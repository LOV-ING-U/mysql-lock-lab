"""Run one real RAG question. Source coverage and simple output checks are not a correctness proof."""
import argparse
import json
import re
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", default="http://127.0.0.1:8080")
args = parser.parse_args()
question = "gap lock과 record lock의 구별 기준은 뭐에요?"
request = Request(args.url.rstrip("/") + "/ask/1", method="POST",
                  data=json.dumps({"question": question, "user_answer": ["A", "C"]}).encode(),
                  headers={"Content-Type": "application/json"})
started = time.monotonic()
try:
    with urlopen(request, timeout=125) as response:
        result = json.load(response)
except HTTPError as error:
    print(json.dumps({"status": error.code, "error": json.load(error)}, ensure_ascii=False, indent=2))
    raise SystemExit(1)
except OSError as error:
    print(json.dumps({"error": str(error)}, ensure_ascii=False))
    raise SystemExit(1)
ids = {source["chunk_id"] for source in result.get("sources", [])}
reply = result.get("reply", "")
checks = {
    "record_definition_source": "mysql84-locking-index-record" in ids,
    "gap_definition_source": "mysql84-locking-gap-coexistence" in ids,
    "mentions_both_concepts": bool(re.search(r"갭|gap", reply, re.I) and re.search(r"레코드|record", reply, re.I)),
    "explains_insertion": bool(re.search(r"삽입|insert", reply, re.I)),
    "does_not_ask_user_to_explain": not bool(re.search(r"설명해\s*주시면|설명해\s*주세요|알려\s*주실\s*수|알려\s*주세요", reply)),
}
print(json.dumps({"question": question, "seconds": round(time.monotonic() - started, 3),
                  "checks": checks, "reply": reply, "sources": result.get("sources", []),
                  "note": "문서 후보의 포함 여부와 단순 표현 검사입니다. 답변 사실성과 인용은 사람이 확인해야 합니다."},
                 ensure_ascii=False, indent=2))
raise SystemExit(0 if all(checks.values()) else 1)
