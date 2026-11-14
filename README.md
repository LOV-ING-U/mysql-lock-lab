# MySQL Lock Lab

세션 A, B 두 콘솔에서 SQL을 실행하면, InnoDB 락 함수 8개가 불릴 때마다
`lock_t`, `trx_t`, `lock_sys->rec_hash`, `dict_table_t->locks` 상태를 타임라인으로 보여줍니다.
MySQL 8.4.6 공식 디버그 바이너리에 GDB를 붙여 메모리를 읽기만 합니다 (MySQL 소스 수정 없음).

## 실행

```bash
cp .env.example .env        # AI 추가 질문을 쓸 때만 OPENAI_API_KEY 입력
docker compose up --build -d
docker compose logs -f lab  # "Started LockLabApplication" 확인
```

http://localhost:8080 접속 후 순서:

1. 준비 SQL 실행 (추적이 꺼진 상태에서만 가능)
2. **추적 시작** → "GDB 연결됨"이 될 때까지 대기 (디버그 심볼을 읽느라 수십 초)
3. 세션 A·B 콘솔에서 실행·커밋
4. 리셋하면 GDB도 떨어집니다. 다시 준비 SQL → 추적 시작

## 구조

```
브라우저 ──HTTP──▶ Spring ──JDBC──▶ mysqld-debug
                     │                  ▲
                     │ 자식 프로세스      │ ptrace (멈추고 메모리 읽기)
                     ▼                  │
                    gdb + gdb_trace.py ─┘
                     │
                     └─ stdout 'LOCKLAB {json}' ──▶ Spring 메모리 이벤트 목록
```

- `lab` 컨테이너 하나에 mysqld-debug, GDB, Spring이 같이 있습니다.
- Spring이 시작하면 커넥션 admin, A, B를 열고 A·B의 mysqld 스레드 ID(LWP)를 조회합니다.
- **추적 시작**을 누르면 그 LWP를 넘겨 GDB를 띄웁니다. 요청 함수 3개는 A·B 스레드 전용 브레이크포인트,
  해제·승인 함수 5개는 "A·B의 trx일 때만" 조건이 붙은 브레이크포인트라 다른 스레드는 GDB 안에서 바로 통과합니다.
- 테이블 준비(DDL)는 추적이 꺼진 상태에서만 합니다. DDL은 MySQL 내부 테이블에 락을 매우 많이 걸기 때문입니다.
- 커넥션 A, B는 닫지 않습니다. 닫으면 LWP가 바뀝니다.
- `qdrant`, `embedding` 컨테이너는 문제 풀이의 AI 추가 질문(RAG)용입니다.

## 코드

| 파일 | 역할 |
| --- | --- |
| `tracer/gdb_trace.py` | 8개 함수 브레이크포인트, 진입/반환 스냅샷, 포인터 → 라벨(A/B, 정수 id) |
| `tracer/protocol.py` | type_mode 해석, 비트맵 정리 등 순수 함수 (단위 테스트 대상) |
| `Tracer.kt` | GDB 실행, stdout 읽기, 이벤트 목록 |
| `LabService.kt` | 세션 A/B 실행·커밋, 테이블 준비, 리셋 |
| `LabController.kt` | 실험실 API |
| `static/` | 콘솔 2개 + 타임라인 + 문제 풀기 화면 |
| `RagService.kt`, `ProblemService.kt`, `LearningController.kt` | 문제은행·채점·AI 추가 질문 (v0.8 그대로) |

## 실험실 API

| API | 본문 | 응답 |
| --- | --- | --- |
| `POST /api/tables` | `{"sql": "..."}` | `{result, tables}` · 추적 중이거나 세션에 커밋 안 한 트랜잭션이 있으면 409 |
| `POST /api/trace` | `{"on": true\|false}` | GDB 붙이기/떼기 |
| `POST /api/sessions/{A\|B}/run` | `{"sql": "..."}` | `{session, status, open, rows, error, events}` · 커밋하지 않음 |
| `POST /api/sessions/{A\|B}/commit` | `{}` | 위와 같음 |
| `GET /api/timeline?after={seq}` | | `{tracer, tables, sessions, events}` |
| `POST /api/reset` | `{}` | GDB 떼기 → 두 세션 롤백 → `lab` DB 재생성 → 이벤트 삭제 |

`status`: `idle`(트랜잭션 없음) · `running` · `waiting`(락 대기) · `done`(실행 완료, 커밋 전) · `error`

`run`은 SQL이 끝나거나, 이 세션의 락 요청이 `DB_LOCK_WAIT`으로 반환되면 응답합니다.
대기가 풀린 뒤의 이벤트는 브라우저가 `/api/timeline`으로 가져갑니다.

이벤트 하나:

```json
{
  "seq": 5, "session": "B", "fn": "lock_rec_lock", "phase": "return", "result": "DB_LOCK_WAIT",
  "args": {"table": "t", "index": "PRIMARY", "mode": "X,REC_NOT_GAP", "page_id": [5, 4], "heap_no": 3},
  "lock_t": [{"id": 8, "trx": "A", "table": "t", "index": "PRIMARY", "page_id": [5, 4],
              "type_mode": 1059, "mode": "X,REC_NOT_GAP", "bitmap": [0, 0, 0, 1]}],
  "trx_t": [{"id": "B", "trx_locks": [9, 10], "wait_lock": 10, "blocking_trx": "A"}],
  "rec_hash": [{"cell": 1234, "chain": [8, 10]}],
  "tables": [{"name": "t", "locks": [7, 9], "count_by_mode": {"IX": 2}}]
}
```

- `session`: 이 함수를 실행한 스레드. A의 커밋이 B를 깨우는 `lock_grant`는 `session: "A"`입니다.
- `phase`: `enter`(진입 직전) / `return`(반환 직후). 인라인된 호출은 `enter`만 남습니다.
- lock id는 GDB가 붙인 정수이고, 화면은 처음 등장한 순서대로 L1, L2…로 바꿔 보여줍니다.

## 실행

```bash
podman machine start
podman compose up -d --build
```

완료 이후 localhost:8080으로 접속

실제 동작 확인 (화면에서):

1. 준비 SQL 실행 (기본값: `t(id, v)`에 10, 20, 30) → 추적 시작
2. 세션 A `SELECT * FROM t WHERE id = 20 FOR UPDATE;` 실행 → `lock_table` IX, `lock_rec_lock` X,REC_NOT_GAP
3. 세션 B 같은 SQL 실행 → B가 "락 대기 중", 마지막 이벤트 `DB_LOCK_WAIT`, `trx_t` B의 `blocking_trx = A`
4. 세션 A 커밋 → `lock_trx_release_locks` → `lock_rec_discard` → `lock_grant` → `lock_table_remove_low`, B가 "실행 완료"로 바뀜

## 한계

- 명시적 락 객체만 보입니다. 충돌 없는 INSERT는 lock_t를 만들지 않으므로(암묵적 락) 레코드 락이 보이지 않는 것이 정상입니다.
- GDB가 멈추는 시간이 들어가므로 타이밍은 성능 측정값이 아닙니다.
- 락 대기 타임아웃(5분)은 그 SQL 문만 롤백합니다. 이전에 잡은 락은 커밋 전까지 남습니다.
