import json
import math
import sys
import tempfile
import threading
import unittest
from http.server import HTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import server


class Vector:
    def tolist(self):
        return [1 / math.sqrt(384)] * 384


class Encoder:
    def __init__(self):
        self.inputs = []

    def encode(self, text, normalize_embeddings):
        assert normalize_embeddings
        self.inputs.append(text)
        return Vector()


class EmbeddingContractTest(unittest.TestCase):
    def test_corpus_has_unique_valid_chunks_and_seven_sources(self):
        chunks = server.load_chunks(Path(__file__).resolve().parents[1] / "chunks.jsonl")
        self.assertEqual(58, len(chunks))
        self.assertEqual(7, len({item["source_url"] for item in chunks}))

    def test_duplicate_chunks_are_rejected(self):
        item = {"chunk_id": "a", "v_mysql": 8.4,
                "source_url": "https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html", "text": "요약"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "chunks.jsonl"
            path.write_text(json.dumps(item) + "\n" + json.dumps(item), encoding="utf-8")
            with self.assertRaises(ValueError):
                server.load_chunks(path)

    def test_vector_shape_and_finite_numbers(self):
        self.assertTrue(server.valid_vector(Vector().tolist()))
        self.assertFalse(server.valid_vector([0.1] * 3))
        self.assertFalse(server.valid_vector([float("nan")] * 384))

    def test_internal_endpoint_keeps_query_prefix_and_returns_collection_with_vector(self):
        encoder = Encoder()
        http = HTTPServer(("127.0.0.1", 0), server.handler(encoder, "locklab_v08_0123456789abcdef"))
        thread = threading.Thread(target=http.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:" + str(http.server_port)
        try:
            text = "query: 이 문제에서 IX가 왜 필요한가요?"
            data = json.dumps({"text": text}).encode()
            with urlopen(Request(base + "/embed", data=data), timeout=3) as response:
                result = json.load(response)
            self.assertEqual([text], encoder.inputs)
            self.assertEqual(384, len(result["vector"]))
            self.assertEqual("locklab_v08_0123456789abcdef", result["collection"])
            with self.assertRaises(HTTPError) as error:
                urlopen(Request(base + "/embed", data=b'{"text":"bad prefix"}'), timeout=3)
            self.assertEqual(400, error.exception.code)
            self.assertEqual([text], encoder.inputs)
        finally:
            http.shutdown()
            thread.join()
            http.server_close()


if __name__ == "__main__":
    unittest.main()
