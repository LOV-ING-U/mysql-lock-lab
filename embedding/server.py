"""Internal E5 embedding server. Build the document index before accepting queries."""
import hashlib
import json
import math
import os
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MODEL_ID = "intfloat/multilingual-e5-small"
QDRANT = os.environ.get("QDRANT_URL", "http://qdrant:6333").rstrip("/")


def request(url, method="GET", body=None, timeout=30):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    with urlopen(Request(url, data=data, method=method, headers={"Content-Type": "application/json"}), timeout=timeout) as response:
        return json.load(response)


def wait_for(url):
    for attempt in range(60):
        try:
            return request(url)
        except (URLError, OSError):
            if attempt == 59:
                raise
            time.sleep(1)


def load_chunks(path):
    chunks = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    ids = set()
    for chunk in chunks:
        if (set(chunk) != {"chunk_id", "v_mysql", "source_url", "text"}
                or not isinstance(chunk["chunk_id"], str) or not chunk["chunk_id"]
                or chunk["chunk_id"] in ids or chunk["v_mysql"] != 8.4
                or not chunk["source_url"].startswith("https://dev.mysql.com/doc/refman/8.4/en/")
                or not isinstance(chunk["text"], str) or not chunk["text"].strip()):
            raise ValueError("Invalid or duplicate document chunk")
        ids.add(chunk["chunk_id"])
    if not chunks:
        raise ValueError("No document chunks")
    return chunks


def valid_vector(vector):
    return len(vector) == 384 and all(isinstance(value, (int, float)) and math.isfinite(value) for value in vector)


def prepare_index(model, path):
    chunks = load_chunks(path)
    revision = model[0].auto_model.config._commit_hash
    fingerprint = hashlib.sha256(path.read_bytes() + (MODEL_ID + str(revision) + "passage: ").encode()).hexdigest()[:16]
    collection = "locklab_v08_" + fingerprint
    url = QDRANT + "/collections/" + collection
    wait_for(QDRANT + "/collections")
    try:
        info = request(url)["result"]
        if info["points_count"] == len(chunks) and info["config"]["params"]["vectors"]["size"] == 384:
            print("Reusing document index:", collection, flush=True)
            return collection
    except HTTPError as error:
        if error.code != 404:
            raise
        request(url, "PUT", {"vectors": {"size": 384, "distance": "Cosine"}})
    texts = ["passage: " + chunk["text"] for chunk in chunks]
    if any(len(model.tokenizer.encode(text)) > 512 for text in texts):
        raise ValueError("A document chunk exceeds the model's 512-token limit")
    vectors = model.encode(texts, normalize_embeddings=True, batch_size=8).tolist()
    if len(vectors) != len(chunks) or not all(valid_vector(vector) for vector in vectors):
        raise ValueError("Invalid embedding vectors")
    points = [{"id": str(uuid.uuid5(uuid.NAMESPACE_URL, chunk["chunk_id"])),
               "payload": chunk, "vector": vector} for chunk, vector in zip(chunks, vectors)]
    request(url + "/points?wait=true", "PUT", {"points": points})
    print("Indexed", len(points), "document chunks:", collection, flush=True)
    return collection


def handler(model, collection):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, body):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            self.reply(200, {"ready": True}) if self.path == "/health" else self.reply(404, {"message": "Not found"})

        def do_POST(self):
            if self.path != "/embed":
                self.reply(404, {"message": "Not found"})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 1 or length > 32768:
                    raise ValueError("Invalid request size")
                text = json.loads(self.rfile.read(length)).get("text")
                if not isinstance(text, str) or not text.startswith("query: ") or len(text) > 6000:
                    raise ValueError("Expected a query-prefixed string")
            except (ValueError, AttributeError):
                self.reply(400, {"message": "Invalid embedding input"})
                return
            try:
                vector = model.encode(text, normalize_embeddings=True).tolist()
                if not valid_vector(vector):
                    raise ValueError("Invalid embedding output")
                self.reply(200, {"vector": vector, "collection": collection})
            except Exception as error:
                print("Embedding failed:", error, flush=True)
                self.reply(503, {"message": "Embedding failed"})

    return Handler


if __name__ == "__main__":
    import torch
    from sentence_transformers import SentenceTransformer

    torch.set_num_threads(2)
    print("Loading embedding model:", MODEL_ID, flush=True)
    encoder = SentenceTransformer(MODEL_ID, device="cpu")
    name = prepare_index(encoder, Path(__file__).with_name("chunks.jsonl"))
    print("Embedding ready: " + MODEL_ID + " + Qdrant", flush=True)
    HTTPServer(("0.0.0.0", 8090), handler(encoder, name)).serve_forever()
