# LockLab RAG 기초 평가 — 2026-10-18

## 이번에 실제로 한 일

- 실행 중인 `http://127.0.0.1:8080/ask/1`에 기존 질문 15개를 한 번씩 순차 전송했다.
- 각 요청에는 `question`만 전달했다. 앱은 문제 1의 배경 정보를 프롬프트에 포함한다. 다른 문제·대화 이력을 사용한 평가는 아니다.
- 실제 검색 문서와 실제 생성 답변을 `results.json`에 저장했다. 모델·문서·앱의 검색 방식은 변경하지 않았다.
- 별도로 근거 부족 상황을 확인하는 질문 2개를 전송해 `boundary-results.jsonl`에 저장했다. 총 실제 생성 요청은 17회다.
- Podman 접근 제한으로 임베딩·검색만 분리한 실행은 하지 못했다. 앱 API를 통한 전체 경로를 평가했다.
- 반환된 모든 문서 payload는 로컬 `embedding/chunks.jsonl`의 동일 ID 본문·출처·버전과 일치했다. 전체 실행 중 컬렉션 58개를 직접 조회한 것은 아니다.

## 기존 근거 목록으로 계산한 검색 지표

| 항목 | 실제 결과 |
|---|---:|
| 요청 성공 | 15/15 |
| Hit@3: 표시된 관련 근거가 하나 이상 검색됨 | 11/15 = 73.3% |
| 평균 Recall@3: 표시된 관련 근거 중 검색된 비율 | 53.3% |
| MRR@3: 첫 관련 근거 순위의 역수 평균 | 0.6556 |
| 전체 API 응답 시간 중앙값 | 3.327초 |
| 전체 API 응답 시간 평균 | 3.542초 |
| 전체 API 응답 시간 최소 / 최대 | 1.857초 / 6.850초 |

이 값들은 생성 모델까지 포함한 순차 요청 한 번의 측정이다. 벡터 검색만의 시간, p95 부하 성능, 운영 SLA, 답변 정확도가 아니다.

기존 `relevant_chunk_ids`에는 직접 답하는 근거, 보완 근거, 유사한 대체 근거가 섞여 있다. 따라서 Recall@3가 낮아도 답변에 충분한 근거를 찾았을 수 있고, Hit@3가 1이어도 핵심 근거가 없을 수 있다. 예를 들어 AUTO-INC 문항은 일반 COMMIT/ROLLBACK 문서를 찾았다는 이유로 Hit@3=1이지만, 문장 종료 시 해제된다는 핵심 문서는 검색되지 않았다. 기존 지표를 고쳐서 높이지 않고 이 한계를 함께 기록한다.

## 답변·근거에 대한 AI의 1차 검토

아래 판정은 검색 결과와 답변을 읽고 MySQL 8.4 공식 문서와 대조한 AI의 정성 검토다. 독립적인 사람 평가나 확정된 정답률이 아니다. 제출 전 본인이 검토해야 한다.

| 질문 ID | 핵심 답변 | 검색 근거 상태 | 확인한 내용 |
|---|---|---|---|
| intent-vs-x | 대체로 타당 | 충분 | IX의 테이블 의도·행 X와의 관계, 호환성 설명. 다만 첫 문장에서 X를 행 잠금으로만 설명한 표현은 범위를 명시하는 편이 정확하다. |
| gap-coexist | 핵심 규칙은 타당 | 부족·잘못된 인용 | Gap Lock의 공존 규칙을 못 찾았다. INSERT 의도 락 관련 설명에 인용한 LOCK TABLES 문서는 해당 주장을 뒷받침하지 않는다. |
| existing-pk | 핵심 결론 타당 | 부족 | 존재하는 유일 PK를 정확히 조회할 때 앞쪽 갭을 잠그지 않는다는 직접 근거가 없다. 일반 FOR UPDATE 문서만 인용했다. |
| insert-shared | 일부만 답함 | 일부 누락 | 중복 키 S 락 근거는 찾았으나 FK 검사 S 락 근거는 놓쳤다. 모델은 FK 부분을 확인할 수 없다고 밝혔다. |
| secondary-index | 핵심 결론 타당 | 충분 | 보조 인덱스 X 락과 클러스터 인덱스 잠금 관계를 직접 근거로 설명했다. |
| rc-gap | 핵심 결론 타당 | 충분 | RC에서도 FK·중복 키 검사 예외가 있음을 설명했다. |
| release | 핵심 결론 타당 | 충분 | 전체 COMMIT/ROLLBACK으로 트랜잭션의 InnoDB 락이 해제됨을 설명했다. 모든 종류의 락이 반드시 그때까지 유지된다는 뜻은 아니다. |
| explicit-with-autocommit | 핵심 결론 타당 | 충분 | autocommit=1에서도 명시적 다중 문장 트랜잭션을 사용할 수 있음을 설명했다. |
| old-value | 핵심 결론 타당 | 충분 | RR 첫 일관 읽기 스냅샷을 재사용하는 이유를 설명했다. |
| subquery | 핵심 결론 타당 | 충분 | 바깥 잠금 절이 내부 서브쿼리에 자동 전파되지 않음을 설명했다. |
| skip-vs-nowait | 핵심 결론 타당 | 충분 | 오류 반환과 잠긴 행 제외를 구분했다. |
| phantom | 방향은 타당·조건 설명 주의 | 부족 | 팬텀 방지와 갭을 설명했지만 직접 근거가 없다. 모든 범위 검색이 최대 레코드 뒤까지 잠근다는 식의 일반화는 피해야 한다. |
| serializable | 핵심 결론 타당 | 충분 | autocommit이 꺼진 경우와 자동 커밋의 독립 SELECT를 구분했다. |
| auto-inc-lifetime | 핵심 결론 타당 | 핵심 근거 부족 | 문장 종료 시 해제·모드별 동작을 말했지만, 검색한 AUTO-INC 요약에는 그 상세 내용이 없다. 직접적인 수명 문서를 놓쳤다. |
| gap-vs-record | 핵심 정의 타당 | 잘못된 인용 | 레코드·갭의 정의를 설명하면서 두 정의가 없는 LOCK TABLES 문서를 인용했다. |

정성 검토상 핵심 설명을 충분히 뒷받침한 문항 9개, 부분 근거만 있는 문항 1개, 핵심 근거가 부족하거나 잘못 인용된 문항 5개로 분류했다. 이는 AI가 이번 출력에 적용한 탐색적 분류이며 일반적인 서비스 정확도를 뜻하지 않는다.

### 실제 실패 예시

질문: `gap lock과 record lock의 구별 기준은 뭐에요?`

- 필요한 근거: `mysql84-locking-index-record`, `mysql84-locking-gap-coexistence`.
- 실제 검색: `mysql84-locks-set-lock-tables`, `mysql84-autocommit-end-release`, `mysql84-consistent-read-no-data-locks`.
- 실제 답변은 레코드와 간격의 차이를 설명했지만, 근거로 `mysql84-locks-set-lock-tables`를 표시했다.
- 그 문서는 LOCK TABLES의 계층과 암묵적 COMMIT에 관한 내용으로, 해당 정의를 뒷받침하지 않는다.
- 따라서 인용 ID가 실제 존재한다는 검사만으로 인용 정확성을 검증할 수 없다.

## 추가 경계 질문 2개

1. **현재 서버 설정값을 묻는 질문**: 현재 값을 확인할 정보가 없다고 밝혀 단정을 피했다. 다만 MySQL 기본값을 덧붙이며 그 값이 없는 요약 조각을 인용해, 부가 설명의 근거 연결은 부족했다.
2. **PostgreSQL SIReadLock 질문**: 검색 문서에 근거가 없다고 마지막에 밝혔지만, 그 전에 PostgreSQL 상세 동작을 설명했다. 현재의 '제공 근거로 확인할 수 없는 내용은 확인할 수 없다고 한다'는 규칙을 엄격하게 적용한 답변은 아니다. 설명 자체의 PostgreSQL 사실 정확도는 이번 평가 범위에서 채점하지 않았다.

범위 밖 지식도 허용할지, 제공 근거로 확인되는 내용만 답할지 먼저 제품 정책을 정해야 한다. 후자를 선택한다면 현재 프롬프트 문구만으로는 일관된 제한이 되지 않았다.

## 다음에 시도할 수 있는 개선 — 아직 적용하지 않음

1. 질문별 핵심 근거와 보완·대체 근거를 구분해 평가 목록을 정리한다. 기존 15개는 개발용 목록으로 유지하고, 평가 결과를 본 뒤 만든 개선은 새 질문에서도 검증한다.
2. 핵심 근거가 없는 네 검색 실패 문항부터 원인을 살핀다. 문서 자체는 존재하므로 '자료 부족'과 '검색 실패'를 구분한다.
3. 표기 차이(Gap Lock/갭 락 등), 짧은 요약의 정보량, 검색 쿼리의 영향은 원인 후보일 뿐 아직 검증된 원인이 아니다.
4. 검색 후보 수, 용어 보완, 키워드 검색 혼합 등을 하나씩 비교한다. top-k를 키우면 근거 포함률이 올라가도 토큰 비용과 무관한 문서가 늘 수 있으므로 같은 지표로 단순 비교하지 않는다.
5. 근거 내용이 실제 주장을 뒷받침하는지 점검한다. 인용 ID의 존재 검사와 답변의 사실성·근거성 평가는 별개다.

## 포트폴리오에 현재 쓸 수 있는 범위

> MySQL 8.4 문서 요약 58개를 사용하는 RAG에 대해 기존 질문 15개로 실제 API 평가를 수행했다. 기존 관련 근거 목록 기준 Hit@3는 73.3%였으며, 관련 근거 검색 실패와 문서 내용에 맞지 않는 인용 사례를 확인했다. 추가 2개 질문으로 근거 부족 상황의 응답도 점검했다.

아직 검색 최적화나 개선 효과를 검증한 것은 아니다. 개선 수치, 높은 답변 정확도, 대규모 운영 성능으로 표현하지 않는다. 이번 평가 코드 실행·결과 정리는 AI의 지원을 받았으며, 본인 기여는 평가 기준·실패 원인·개선 선택을 직접 이해하고 검토한 범위로 설명한다.

## 대조한 공식 자료

- [InnoDB Locking](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html): 의도 락·레코드·갭·넥스트키 개념.
- [Locks Set by Different SQL Statements](https://dev.mysql.com/doc/refman/8.4/en/innodb-locks-set.html): 보조 인덱스·유일 검색·INSERT·FK·AUTO-INC 수명.
- [Transaction Isolation Levels](https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html): RR·RC·SERIALIZABLE 조건.
- [Locking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking-reads.html): 서브쿼리와 NOWAIT/SKIP LOCKED.
- [autocommit, Commit, and Rollback](https://dev.mysql.com/doc/refman/8.4/en/innodb-autocommit-commit-rollback.html): 명시적 트랜잭션과 해제.
- [Consistent Nonlocking Reads](https://dev.mysql.com/doc/refman/8.4/en/innodb-consistent-read.html): RR 스냅샷.
- [Phantom Rows](https://dev.mysql.com/doc/refman/8.4/en/innodb-next-key-locking.html): 범위 삽입 방지.
