# Client SDK 남은 의사결정

- 작성: 2026-10-22, HuitaePark
- 관련: [cloud-api#12](https://github.com/tokenpliot/token-pilot-cloud-api/issues/12), [cloud-api#18](https://github.com/tokenpliot/token-pilot-cloud-api/issues/18), PR [#1](https://github.com/tokenpliot/token-pilot-client/pull/1), [#2](https://github.com/tokenpliot/token-pilot-client/pull/2)

SDK 구현(#12, #18) 중에 코드만으로 정할 수 없어서 남겨둔 결정들이다. 지금 코드는 각 항목의 "현재 동작"대로 움직이며, 결정이 나면 이 문서의 상태를 바꾸고 결과를 적는다.

## 한눈에 보기

| # | 결정 | 위험 | 기한 | 결정할 사람 | 상태 |
| --- | --- | --- | --- | --- | --- |
| D1 | `ChatClient.stream()` 호출을 Enforce에서 어떻게 다룰지 | **높음**: 지금은 판정 없이 통과 | Enforce 파일럿을 켜기 전 (11/09) | HuitaePark | 열림 |
| D2 | Observe v2 계약에 metadata(`trace_id` 등)를 넣을지 | 중간: v2에서 trace 연결 불가 | #13 E2E·runbook 전 | chokyungjin0504, HuitaePark | 열림 |
| D3 | 가격표 없는 모델 호출을 Token Pilot이 SDK로 넘기게 할지 | 중간: 해당 호출이 Control Plane에 안 남음 | Observe 파일럿 전 | HuitaePark | 열림 |
| D4 | SDK 기본 전송 경로를 `OBSERVE_V2`로 바꿀 시점 | 낮음 | 서버가 운영에서 v2를 켤 때 | chokyungjin0504, HuitaePark | 열림 |
| D5 | `REQUIRE_APPROVAL` 승인 조회·재실행을 SDK가 맡을지 | 낮음 | 승인 정책을 파일럿에 넣을 때 | 팀 | 열림 |

## D1. 스트리밍 호출 집행

**현재 동작**: 집행 advisor는 `CallAdvisor`만 구현한다. `ChatClient.stream()` 호출에는 붙지 않아서, `ENFORCE`를 켠 앱도 스트리밍 호출은 판정 없이 provider로 간다. 고객은 막힌다고 생각하지만 실제로는 안 막히는 상태라 가장 위험하다.

| 선택지 | 내용 | 비용 |
| --- | --- | --- |
| A. 현행 유지 | README 한계로만 안내 | 없음. 대신 고객 오해 위험이 그대로 |
| B. 판정 후 스트리밍 | `StreamAdvisor`로 스트림 시작 전에 판정하고, 스트림이 끝나면 집계된 사용량으로 정산 | 큼. 중간 취소·부분 응답·사용량 누락을 모두 정산 규칙에 넣어야 함 |
| C. 집행 범위에서 스트리밍 거부 | `ENFORCE` 범위의 스트리밍 호출은 `EnforcementException`으로 막고, `OBSERVE`/`ADVISORY`는 그대로 통과 | 작음. Token Pilot 예산 기능도 스트리밍을 fail-closed로 막는 같은 방식 |

**권장: C.** 파일럿 범위가 작아서 스트리밍을 집행할 필요가 생기면 그때 B로 간다. 막힌다는 사실이 예외로 드러나서 조용히 통과하는 것보다 안전하다.

## D2. Observe v2의 metadata

**현재 동작**: `POST /api/v2/observe/events` 계약(`ObserveEventRequest`)에 metadata 필드가 없다. 그래서 `OBSERVE_V2`로 보내면 `trace_id`, `app_request_id`, `forwarded-tags`가 서버에 가지 않는다. 기존 배치 경로(`INGESTION_BATCH`)는 보낸다.

| 선택지 | 내용 |
| --- | --- |
| A. v2에 metadata 추가 | v1과 같은 규칙(16개, 값 256자, 금지 키 제거)으로 받고 저장. SDK는 이미 가진 metadata를 그대로 보내면 됨 |
| B. 현행 유지 | trace 연결이 필요하면 배치 경로를 쓰게 안내 |

**권장: A.** #12의 요구사항에 trace correlation이 있고, #13 장애 주입·runbook에서 호출을 추적하려면 필요하다. 서버 변경이 먼저고, SDK 변경은 인코더에 필드 하나를 더하는 수준이다.

## D3. 가격표 없는 모델 호출

**현재 동작**: Token Pilot의 Spring AI advisor는 가격 스냅샷이 없거나 요청·응답 모델이 다른 호출을 원장에 기록하지 않는다(`RECONCILED`일 때만 `LedgerListener` 호출). 그래서 이 SDK도 그 호출을 받지 못하고 Control Plane에 아무것도 남지 않는다. 비용을 모르는 호출일수록 Control Plane이 알아야 하는데 반대로 빠진다.

함께 볼 것: Token Pilot 예산 기능(`token-pilot.budget.enabled=true`)을 켜면 advisor가 `LedgerListener` 대신 예약 정산 경로를 써서 SDK의 원장 전달이 통째로 멈춘다.

| 선택지 | 내용 |
| --- | --- |
| A. tokenpilot 변경 | `UNPRICED`·`RECONCILIATION_REQUIRED` 호출도 비용 없이 `LedgerListener`로 알림. 서버 v2가 가격을 다시 계산하므로 SDK는 토큰만 보내면 됨 |
| B. SDK 자체 수집 | Token Pilot 원장과 별개로 SDK advisor가 사용량을 직접 수집 |
| C. 현행 유지 | 가격표에 모든 모델을 등록하도록 안내 |

**권장: A.** 사용량 추출을 두 군데서 하면(B) 같은 호출이 두 번 집계될 위험이 생긴다. 예산 기능과의 충돌도 같은 변경에서 함께 다룬다.

## D4. 기본 전송 경로 전환

**현재 동작**: 기본은 `INGESTION_BATCH`(기존 사용량 테이블)다. 서버 v2가 기본으로 꺼져 있어서 기본값을 바꾸면 v2를 켜지 않은 서버에서 모든 이벤트가 404로 버려진다.

**결정할 것**: 서버가 운영에서 v2를 켜는 시점에 SDK 기본값을 `OBSERVE_V2`로 바꿀지, 그리고 배치 경로를 언제까지 유지할지. 기존 대시보드·예산 집계가 배치 경로 행만 보므로 서버 집계 전환 계획과 같이 정해야 한다.

## D5. 승인 대기 처리

**현재 동작**: `REQUIRE_APPROVAL`을 집행 범위에 넣으면 `approvalId`를 담은 `EnforcementException`으로 호출을 막는 데까지만 한다. 승인 상태 조회(`GET /api/control/v1/approvals/{id}`)와 승인 후 재실행은 앱이 같은 `requestId`로 다시 호출해 처리한다.

**결정할 것**: 파일럿에 승인 정책을 넣을지. 넣는다면 SDK가 조회 API와 대기·재시도 헬퍼를 제공할지.

## 서버 구현(#15, #17)과 맞출 것

결정은 아니지만 서버가 나오면 바로 확인해야 하는 계약 지점이다. SDK는 `docs/api/control-plane-v1.yaml`을 기준으로 만들었다.

- **`Idempotency-Key` 형식**: 판정은 `dec:<requestId>`, 정산은 `stl:<reservationId>:<COMMIT|RELEASE|RECONCILIATION_REQUIRED>`. 8자 미만이나 128자 초과면 `dec:<sha256>` 형태로 바꾼다. 서버가 키 형식에 다른 가정을 하지 않는지 확인한다.
- **정산 본문의 `occurredAt`**: 정산을 예약할 때 한 번 정하고 재시도에는 같은 본문을 쓴다. 다른 시점에 같은 결과를 다시 보내면 본문이 달라 409가 된다. 서버의 "종료 상태에 같은 정산 → 원래 결과" 규칙이 본문 비교인지 결과 비교인지 맞춘다.
- **COMMIT 비용 분할**: 계약이 prompt·completion 비용을 따로 요구해서, SDK는 입력 토큰만으로 한 번 더 계산해 나눈다. 두 값의 합은 총비용과 같다(소수 6자리 올림). 서버가 합계 일치를 검증한다면 이 규칙과 같아야 한다.
- **API가 없을 때**: 지금 서버로 판정을 요청하면 404가 와서 `INDETERMINATE`가 된다. `on-indeterminate: fail-closed`를 고른 고객은 서버 배포 전까지 모든 호출이 막힌다.
- **판정 응답의 `requestId` 불일치**: SDK는 요청과 다른 `requestId`를 담은 200 응답을 `INDETERMINATE`(`UNREADABLE_RESPONSE`)로 본다.
