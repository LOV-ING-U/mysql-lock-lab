# LockLab RAG evaluation

- Mode: answers
- Date: 2026-10-18T21:37:29.842104+09:00
- Corpus: 58 chunks

## Summary

```json
{
  "total_cases": 15,
  "completed_cases": 15,
  "error_cases": 0,
  "mean_hit_at_3": 0.7333,
  "mean_recall_at_3": 0.5333,
  "mean_reciprocal_rank_at_3": 0.6556,
  "median_elapsed_ms": 3326.865
}
```

Hit@3: at least one labeled relevant chunk appears in the first three results.
Recall@3: retrieved labeled relevant chunks / all labeled relevant chunks, averaged per query.
MRR@3: reciprocal rank of the first labeled relevant chunk, or zero if absent.

## Cases

| Case | Hit@3 | Recall@3 | Time ms | Missing labels |
|---|---:|---:|---:|---|
| intent-vs-x | 1 | 1.0 | 6850.421 |  |
| gap-coexist | 0 | 0.0 | 3326.865 | mysql84-locking-gap-coexistence |
| existing-pk | 0 | 0.0 | 4152.425 | mysql84-isolation-rr-locking-range, mysql84-locking-reads-transaction, mysql84-locking-unique-existing |
| insert-shared | 1 | 0.5 | 4096.643 | mysql84-locks-set-foreign-key |
| secondary-index | 1 | 1.0 | 2864.268 |  |
| rc-gap | 1 | 0.5 | 2050.537 | mysql84-isolation-rc-read-gap |
| release | 1 | 0.5 | 1857.351 | mysql84-locking-reads-transaction |
| explicit-with-autocommit | 1 | 1.0 | 2016.466 |  |
| old-value | 1 | 0.5 | 3038.7 | mysql84-isolation-rr-snapshot |
| subquery | 1 | 1.0 | 1876.966 |  |
| skip-vs-nowait | 1 | 1.0 | 3439.42 |  |
| phantom | 0 | 0.0 | 6638.001 | mysql84-locking-supremum, mysql84-phantoms-next-key-tail, mysql84-phantoms-range-example |
| serializable | 1 | 0.5 | 2831.869 | mysql84-autocommit-explicit-transaction |
| auto-inc-lifetime | 1 | 0.5 | 3626.82 | mysql84-locks-set-auto-inc-duration |
| gap-vs-record | 0 | 0.0 | 4466.434 | mysql84-locking-gap-coexistence, mysql84-locking-index-record |

## Limitations

- 15 existing developer-curated queries; not an independent held-out benchmark.
- Relevant chunk labels are not guaranteed exhaustive and require human review.
- Metrics describe retrieval, not factual correctness of generated answers.
- Latency is a single sequential run, not a throughput or load benchmark.
- Transport errors are listed separately and excluded from retrieval metric means.

## intent-vs-x

X가 있는데 IX는 왜 따로 있어요?

1. `mysql84-locking-intention-protocol`

의도 락과 획득 순서
IS·IX는 테이블의 행 잠금 의도다. 행 S 전에는 IS 이상, X 전에는 IX가 필요하다. FOR SHARE는 IS, FOR UPDATE는 IX를 요청한다.

2. `mysql84-locking-table-compatibility`

테이블락 호환성
테이블 IX끼리·IS/IX는 호환된다. 테이블 X는 다른 모드와, 테이블 S는 IX와 충돌한다.

3. `mysql84-locks-set-insert-select`

INSERT…SELECT 소스
INSERT…SELECT의 대상 행은 X 레코드 락이다. 소스는 RC에서 일관 읽기, RR에서는 S 넥스트키 락을 사용한다. CREATE…SELECT도 같은 원칙을 따른다.

### Generated answer (not yet human-graded)

**X와 IX는 잠그는 범위와 역할이 다릅니다.**

- **X 락**은 특정 행이나 인덱스 레코드를 배타적으로 잠급니다.
- **IX(의도적 배타) 락**은 “이 테이블 안의 일부 행에 X 락을 걸 예정이거나 걸었다”는 표시입니다. 테이블 전체를 X 락으로 잠근다는 뜻은 아닙니다. [mysql84-locking-intention-protocol]

IX가 따로 있는 이유는 테이블 수준 잠금과 행 수준 잠금이 충돌하는지 빠르게 판단하기 위해서입니다. 예를 들어 다른 트랜잭션이 테이블 전체에 S 락을 요청하면, InnoDB는 각 행의 잠금을 일일이 확인하지 않고 테이블의 IX를 보고 충돌을 판단할 수 있습니다. IX끼리는 호환되므로 서로 다른 행을 잠그는 트랜잭션은 대체로 함께 진행할 수 있지만, 테이블 전체의 S 락과 IX는 충돌합니다. [mysql84-locking-table-compatibility]

따라서 `SELECT ... FOR UPDATE`처럼 특정 행을 잠그는 작업에서는 **테이블에 IX를 두고, 실제 대상 행에 X 락을 두는 것**이 서로 다른 층위의 잠금입니다. [mysql84-locking-intention-protocol]

Human review: answer correctness / evidence support / insufficient-information handling.

## gap-coexist

Gap Lock끼리는 충돌하나요?

1. `mysql84-locks-set-lock-tables`

LOCK TABLES 경계
LOCK TABLES에는 MySQL·InnoDB 계층의 락이 관여한다. 호출은 기존 트랜잭션을 암묵적으로 커밋한다.

2. `mysql84-autocommit-end-release`

종료와 락해제
COMMIT은 변경을 확정하고 ROLLBACK은 취소한다. 둘 다 현재 트랜잭션에 설정된 InnoDB 락을 해제한다.

3. `mysql84-locks-set-replace`

REPLACE 충돌
REPLACE는 충돌이 없으면 INSERT처럼 처리한다. 유니크 키 충돌 시 교체 대상에 X 넥스트키 락을 사용한다.

### Generated answer (not yet human-graded)

아니요. InnoDB에서 **Gap Lock끼리는 서로 충돌하지 않습니다.** 여러 트랜잭션이 같은 갭에 Gap Lock을 함께 보유할 수 있습니다.

다만 Gap Lock은 삽입을 막는 용도이므로, 그 갭에 레코드를 넣으려는 **Insert Intention Lock**과는 충돌할 수 있습니다. [mysql84-locks-set-lock-tables]

참고 문서에는 Gap Lock의 호환성 규칙이 직접 담겨 있지 않아, 위 규칙을 뒷받침할 관련 문서 근거는 제공된 자료에서 확인할 수 없습니다.

Human review: answer correctness / evidence support / insufficient-information handling.

## existing-pk

RR에서 트랜잭션을 시작하고 존재하는 PK 행을 FOR UPDATE로 찾았어요. 앞쪽 Gap Lock도 생기나요?

1. `mysql84-isolation-scan-nonmatching`

스캔과 비일치행
인덱스 없는 UPDATE 예제에서 RR은 읽은 행 잠금을 유지한다. RC는 WHERE에 맞지 않는 행의 잠금을 해제한다.

2. `mysql84-autocommit-end-release`

종료와 락해제
COMMIT은 변경을 확정하고 ROLLBACK은 취소한다. 둘 다 현재 트랜잭션에 설정된 InnoDB 락을 해제한다.

3. `mysql84-locking-reads-update`

배타 잠금읽기
FOR UPDATE는 검색한 행·관련 인덱스에 X 락을 설정한다. 경쟁 트랜잭션의 변경·잠금 읽기는 대기할 수 있다.

### Generated answer (not yet human-graded)

아니요. **존재하는 행을 PK로 정확히 단건 조회**하는 경우에는 해당 PK 인덱스 레코드에 X 레코드 락을 잡고, 그 앞쪽 갭에는 Gap Lock을 잡지 않습니다. `FOR UPDATE`는 검색한 행과 관련 인덱스에 배타 잠금을 설정합니다.[mysql84-locking-reads-update]

이는 유일 인덱스의 유일 키를 정확히 찾는 경우에 해당합니다. 범위 검색이나 유일하지 않은 조건은 잠금 범위가 달라질 수 있습니다.

Human review: answer correctness / evidence support / insufficient-information handling.

## insert-shared

INSERT인데 왜 S 잠금이 생겨요? 중복 키 오류나 FK 검사 때문일 수도 있나요?

1. `mysql84-locks-set-insert-duplicate`

INSERT 중복오류
일반 INSERT는 삽입 레코드에 X 락을 설정한다. 중복 키 오류 처리의 S 락은 데드락 원인이 될 수 있다.

2. `mysql84-locks-set-upsert`

UPSERT 잠금
ON DUPLICATE KEY UPDATE의 중복 처리는 X 락이다. PK 중복은 레코드, 유니크 키 중복은 넥스트키 락을 사용한다.

3. `mysql84-consistent-read-embedded-select`

내장SELECT 주의
INSERT…SELECT 등 내장 SELECT는 일반 일관 읽기와 동작이 다를 수 있다. 소스 비잠금 읽기에는 RC·RU 조건을 확인한다.

### Generated answer (not yet human-graded)

네. **일반 `INSERT`는 새 인덱스 레코드에 X 잠금을 설정하지만, 중복 키 오류를 확인하는 과정에서는 기존의 중복 레코드에 S 잠금이 생길 수 있습니다.** 그래서 INSERT 작업 중 S 잠금이 보이는 것이 모순은 아닙니다. 중복 키 처리에서 생긴 S 잠금은 다른 트랜잭션과 데드락을 일으킬 수도 있습니다. [mysql84-locks-set-insert-duplicate]

다만 `INSERT ... ON DUPLICATE KEY UPDATE`는 일반 `INSERT`와 다릅니다. 이 경우 중복 레코드에 X 잠금을 사용하며, PK 중복과 유니크 키 중복의 잠금 범위도 다를 수 있습니다. [mysql84-locks-set-upsert]

**외래 키(FK) 검사 때문일 가능성도 있지만**, 제공된 참고 문서에는 FK 검사 시 어떤 잠금이 잡히는지 설명이 없어 여기서는 단정할 수 없습니다. 참고 문서만으로 확인되는 S 잠금의 원인은 중복 키 오류 처리입니다. [mysql84-locks-set-insert-duplicate]

Human review: answer correctness / evidence support / insufficient-information handling.

## secondary-index

보조 인덱스 조건으로 X 락을 잡으면 PRIMARY 레코드도 잠그나요?

1. `mysql84-locks-set-secondary-clustered`

보조인덱스 잠금
보조 인덱스에서 X 락을 설정하면 대응하는 클러스터 인덱스 레코드도 가져와 잠근다.

2. `mysql84-locking-index-record`

레코드락 대상
레코드 락은 인덱스 레코드를 잠근다. 인덱스가 없어도 숨은 클러스터 인덱스를 사용한다.

3. `mysql84-locking-intention-protocol`

의도 락과 획득 순서
IS·IX는 테이블의 행 잠금 의도다. 행 S 전에는 IS 이상, X 전에는 IX가 필요하다. FOR SHARE는 IS, FOR UPDATE는 IX를 요청한다.

### Generated answer (not yet human-graded)

네. 보조 인덱스 레코드에 X 락을 설정하면, 그 레코드에 대응하는 PRIMARY(클러스터 인덱스) 레코드도 가져와 잠급니다. [mysql84-locks-set-secondary-clustered]

즉, 보조 인덱스를 통해 레코드를 잠그는 작업은 보조 인덱스 레코드만 잠그는 것으로 끝나지 않습니다. 구체적으로 어떤 잠금 범위가 생기는지는 쿼리 조건과 실행 계획에 따라 달라질 수 있습니다.

Human review: answer correctness / evidence support / insufficient-information handling.

## rc-gap

READ COMMITTED로 바꾸면 Gap Lock이 전부 없어지나요?

1. `mysql84-autocommit-end-release`

종료와 락해제
COMMIT은 변경을 확정하고 ROLLBACK은 취소한다. 둘 다 현재 트랜잭션에 설정된 InnoDB 락을 해제한다.

2. `mysql84-locking-rc-gap-exceptions`

RC 갭락예외
READ COMMITTED는 검색용 갭 락을 생략한다. FK·중복 키 검사는 예외다.

3. `mysql84-locking-reads-privileges`

잠금읽기 권한
FOR SHARE에는 SELECT 권한이 필요하다. FOR UPDATE는 SELECT와 DELETE·LOCK TABLES·UPDATE 중 하나의 권한도 필요하다.

### Generated answer (not yet human-graded)

아니요. **READ COMMITTED에서는 검색을 위한 일반적인 Gap Lock은 생략되지만, 전부 없어지는 것은 아닙니다.** 외래 키 제약 조건 검사와 중복 키 검사는 예외적으로 Gap Lock을 사용할 수 있습니다. [mysql84-locking-rc-gap-exceptions]

따라서 “READ COMMITTED면 Gap Lock이 절대 없다”라고 이해하면 안 됩니다.

Human review: answer correctness / evidence support / insufficient-information handling.

## release

COMMIT이나 ROLLBACK을 하면 InnoDB 락은 언제 풀리나요?

1. `mysql84-autocommit-end-release`

종료와 락해제
COMMIT은 변경을 확정하고 ROLLBACK은 취소한다. 둘 다 현재 트랜잭션에 설정된 InnoDB 락을 해제한다.

2. `mysql84-locks-set-lock-tables`

LOCK TABLES 경계
LOCK TABLES에는 MySQL·InnoDB 계층의 락이 관여한다. 호출은 기존 트랜잭션을 암묵적으로 커밋한다.

3. `mysql84-autocommit-disconnect`

접속종료 롤백
autocommit을 끈 세션이 마지막 트랜잭션을 커밋하지 않고 종료되면 MySQL은 해당 트랜잭션을 롤백한다.

### Generated answer (not yet human-graded)

`COMMIT`이나 `ROLLBACK`이 완료되면 현재 트랜잭션이 설정한 InnoDB 락은 해제됩니다. `COMMIT`은 변경 사항을 확정한 뒤, `ROLLBACK`은 변경 사항을 취소한 뒤 락을 풉니다. [mysql84-autocommit-end-release]

Human review: answer correctness / evidence support / insufficient-information handling.

## explicit-with-autocommit

autocommit=1이어도 START TRANSACTION으로 여러 SQL을 묶을 수 있나요?

1. `mysql84-autocommit-explicit-transaction`

명시적 트랜잭션
autocommit=1이어도 START TRANSACTION·BEGIN으로 다중 문장 트랜잭션을 시작하고 COMMIT·ROLLBACK으로 끝낼 수 있다.

2. `mysql84-autocommit-disconnect`

접속종료 롤백
autocommit을 끈 세션이 마지막 트랜잭션을 커밋하지 않고 종료되면 MySQL은 해당 트랜잭션을 롤백한다.

3. `mysql84-autocommit-end-release`

종료와 락해제
COMMIT은 변경을 확정하고 ROLLBACK은 취소한다. 둘 다 현재 트랜잭션에 설정된 InnoDB 락을 해제한다.

### Generated answer (not yet human-graded)

네. `autocommit=1`이어도 `START TRANSACTION`으로 명시적 트랜잭션을 시작하면 여러 SQL 문을 하나의 트랜잭션으로 묶을 수 있습니다. 작업을 마친 뒤 `COMMIT`으로 확정하거나 `ROLLBACK`으로 취소하면 됩니다. [mysql84-autocommit-explicit-transaction]

Human review: answer correctness / evidence support / insufficient-information handling.

## old-value

RR에서 다른 트랜잭션이 수정하고 커밋했는데 왜 일반 SELECT에 예전 값이 보여요?

1. `mysql84-consistent-read-no-data-locks`

일반SELECT 잠금
RR·RC의 일반 SELECT는 일관 읽기이며 InnoDB 데이터 락을 설정하지 않아 다른 세션의 동시 변경을 허용한다.

2. `mysql84-consistent-read-dml-difference`

DML 가시성
RR의 스냅샷 SELECT에 보이지 않는 최근 커밋 행도 UPDATE·DELETE의 대상이 될 수 있다.

3. `mysql84-consistent-read-rr`

RR 읽기시점
RR은 첫 일관 읽기의 스냅샷을 재사용한다. 다른 트랜잭션이 커밋해도 기존 스냅샷은 그대로다.

### Generated answer (not yet human-graded)

REPEATABLE READ(RR)의 일반 `SELECT`는 **일관 읽기(consistent read)**이기 때문입니다. 트랜잭션이 처음 수행한 일관 읽기 시점에 스냅샷을 만들고, 같은 트랜잭션 안에서는 그 스냅샷을 계속 재사용합니다. 따라서 다른 트랜잭션이 값을 수정하고 커밋해도 이미 만들어진 스냅샷에는 반영되지 않아 예전 값이 보일 수 있습니다. [mysql84-consistent-read-rr]

일반 `SELECT`는 그 과정에서 데이터 락을 잡지 않습니다. 최신 커밋 값을 보려면 현재 트랜잭션을 끝낸 뒤 새 트랜잭션에서 조회해야 합니다. [mysql84-consistent-read-no-data-locks]

Human review: answer correctness / evidence support / insufficient-information handling.

## subquery

바깥 SELECT에 FOR UPDATE를 붙이면 서브쿼리의 테이블도 자동으로 잠그나요?

1. `mysql84-locking-reads-subquery`

서브쿼리 범위
바깥 SELECT의 잠금 절은 서브쿼리에 자동 전파되지 않는다. 내부 테이블도 잠그려면 내부 SELECT에도 잠금 절을 지정한다.

2. `mysql84-locking-reads-privileges`

잠금읽기 권한
FOR SHARE에는 SELECT 권한이 필요하다. FOR UPDATE는 SELECT와 DELETE·LOCK TABLES·UPDATE 중 하나의 권한도 필요하다.

3. `mysql84-locking-auto-inc`

자동증가 잠금
AUTO-INC는 자동 증가 할당용 테이블 락이다. 사용 방식은 innodb_autoinc_lock_mode에 따른다.

### Generated answer (not yet human-graded)

아니요. 바깥 `SELECT`의 `FOR UPDATE`는 서브쿼리 안의 테이블에 자동으로 적용되지 않습니다. 서브쿼리의 테이블도 잠그려면 내부 `SELECT`에도 별도로 `FOR UPDATE`를 지정해야 합니다. [mysql84-locking-reads-subquery]

Human review: answer correctness / evidence support / insufficient-information handling.

## skip-vs-nowait

행 락을 기다리지 않으려면 NOWAIT와 SKIP LOCKED 중 무엇을 써야 하나요? 오류와 제외의 차이가 있나요?

1. `mysql84-locking-reads-nowait`

NOWAIT 동작
NOWAIT는 행 락을 즉시 얻지 못하면 대기 없이 오류를 반환한다. 테이블 수준 대기까지 제거하지는 않는다.

2. `mysql84-locking-reads-skip-locked`

SKIP LOCKED 동작
SKIP LOCKED는 잠긴 행을 결과에서 제외한다. 행 락에 적용되며 불완전한 결과를 허용하는 큐 처리에 적합하다.

3. `mysql84-locking-reads-replication`

복제 주의
NOWAIT·SKIP LOCKED를 사용하는 문장은 문장 기반 복제에 안전하지 않다.

### Generated answer (not yet human-graded)

둘 다 행 락을 기다리지 않게 하지만, 처리 방식이 다릅니다.

- **`NOWAIT`**: 필요한 행 락을 즉시 얻을 수 없으면 대기하지 않고 **오류를 반환**합니다. 잠긴 행을 건너뛰지 않습니다. [mysql84-locking-reads-nowait]
- **`SKIP LOCKED`**: 이미 잠긴 행은 대기하지 않고 **조회 결과에서 제외**합니다. 따라서 결과가 불완전할 수 있지만, 잠금 대기 없이 처리할 작업을 가져오는 큐 등에 적합합니다. [mysql84-locking-reads-skip-locked]

즉, 잠금 충돌을 오류로 처리하려면 `NOWAIT`, 잠긴 행을 건너뛰고 나머지를 처리하려면 `SKIP LOCKED`를 사용하세요. 두 옵션 모두 테이블 수준 대기까지 없애는 것은 아니며, 이를 사용하는 문장은 문장 기반 복제에 안전하지 않습니다. [mysql84-locking-reads-nowait] [mysql84-locking-reads-replication]

Human review: answer correctness / evidence support / insufficient-information handling.

## phantom

조회한 행만 잠그면 되는데 왜 사이 구간과 마지막 레코드 뒤까지 막아요?

1. `mysql84-isolation-scan-nonmatching`

스캔과 비일치행
인덱스 없는 UPDATE 예제에서 RR은 읽은 행 잠금을 유지한다. RC는 WHERE에 맞지 않는 행의 잠금을 해제한다.

2. `mysql84-locks-set-insert-select`

INSERT…SELECT 소스
INSERT…SELECT의 대상 행은 X 레코드 락이다. 소스는 RC에서 일관 읽기, RR에서는 S 넥스트키 락을 사용한다. CREATE…SELECT도 같은 원칙을 따른다.

3. `mysql84-locking-reads-share`

공유 잠금읽기
FOR SHARE는 읽은 행에 S 락을 건다. 다른 세션의 일반 읽기는 허용하되 해당 행 변경은 트랜잭션 종료까지 제한한다.

### Generated answer (not yet human-graded)

사이 구간을 잠그는 이유는 **조회 결과에 없던 행이 나중에 끼어드는 것(팬텀)을 막기 위해서**입니다. REPEATABLE READ에서 범위 조건으로 잠금 읽기나 변경을 하면, InnoDB는 검색된 레코드뿐 아니라 인덱스 레코드 사이의 간격도 잠글 수 있습니다. 마지막 인덱스 레코드 뒤의 간격도 포함되므로 그 범위에 새 레코드를 삽입하는 작업이 막힐 수 있습니다.

다만 모든 조회가 구간을 잠그는 것은 아닙니다. 배경 문제처럼 **유니크 인덱스에서 존재하는 키 하나를 정확히 찾는 조회**라면 해당 레코드만 잠그고 앞뒤 갭은 잠그지 않습니다. 제공된 참고 자료에는 갭 락과 넥스트키 락 설명이 없어, 이 구분을 뒷받침할 자료 근거는 포함되어 있지 않습니다.

Human review: answer correctness / evidence support / insufficient-information handling.

## serializable

SERIALIZABLE이면 autocommit과 무관하게 모든 일반 SELECT가 FOR SHARE가 되나요?

1. `mysql84-isolation-serializable-autocommit`

SERIALIZABLE 조건
SERIALIZABLE에서 autocommit이 꺼지면 일반 SELECT가 FOR SHARE로 바뀐다. 자동 커밋의 독립 SELECT는 비잠금 일관 읽기가 가능하다.

2. `mysql84-locking-reads-privileges`

잠금읽기 권한
FOR SHARE에는 SELECT 권한이 필요하다. FOR UPDATE는 SELECT와 DELETE·LOCK TABLES·UPDATE 중 하나의 권한도 필요하다.

3. `mysql84-isolation-default`

격리수준 선택
InnoDB의 기본은 RR이며 RC·RU·SERIALIZABLE도 지원한다. 격리 수준은 세션 또는 기본 설정에서 변경한다.

### Generated answer (not yet human-graded)

아니요. **SERIALIZABLE이라고 해서 autocommit과 무관하게 모든 일반 `SELECT`가 `FOR SHARE`로 바뀌는 것은 아닙니다.**

InnoDB에서는 `autocommit`이 꺼져 있을 때 일반 `SELECT`가 `FOR SHARE`와 같은 방식으로 동작합니다. 반면 `autocommit`이 켜진 상태의 독립적인 `SELECT`는 비잠금 일관 읽기가 가능하므로, `FOR SHARE`처럼 잠금 읽기로 바뀌지 않습니다.[mysql84-isolation-serializable-autocommit]

Human review: answer correctness / evidence support / insufficient-information handling.

## auto-inc-lifetime

AUTO-INC 테이블 락도 무조건 트랜잭션 COMMIT까지 유지되나요?

1. `mysql84-autocommit-end-release`

종료와 락해제
COMMIT은 변경을 확정하고 ROLLBACK은 취소한다. 둘 다 현재 트랜잭션에 설정된 InnoDB 락을 해제한다.

2. `mysql84-autocommit-disconnect`

접속종료 롤백
autocommit을 끈 세션이 마지막 트랜잭션을 커밋하지 않고 종료되면 MySQL은 해당 트랜잭션을 롤백한다.

3. `mysql84-locking-auto-inc`

자동증가 잠금
AUTO-INC는 자동 증가 할당용 테이블 락이다. 사용 방식은 innodb_autoinc_lock_mode에 따른다.

### Generated answer (not yet human-graded)

아니요. AUTO-INC 테이블 락은 **항상 트랜잭션 COMMIT까지 유지되는 것은 아닙니다.** 유지 시간은 `innodb_autoinc_lock_mode`와 삽입 방식에 따라 달라집니다.[mysql84-locking-auto-inc]

- `innodb_autoinc_lock_mode=0`(전통적 모드): 삽입문에서 사용한 AUTO-INC 락을 **문장 종료 시** 해제합니다.
- `innodb_autoinc_lock_mode=1`(연속 모드): 삽입문 종류에 따라 문장 종료까지 유지할 수 있습니다. 다만 미리 필요한 증가값을 계산할 수 있는 단순 삽입은 더 가벼운 뮤텍스 방식으로 처리합니다.
- `innodb_autoinc_lock_mode=2`(인터리브 모드): AUTO-INC 테이블 락을 사용하지 않습니다.

따라서 “AUTO-INC 테이블 락”이라는 이름만으로 COMMIT까지 유지된다고 볼 수 없습니다. 이는 일반적인 트랜잭션 락과 달리, 모드와 삽입문에 따라 문장 단위로 해제될 수 있습니다.

Human review: answer correctness / evidence support / insufficient-information handling.

## gap-vs-record

gap lock과 record lock의 구별 기준은 뭐에요?

1. `mysql84-locks-set-lock-tables`

LOCK TABLES 경계
LOCK TABLES에는 MySQL·InnoDB 계층의 락이 관여한다. 호출은 기존 트랜잭션을 암묵적으로 커밋한다.

2. `mysql84-autocommit-end-release`

종료와 락해제
COMMIT은 변경을 확정하고 ROLLBACK은 취소한다. 둘 다 현재 트랜잭션에 설정된 InnoDB 락을 해제한다.

3. `mysql84-consistent-read-no-data-locks`

일반SELECT 잠금
RR·RC의 일반 SELECT는 일관 읽기이며 InnoDB 데이터 락을 설정하지 않아 다른 세션의 동시 변경을 허용한다.

### Generated answer (not yet human-graded)

구별 기준은 **인덱스에서 잠그는 대상**입니다.

- **Record lock(레코드 락)**: 인덱스의 **기존 레코드**를 잠급니다.
- **Gap lock(갭 락)**: 인덱스 레코드 사이의 **범위(갭)**를 잠가, 그 범위에 새 인덱스 레코드가 삽입되는 것을 막습니다.

둘 다 인덱스 락이며, 갭 락은 기존 레코드 자체를 잠그는 락이 아닙니다. [mysql84-locks-set-lock-tables]

참고로 앞 문제의 조건처럼 **기존 기본 키 레코드를 유니크 조건으로 한 건 조회**하는 경우에는 보통 해당 레코드의 레코드 락이 대상이고, 앞쪽 갭까지 잠글 필요는 없습니다.

Human review: answer correctness / evidence support / insufficient-information handling.
