# Failed-query retrieval rank audit

- Query numbers: 2, 3, 12, 15
- No OpenAI calls; original documents, questions and relevance labels unchanged.
- Full corpus ranking requested instead of only the first three documents.
- Cosine similarities are ranking scores, not answer-correctness probabilities.
- Existing embedding service reused; warm-up performed.
- Top-k variants below change the cutoff, not the retrieval algorithm.

Collection: `locklab_v08_aa64e0040e6580f9`

## Four-question aggregate

| k | Hit@k | Mean Recall@k | MRR@k |
|---:|---:|---:|---:|
| 3 | 0.0000 | 0.0000 | 0.0000 |
| 5 | 0.2500 | 0.0833 | 0.0500 |
| 10 | 0.2500 | 0.0833 | 0.0500 |
| 20 | 0.7500 | 0.5417 | 0.0936 |
| 58 | 1.0000 | 1.0000 | 0.0997 |

## 2. Gap Lock끼리는 충돌하나요?

Baseline top-3 order matches: True

### Expected document ranks

| chunk_id | Rank | Similarity | Title |
|---|---:|---:|---|
| `mysql84-locking-gap-coexistence` | 41 | 0.818473 | 갭락 공존 |

Embedding HTTP: 117.57 ms; Qdrant HTTP: 52.565 ms; total: 170.135 ms.

### Full ranking

| Rank | chunk_id | Similarity | Labeled relevant | Title |
|---:|---|---:|:---:|---|
| 1 | `mysql84-locks-set-lock-tables` | 0.872495 |  | LOCK TABLES 경계 |
| 2 | `mysql84-autocommit-end-release` | 0.866165 |  | 종료와 락해제 |
| 3 | `mysql84-locks-set-replace` | 0.857260 |  | REPLACE 충돌 |
| 4 | `mysql84-locking-reads-privileges` | 0.843554 |  | 잠금읽기 권한 |
| 5 | `mysql84-locks-set-insert-select` | 0.843207 |  | INSERT…SELECT 소스 |
| 6 | `mysql84-locking-table-compatibility` | 0.842130 |  | 테이블락 호환성 |
| 7 | `mysql84-consistent-read-no-data-locks` | 0.840667 |  | 일반SELECT 잠금 |
| 8 | `mysql84-isolation-default` | 0.840249 |  | 격리수준 선택 |
| 9 | `mysql84-locking-rc-gap-exceptions` | 0.839486 |  | RC 갭락예외 |
| 10 | `mysql84-locking-reads-update` | 0.839219 |  | 배타 잠금읽기 |
| 11 | `mysql84-consistent-read-dml-difference` | 0.839101 |  | DML 가시성 |
| 12 | `mysql84-locking-auto-inc` | 0.836702 |  | 자동증가 잠금 |
| 13 | `mysql84-locking-intention-protocol` | 0.836421 |  | 의도 락과 획득 순서 |
| 14 | `mysql84-consistent-read-ddl` | 0.836065 |  | DDL 제한 |
| 15 | `mysql84-autocommit-disconnect` | 0.836038 |  | 접속종료 롤백 |
| 16 | `mysql84-locking-unique-existing` | 0.834228 |  | 유니크 조회의 예외 |
| 17 | `mysql84-isolation-rc-read-gap` | 0.833217 |  | RC 읽기·갭 |
| 18 | `mysql84-locks-set-insert-duplicate` | 0.832791 |  | INSERT 중복오류 |
| 19 | `mysql84-autocommit-implicit-api` | 0.832580 |  | 암묵적 커밋 |
| 20 | `mysql84-locking-sx-compatibility` | 0.832141 |  | S·X 호환성 |
| 21 | `mysql84-locking-reads-nowait` | 0.831905 |  | NOWAIT 동작 |
| 22 | `mysql84-consistent-read-embedded-select` | 0.829861 |  | 내장SELECT 주의 |
| 23 | `mysql84-locks-set-secondary-clustered` | 0.828067 |  | 보조인덱스 잠금 |
| 24 | `mysql84-isolation-rr-locking-range` | 0.828046 |  | RR 유니크·범위 |
| 25 | `mysql84-locking-reads-subquery` | 0.827594 |  | 서브쿼리 범위 |
| 26 | `mysql84-locks-set-upsert` | 0.827584 |  | UPSERT 잠금 |
| 27 | `mysql84-isolation-scan-nonmatching` | 0.827151 |  | 스캔과 비일치행 |
| 28 | `mysql84-locking-insert-intention` | 0.826886 |  | 삽입의도 공존 |
| 29 | `mysql84-locks-set-auto-inc-duration` | 0.826411 |  | AUTO-INC 유지시간 |
| 30 | `mysql84-locking-index-record` | 0.825895 |  | 레코드락 대상 |
| 31 | `mysql84-locking-reads-share` | 0.825515 |  | 공유 잠금읽기 |
| 32 | `mysql84-autocommit-autocommit-off` | 0.825210 |  | 자동커밋 해제 |
| 33 | `mysql84-locking-reads-replication` | 0.824862 |  | 복제 주의 |
| 34 | `mysql84-locking-reads-skip-locked` | 0.822970 |  | SKIP LOCKED 동작 |
| 35 | `mysql84-isolation-serializable-autocommit` | 0.822832 |  | SERIALIZABLE 조건 |
| 36 | `mysql84-consistent-read-mixed-versions` | 0.822659 |  | 자기변경 예외 |
| 37 | `mysql84-phantoms-definition` | 0.822432 |  | 팬텀 현상 |
| 38 | `mysql84-locking-reads-counter` | 0.821437 |  | 카운터 갱신 |
| 39 | `mysql84-isolation-rc-semi-consistent` | 0.821210 |  | RC UPDATE 판정 |
| 40 | `mysql84-locks-set-unlock-union` | 0.820461 |  | 잠금해제 지연 |
| 41 | `mysql84-locking-gap-coexistence` | 0.818473 | YES | 갭락 공존 |
| 42 | `mysql84-locking-reads-transaction` | 0.818283 |  | 잠금읽기 전제 |
| 43 | `mysql84-phantoms-range-example` | 0.817963 |  | 행잠금의 한계 |
| 44 | `mysql84-autocommit-explicit-transaction` | 0.816699 |  | 명시적 트랜잭션 |
| 45 | `mysql84-locking-spatial` | 0.816309 |  | 공간인덱스 잠금 |
| 46 | `mysql84-consistent-read-visibility` | 0.814549 |  | 스냅샷 가시성 |
| 47 | `mysql84-phantoms-absence` | 0.811500 |  | 비존재 키보호 |
| 48 | `mysql84-phantoms-next-key-tail` | 0.811231 |  | 범위·끝구간 보호 |
| 49 | `mysql84-locks-set-scan-range` | 0.811217 |  | 스캔범위 잠금 |
| 50 | `mysql84-isolation-ru` | 0.809296 |  | RU 더티리드 |
| 51 | `mysql84-consistent-read-rr` | 0.805146 |  | RR 읽기시점 |
| 52 | `mysql84-autocommit-autocommit-default` | 0.805050 |  | 자동커밋 기본 |
| 53 | `mysql84-locking-supremum` | 0.803158 |  | 마지막 갭 |
| 54 | `mysql84-consistent-read-rc` | 0.801502 |  | RC 읽기시점 |
| 55 | `mysql84-locks-set-foreign-key` | 0.800586 |  | FK 검사잠금 |
| 56 | `mysql84-locking-next-key` | 0.800072 |  | 넥스트키 구성 |
| 57 | `mysql84-isolation-rr-read-mix` | 0.799389 |  | RR 읽기혼합 |
| 58 | `mysql84-isolation-rr-snapshot` | 0.792884 |  | RR 스냅샷 |

## 3. RR에서 트랜잭션을 시작하고 존재하는 PK 행을 FOR UPDATE로 찾았어요. 앞쪽 Gap Lock도 생기나요?

Baseline top-3 order matches: True

### Expected document ranks

| chunk_id | Rank | Similarity | Title |
|---|---:|---:|---|
| `mysql84-locking-unique-existing` | 13 | 0.845678 | 유니크 조회의 예외 |
| `mysql84-isolation-rr-locking-range` | 11 | 0.848769 | RR 유니크·범위 |
| `mysql84-locking-reads-transaction` | 20 | 0.841277 | 잠금읽기 전제 |

Embedding HTTP: 175.237 ms; Qdrant HTTP: 3.78 ms; total: 179.018 ms.

### Full ranking

| Rank | chunk_id | Similarity | Labeled relevant | Title |
|---:|---|---:|:---:|---|
| 1 | `mysql84-isolation-scan-nonmatching` | 0.863436 |  | 스캔과 비일치행 |
| 2 | `mysql84-autocommit-end-release` | 0.862286 |  | 종료와 락해제 |
| 3 | `mysql84-locking-reads-update` | 0.860164 |  | 배타 잠금읽기 |
| 4 | `mysql84-locking-rc-gap-exceptions` | 0.856911 |  | RC 갭락예외 |
| 5 | `mysql84-consistent-read-dml-difference` | 0.856141 |  | DML 가시성 |
| 6 | `mysql84-locks-set-lock-tables` | 0.855239 |  | LOCK TABLES 경계 |
| 7 | `mysql84-locking-reads-privileges` | 0.853436 |  | 잠금읽기 권한 |
| 8 | `mysql84-locking-reads-share` | 0.851473 |  | 공유 잠금읽기 |
| 9 | `mysql84-locks-set-insert-select` | 0.851440 |  | INSERT…SELECT 소스 |
| 10 | `mysql84-isolation-rc-semi-consistent` | 0.849416 |  | RC UPDATE 판정 |
| 11 | `mysql84-isolation-rr-locking-range` | 0.848769 | YES | RR 유니크·범위 |
| 12 | `mysql84-consistent-read-no-data-locks` | 0.847205 |  | 일반SELECT 잠금 |
| 13 | `mysql84-locking-unique-existing` | 0.845678 | YES | 유니크 조회의 예외 |
| 14 | `mysql84-locking-intention-protocol` | 0.845409 |  | 의도 락과 획득 순서 |
| 15 | `mysql84-autocommit-autocommit-off` | 0.845263 |  | 자동커밋 해제 |
| 16 | `mysql84-isolation-rc-read-gap` | 0.845075 |  | RC 읽기·갭 |
| 17 | `mysql84-autocommit-explicit-transaction` | 0.844771 |  | 명시적 트랜잭션 |
| 18 | `mysql84-locks-set-upsert` | 0.843444 |  | UPSERT 잠금 |
| 19 | `mysql84-consistent-read-mixed-versions` | 0.842031 |  | 자기변경 예외 |
| 20 | `mysql84-locking-reads-transaction` | 0.841277 | YES | 잠금읽기 전제 |
| 21 | `mysql84-autocommit-implicit-api` | 0.841151 |  | 암묵적 커밋 |
| 22 | `mysql84-consistent-read-rr` | 0.840419 |  | RR 읽기시점 |
| 23 | `mysql84-consistent-read-embedded-select` | 0.840265 |  | 내장SELECT 주의 |
| 24 | `mysql84-autocommit-disconnect` | 0.839671 |  | 접속종료 롤백 |
| 25 | `mysql84-isolation-default` | 0.839161 |  | 격리수준 선택 |
| 26 | `mysql84-locks-set-insert-duplicate` | 0.834750 |  | INSERT 중복오류 |
| 27 | `mysql84-locking-reads-subquery` | 0.833120 |  | 서브쿼리 범위 |
| 28 | `mysql84-isolation-rr-snapshot` | 0.832849 |  | RR 스냅샷 |
| 29 | `mysql84-phantoms-definition` | 0.832801 |  | 팬텀 현상 |
| 30 | `mysql84-consistent-read-rc` | 0.831559 |  | RC 읽기시점 |
| 31 | `mysql84-consistent-read-visibility` | 0.826085 |  | 스냅샷 가시성 |
| 32 | `mysql84-locking-auto-inc` | 0.824992 |  | 자동증가 잠금 |
| 33 | `mysql84-locks-set-replace` | 0.823874 |  | REPLACE 충돌 |
| 34 | `mysql84-locking-reads-counter` | 0.823558 |  | 카운터 갱신 |
| 35 | `mysql84-phantoms-next-key-tail` | 0.822085 |  | 범위·끝구간 보호 |
| 36 | `mysql84-locking-reads-skip-locked` | 0.821574 |  | SKIP LOCKED 동작 |
| 37 | `mysql84-locking-insert-intention` | 0.821444 |  | 삽입의도 공존 |
| 38 | `mysql84-isolation-serializable-autocommit` | 0.821296 |  | SERIALIZABLE 조건 |
| 39 | `mysql84-locking-reads-replication` | 0.821153 |  | 복제 주의 |
| 40 | `mysql84-locking-reads-nowait` | 0.821135 |  | NOWAIT 동작 |
| 41 | `mysql84-locking-sx-compatibility` | 0.819590 |  | S·X 호환성 |
| 42 | `mysql84-isolation-rr-read-mix` | 0.818977 |  | RR 읽기혼합 |
| 43 | `mysql84-locking-spatial` | 0.816518 |  | 공간인덱스 잠금 |
| 44 | `mysql84-locks-set-scan-range` | 0.816248 |  | 스캔범위 잠금 |
| 45 | `mysql84-locks-set-secondary-clustered` | 0.815679 |  | 보조인덱스 잠금 |
| 46 | `mysql84-locking-next-key` | 0.814839 |  | 넥스트키 구성 |
| 47 | `mysql84-consistent-read-ddl` | 0.814226 |  | DDL 제한 |
| 48 | `mysql84-phantoms-absence` | 0.812817 |  | 비존재 키보호 |
| 49 | `mysql84-locks-set-unlock-union` | 0.812070 |  | 잠금해제 지연 |
| 50 | `mysql84-isolation-ru` | 0.811941 |  | RU 더티리드 |
| 51 | `mysql84-locks-set-auto-inc-duration` | 0.809633 |  | AUTO-INC 유지시간 |
| 52 | `mysql84-locks-set-foreign-key` | 0.806578 |  | FK 검사잠금 |
| 53 | `mysql84-locking-supremum` | 0.800366 |  | 마지막 갭 |
| 54 | `mysql84-locking-gap-coexistence` | 0.800325 |  | 갭락 공존 |
| 55 | `mysql84-phantoms-range-example` | 0.799947 |  | 행잠금의 한계 |
| 56 | `mysql84-locking-index-record` | 0.797671 |  | 레코드락 대상 |
| 57 | `mysql84-locking-table-compatibility` | 0.792518 |  | 테이블락 호환성 |
| 58 | `mysql84-autocommit-autocommit-default` | 0.784760 |  | 자동커밋 기본 |

## 12. 조회한 행만 잠그면 되는데 왜 사이 구간과 마지막 레코드 뒤까지 막아요?

Baseline top-3 order matches: True

### Expected document ranks

| chunk_id | Rank | Similarity | Title |
|---|---:|---:|---|
| `mysql84-phantoms-range-example` | 5 | 0.866148 | 행잠금의 한계 |
| `mysql84-phantoms-next-key-tail` | 15 | 0.857319 | 범위·끝구간 보호 |
| `mysql84-locking-supremum` | 37 | 0.836891 | 마지막 갭 |

Embedding HTTP: 33.259 ms; Qdrant HTTP: 3.664 ms; total: 36.923 ms.

### Full ranking

| Rank | chunk_id | Similarity | Labeled relevant | Title |
|---:|---|---:|:---:|---|
| 1 | `mysql84-isolation-scan-nonmatching` | 0.871087 |  | 스캔과 비일치행 |
| 2 | `mysql84-locks-set-insert-select` | 0.868889 |  | INSERT…SELECT 소스 |
| 3 | `mysql84-locking-reads-share` | 0.866651 |  | 공유 잠금읽기 |
| 4 | `mysql84-locks-set-unlock-union` | 0.866284 |  | 잠금해제 지연 |
| 5 | `mysql84-phantoms-range-example` | 0.866148 | YES | 행잠금의 한계 |
| 6 | `mysql84-locking-reads-privileges` | 0.865824 |  | 잠금읽기 권한 |
| 7 | `mysql84-locking-unique-existing` | 0.864978 |  | 유니크 조회의 예외 |
| 8 | `mysql84-locking-reads-skip-locked` | 0.864428 |  | SKIP LOCKED 동작 |
| 9 | `mysql84-consistent-read-no-data-locks` | 0.863281 |  | 일반SELECT 잠금 |
| 10 | `mysql84-autocommit-disconnect` | 0.862744 |  | 접속종료 롤백 |
| 11 | `mysql84-autocommit-end-release` | 0.861764 |  | 종료와 락해제 |
| 12 | `mysql84-locking-reads-subquery` | 0.861325 |  | 서브쿼리 범위 |
| 13 | `mysql84-locking-intention-protocol` | 0.858308 |  | 의도 락과 획득 순서 |
| 14 | `mysql84-locks-set-lock-tables` | 0.857713 |  | LOCK TABLES 경계 |
| 15 | `mysql84-phantoms-next-key-tail` | 0.857319 | YES | 범위·끝구간 보호 |
| 16 | `mysql84-isolation-default` | 0.857213 |  | 격리수준 선택 |
| 17 | `mysql84-isolation-rc-semi-consistent` | 0.857023 |  | RC UPDATE 판정 |
| 18 | `mysql84-consistent-read-embedded-select` | 0.853779 |  | 내장SELECT 주의 |
| 19 | `mysql84-locking-auto-inc` | 0.853464 |  | 자동증가 잠금 |
| 20 | `mysql84-locks-set-insert-duplicate` | 0.852878 |  | INSERT 중복오류 |
| 21 | `mysql84-locking-reads-nowait` | 0.852589 |  | NOWAIT 동작 |
| 22 | `mysql84-locking-reads-update` | 0.850252 |  | 배타 잠금읽기 |
| 23 | `mysql84-locks-set-secondary-clustered` | 0.849642 |  | 보조인덱스 잠금 |
| 24 | `mysql84-isolation-serializable-autocommit` | 0.849259 |  | SERIALIZABLE 조건 |
| 25 | `mysql84-consistent-read-dml-difference` | 0.848704 |  | DML 가시성 |
| 26 | `mysql84-autocommit-implicit-api` | 0.848221 |  | 암묵적 커밋 |
| 27 | `mysql84-locks-set-upsert` | 0.844796 |  | UPSERT 잠금 |
| 28 | `mysql84-locks-set-auto-inc-duration` | 0.844478 |  | AUTO-INC 유지시간 |
| 29 | `mysql84-locks-set-scan-range` | 0.844256 |  | 스캔범위 잠금 |
| 30 | `mysql84-locking-index-record` | 0.843685 |  | 레코드락 대상 |
| 31 | `mysql84-locking-reads-replication` | 0.840420 |  | 복제 주의 |
| 32 | `mysql84-consistent-read-ddl` | 0.840342 |  | DDL 제한 |
| 33 | `mysql84-locking-reads-transaction` | 0.839658 |  | 잠금읽기 전제 |
| 34 | `mysql84-isolation-ru` | 0.839073 |  | RU 더티리드 |
| 35 | `mysql84-isolation-rr-locking-range` | 0.838469 |  | RR 유니크·범위 |
| 36 | `mysql84-locks-set-foreign-key` | 0.836916 |  | FK 검사잠금 |
| 37 | `mysql84-locking-supremum` | 0.836891 | YES | 마지막 갭 |
| 38 | `mysql84-consistent-read-mixed-versions` | 0.835174 |  | 자기변경 예외 |
| 39 | `mysql84-locking-reads-counter` | 0.834835 |  | 카운터 갱신 |
| 40 | `mysql84-locks-set-replace` | 0.833386 |  | REPLACE 충돌 |
| 41 | `mysql84-locking-rc-gap-exceptions` | 0.832325 |  | RC 갭락예외 |
| 42 | `mysql84-phantoms-definition` | 0.830108 |  | 팬텀 현상 |
| 43 | `mysql84-locking-next-key` | 0.829070 |  | 넥스트키 구성 |
| 44 | `mysql84-isolation-rc-read-gap` | 0.829036 |  | RC 읽기·갭 |
| 45 | `mysql84-isolation-rr-read-mix` | 0.828529 |  | RR 읽기혼합 |
| 46 | `mysql84-autocommit-autocommit-off` | 0.828433 |  | 자동커밋 해제 |
| 47 | `mysql84-consistent-read-rc` | 0.827395 |  | RC 읽기시점 |
| 48 | `mysql84-autocommit-explicit-transaction` | 0.826982 |  | 명시적 트랜잭션 |
| 49 | `mysql84-consistent-read-rr` | 0.825305 |  | RR 읽기시점 |
| 50 | `mysql84-phantoms-absence` | 0.824038 |  | 비존재 키보호 |
| 51 | `mysql84-locking-sx-compatibility` | 0.823719 |  | S·X 호환성 |
| 52 | `mysql84-locking-spatial` | 0.821792 |  | 공간인덱스 잠금 |
| 53 | `mysql84-consistent-read-visibility` | 0.817953 |  | 스냅샷 가시성 |
| 54 | `mysql84-locking-gap-coexistence` | 0.817748 |  | 갭락 공존 |
| 55 | `mysql84-locking-table-compatibility` | 0.812658 |  | 테이블락 호환성 |
| 56 | `mysql84-locking-insert-intention` | 0.809761 |  | 삽입의도 공존 |
| 57 | `mysql84-isolation-rr-snapshot` | 0.808856 |  | RR 스냅샷 |
| 58 | `mysql84-autocommit-autocommit-default` | 0.807652 |  | 자동커밋 기본 |

## 15. gap lock과 record lock의 구별 기준은 뭐에요?

Baseline top-3 order matches: True

### Expected document ranks

| chunk_id | Rank | Similarity | Title |
|---|---:|---:|---|
| `mysql84-locking-index-record` | 12 | 0.830129 | 레코드락 대상 |
| `mysql84-locking-gap-coexistence` | 53 | 0.799329 | 갭락 공존 |

Embedding HTTP: 27.685 ms; Qdrant HTTP: 3.967 ms; total: 31.651 ms.

### Full ranking

| Rank | chunk_id | Similarity | Labeled relevant | Title |
|---:|---|---:|:---:|---|
| 1 | `mysql84-locks-set-lock-tables` | 0.862567 |  | LOCK TABLES 경계 |
| 2 | `mysql84-autocommit-end-release` | 0.858101 |  | 종료와 락해제 |
| 3 | `mysql84-consistent-read-no-data-locks` | 0.847461 |  | 일반SELECT 잠금 |
| 4 | `mysql84-locking-rc-gap-exceptions` | 0.846683 |  | RC 갭락예외 |
| 5 | `mysql84-locks-set-insert-select` | 0.844991 |  | INSERT…SELECT 소스 |
| 6 | `mysql84-isolation-default` | 0.844632 |  | 격리수준 선택 |
| 7 | `mysql84-isolation-rc-read-gap` | 0.839317 |  | RC 읽기·갭 |
| 8 | `mysql84-isolation-rr-locking-range` | 0.837258 |  | RR 유니크·범위 |
| 9 | `mysql84-locking-reads-privileges` | 0.834833 |  | 잠금읽기 권한 |
| 10 | `mysql84-locking-intention-protocol` | 0.834556 |  | 의도 락과 획득 순서 |
| 11 | `mysql84-locking-reads-update` | 0.832614 |  | 배타 잠금읽기 |
| 12 | `mysql84-locking-index-record` | 0.830129 | YES | 레코드락 대상 |
| 13 | `mysql84-locking-auto-inc` | 0.830123 |  | 자동증가 잠금 |
| 14 | `mysql84-autocommit-disconnect` | 0.826675 |  | 접속종료 롤백 |
| 15 | `mysql84-consistent-read-embedded-select` | 0.825337 |  | 내장SELECT 주의 |
| 16 | `mysql84-isolation-scan-nonmatching` | 0.823195 |  | 스캔과 비일치행 |
| 17 | `mysql84-locking-reads-replication` | 0.821465 |  | 복제 주의 |
| 18 | `mysql84-locking-reads-nowait` | 0.820166 |  | NOWAIT 동작 |
| 19 | `mysql84-locking-unique-existing` | 0.819853 |  | 유니크 조회의 예외 |
| 20 | `mysql84-locking-reads-skip-locked` | 0.819577 |  | SKIP LOCKED 동작 |
| 21 | `mysql84-consistent-read-dml-difference` | 0.819349 |  | DML 가시성 |
| 22 | `mysql84-locking-reads-share` | 0.818671 |  | 공유 잠금읽기 |
| 23 | `mysql84-isolation-rc-semi-consistent` | 0.817661 |  | RC UPDATE 판정 |
| 24 | `mysql84-locks-set-insert-duplicate` | 0.817474 |  | INSERT 중복오류 |
| 25 | `mysql84-locks-set-upsert` | 0.817117 |  | UPSERT 잠금 |
| 26 | `mysql84-phantoms-next-key-tail` | 0.817040 |  | 범위·끝구간 보호 |
| 27 | `mysql84-locking-spatial` | 0.816863 |  | 공간인덱스 잠금 |
| 28 | `mysql84-locking-reads-transaction` | 0.816857 |  | 잠금읽기 전제 |
| 29 | `mysql84-locks-set-secondary-clustered` | 0.816070 |  | 보조인덱스 잠금 |
| 30 | `mysql84-locks-set-replace` | 0.815352 |  | REPLACE 충돌 |
| 31 | `mysql84-autocommit-implicit-api` | 0.815210 |  | 암묵적 커밋 |
| 32 | `mysql84-consistent-read-ddl` | 0.813098 |  | DDL 제한 |
| 33 | `mysql84-locking-reads-subquery` | 0.812284 |  | 서브쿼리 범위 |
| 34 | `mysql84-isolation-serializable-autocommit` | 0.811626 |  | SERIALIZABLE 조건 |
| 35 | `mysql84-consistent-read-rc` | 0.810420 |  | RC 읽기시점 |
| 36 | `mysql84-locking-insert-intention` | 0.810232 |  | 삽입의도 공존 |
| 37 | `mysql84-locks-set-auto-inc-duration` | 0.810072 |  | AUTO-INC 유지시간 |
| 38 | `mysql84-locking-reads-counter` | 0.808540 |  | 카운터 갱신 |
| 39 | `mysql84-autocommit-explicit-transaction` | 0.808243 |  | 명시적 트랜잭션 |
| 40 | `mysql84-autocommit-autocommit-off` | 0.807999 |  | 자동커밋 해제 |
| 41 | `mysql84-locks-set-scan-range` | 0.807257 |  | 스캔범위 잠금 |
| 42 | `mysql84-phantoms-definition` | 0.806556 |  | 팬텀 현상 |
| 43 | `mysql84-locking-sx-compatibility` | 0.805418 |  | S·X 호환성 |
| 44 | `mysql84-locks-set-unlock-union` | 0.804149 |  | 잠금해제 지연 |
| 45 | `mysql84-phantoms-absence` | 0.804135 |  | 비존재 키보호 |
| 46 | `mysql84-consistent-read-mixed-versions` | 0.803460 |  | 자기변경 예외 |
| 47 | `mysql84-consistent-read-rr` | 0.803136 |  | RR 읽기시점 |
| 48 | `mysql84-autocommit-autocommit-default` | 0.803112 |  | 자동커밋 기본 |
| 49 | `mysql84-locks-set-foreign-key` | 0.802602 |  | FK 검사잠금 |
| 50 | `mysql84-phantoms-range-example` | 0.801583 |  | 행잠금의 한계 |
| 51 | `mysql84-locking-table-compatibility` | 0.799971 |  | 테이블락 호환성 |
| 52 | `mysql84-consistent-read-visibility` | 0.799697 |  | 스냅샷 가시성 |
| 53 | `mysql84-locking-gap-coexistence` | 0.799329 | YES | 갭락 공존 |
| 54 | `mysql84-isolation-ru` | 0.799173 |  | RU 더티리드 |
| 55 | `mysql84-locking-next-key` | 0.795846 |  | 넥스트키 구성 |
| 56 | `mysql84-isolation-rr-read-mix` | 0.794556 |  | RR 읽기혼합 |
| 57 | `mysql84-isolation-rr-snapshot` | 0.788165 |  | RR 스냅샷 |
| 58 | `mysql84-locking-supremum` | 0.785499 |  | 마지막 갭 |
