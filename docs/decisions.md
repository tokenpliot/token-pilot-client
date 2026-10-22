# Client SDK 의사결정 기록

- 작성: 2026-10-22, HuitaePark
- 관련: [cloud-api#12](https://github.com/tokenpliot/token-pilot-cloud-api/issues/12), [cloud-api#18](https://github.com/tokenpliot/token-pilot-cloud-api/issues/18), PR [#1](https://github.com/tokenpliot/token-pilot-client/pull/1), [#2](https://github.com/tokenpliot/token-pilot-client/pull/2)

SDK 구현(#12, #18) 중에 코드만으로 정할 수 없었던 결정들과 그 결과다. 결정을 바꾸려면 이 문서를 고치고 같은 PR에서 코드를 바꾼다.

## 한눈에 보기

| # | 결정 | 결과 | 구현 |
| --- | --- | --- | --- |
| D1 | `ChatClient.stream()` 호출의 Enforce 처리 | **ENFORCE 범위에서는 거부**, 나머지는 판정 없이 통과 | PR #2 |
| D2 | Observe v2 계약에 metadata 추가 | **추가**. v1과 같은 규칙 | SDK: PR #1, 서버: cloud-api PR (아래) |
| D3 | 가격표 없는 모델 호출 전달 | **SDK advisor가 전달**(tokenpilot 변경 없음). Token Pilot 예산 기능과는 함께 쓰지 않음 | PR #1 |
| D4 | 기본 전송 경로를 `OBSERVE_V2`로 바꿀 시점 | **서버 운영에서 v2를 켜는 배포와 같이** 바꿈. 그 전까지 `INGESTION_BATCH` 유지 | 지금은 변경 없음 |
| D5 | `REQUIRE_APPROVAL` 승인 조회·재실행 | **파일럿 범위 밖**. 차단 + `approvalId` 전달까지만 | 지금은 변경 없음 |

## D1. 스트리밍 호출은 ENFORCE 범위에서 거부

**문제**: 집행 advisor가 `CallAdvisor`만 구현해서 `ENFORCE`를 켠 앱도 스트리밍 호출은 판정 없이 provider로 갔다. 고객은 막힌다고 믿는데 실제로는 안 막히는 상태였다.

**선택지**: A 현행 유지(문서로만 안내) / B 스트림 시작 전 판정 + 끝난 뒤 집계 사용량으로 정산 / C ENFORCE 범위의 스트리밍 거부

**결정: C.**
- 스트림은 중간 취소, 부분 응답, 사용량 누락 등 끝나는 방식이 많다. 이걸 예약 정산 규칙에 모두 넣는 B는 파일럿 범위(한 프로젝트·환경)에 비해 비용이 크다.
- 조용히 통과(A)하는 것보다 예외로 드러나는 편이 안전하다. Token Pilot 예산 기능도 스트리밍을 fail-closed로 막는다.

**동작**:
- `ENFORCE` 범위의 스트림은 provider를 부르기 전에 `EnforcementException`으로 실패한다. 판정은 클라이언트가 만든 `INDETERMINATE`(`errorCode = STREAMING_NOT_ENFORCEABLE`)이며 정책 거부(`DENY_*`)가 아니다. Control Plane에는 요청하지 않는다.
- `OBSERVE`·`ADVISORY` 범위의 스트림은 판정 없이 그대로 진행된다. 두 모드 모두 호출을 막지 않으므로 판정을 기다릴 이유가 없다.
- 스트리밍을 집행해야 하는 고객이 생기면 B를 다시 검토한다.

## D2. Observe v2가 metadata를 받는다

**문제**: `POST /api/v2/observe/events`에 metadata 필드가 없어서 `OBSERVE_V2` 경로에서는 `trace_id`, `app_request_id`, 전달 태그가 서버에 가지 않았다. #12의 trace correlation 요구와 #13 장애 추적에 필요하다.

**결정: v2에 metadata를 v1과 같은 규칙으로 추가한다.** 최대 16개, 값 256자, 원문 키(`prompt`, `completion`, `messages` 등) 제거, 엄격 모드에서는 400.

**동작**:
- SDK(PR #1): `OBSERVE_V2`도 metadata를 보낸다. 서버는 알 수 없는 필드를 무시하므로, 서버 변경 전에도 요청이 거부되지 않는다.
- 서버(cloud-api PR): v2 요청이 metadata를 받아 `usage_events.metadata_json`에 저장하고, 재전송 판별(fingerprint)에 포함한다. 같은 `eventId`에 metadata만 다르면 409다. metadata가 없는 요청의 fingerprint는 기존과 같아서, 이미 저장된 이벤트의 재전송은 계속 중복으로 처리된다.

## D3. 원장에 안 남는 호출은 SDK advisor가 전달

**문제**: Token Pilot의 `LedgerAdvisor`는 가격이 `RECONCILED`일 때만 원장에 기록한다. 가격표 없는 모델(`UNPRICED`)이나 응답 모델이 다른 호출(`RECONCILIATION_REQUIRED`)은 `LedgerListener`를 거치지 않아 Control Plane에 전혀 남지 않았다. 비용을 모르는 호출일수록 Control Plane이 알아야 하는데 반대로 빠지고 있었다.

**선택지**: A tokenpilot이 그 호출도 리스너로 알리도록 변경 / B SDK가 직접 수집 / C 가격표 등록 안내

**결정: B의 좁은 형태.** 처음 권장은 A였지만 코드를 확인해 보니 tokenpilot을 바꿀 필요가 없었다. `LedgerAdvisor`가 응답 context(`tokenpilot.pricing.reconciliation.result`)에 판정 결과를 남기므로, SDK advisor가 그 값을 읽으면 된다.
- `UnreconciledUsageAdvisor`가 `LedgerAdvisor` 바로 바깥에서 결과를 읽는다. `UNPRICED`·`RECONCILIATION_REQUIRED`이면 토큰을 비용 없이 보내고 metadata `pricing_reconciliation`을 단다.
- `RECONCILED`는 `LedgerListener` 경로로만 보낸다. 두 조건이 겹치지 않으므로 같은 호출이 두 번 가지 않는다.
- 모델은 provider 응답의 모델을 우선 쓴다. 서버 v2가 그 모델로 가격을 다시 계산한다.

B의 원래 걱정(이중 집계)은 조건이 겹치지 않아서 생기지 않는다. 다른 저장소를 바꾸지 않으니 배포 순서 문제도 없다.

**함께 정한 것: Token Pilot 예산 기능(`token-pilot.budget.enabled=true`)과 이 SDK의 사용량 전달은 함께 쓰지 않는다.** 예산 기능을 켜면 advisor가 예약 정산 경로를 써서 `LedgerListener`도 응답 context 결과도 남지 않는다. 예산 집행이 필요하면 Token Pilot 로컬 예산 대신 Control Plane Enforce를 쓴다. 로컬 예산과 중앙 집행을 동시에 돌리면 같은 호출에 예약이 두 번 걸리므로, 둘 중 하나만 쓰는 쪽이 맞다.

**한계**: 배치 경로(`INGESTION_BATCH`)에는 가격 미상을 표현할 방법이 없어서 이 호출들이 비용 0으로 저장된다. 가격 미상을 구분하려면 `OBSERVE_V2`를 쓴다(D4).

## D4. 기본 전송 경로 전환은 서버 운영 v2 활성화와 같이

**결정**: 서버가 운영에서 `token-pilot.observe.ingestion.enabled=true`로 배포하는 시점에 SDK 기본값을 `OBSERVE_V2`로 바꾼다. 그 전까지는 `INGESTION_BATCH`를 유지한다.

**이유**: v2가 꺼진 서버에 v2로 보내면 모든 이벤트가 404로 버려진다. 기본값만 먼저 바꾸면 SDK를 올린 고객의 사용량이 통째로 사라진다.

**배치 경로 제거**: 기존 대시보드·예산 집계가 배치 경로 행만 본다. 그래서 서버 집계를 원장으로 옮긴 뒤에 별도로 정한다.

## D5. 승인 대기는 파일럿 범위 밖

**결정**: `REQUIRE_APPROVAL`을 집행 범위에 넣으면 `approvalId`를 담은 `EnforcementException`으로 막는 데까지만 한다. SDK는 승인 조회(`GET /api/control/v1/approvals/{id}`)나 대기·재시도 헬퍼를 제공하지 않는다.

**이유**:
- 기본 집행 범위(`DENY_POLICY`, `DENY_BUDGET`)에는 승인이 없다.
- 승인 흐름에는 사람의 응답 시간(분~시간)이 끼어서 provider 호출 경로에서 기다리는 형태가 맞지 않는다.
- 필요해지면 앱이 같은 `requestId`로 다시 호출하는 방식을 기준으로 헬퍼를 설계한다.

## 서버 구현(#15, #17)과 맞출 것

결정은 아니지만, 서버 판정·예약 API가 나오면 바로 확인해야 하는 계약 지점이다. SDK는 `docs/api/control-plane-v1.yaml`을 기준으로 만들었다.

- **`Idempotency-Key` 형식**: 판정은 `dec:<requestId>`, 정산은 `stl:<reservationId>:<COMMIT|RELEASE|RECONCILIATION_REQUIRED>`. 8자 미만이나 128자 초과면 `dec:<sha256>` 형태로 바꾼다.
- **정산 본문의 `occurredAt`**: 정산을 예약할 때 한 번 정하고 재시도에는 같은 본문을 쓴다. 다른 시점에 같은 결과를 다시 보내면 본문이 달라 409가 된다. 서버의 "종료 상태에 같은 정산 → 원래 결과" 규칙이 본문 비교인지 결과 비교인지 맞춘다.
- **COMMIT 비용 분할**: 계약이 prompt·completion 비용을 따로 요구해서, SDK는 입력 토큰만으로 한 번 더 계산해 나눈다. 두 값의 합은 총비용과 같다(소수 6자리 올림).
- **API가 없을 때**: 서버 배포 전에 판정을 요청하면 404 → `INDETERMINATE`다. `on-indeterminate: fail-closed`를 고른 고객은 그동안 모든 호출이 막힌다.
- **판정 응답의 `requestId` 불일치**: 요청과 다른 `requestId`를 담은 200 응답은 `INDETERMINATE`(`UNREADABLE_RESPONSE`)로 본다.
