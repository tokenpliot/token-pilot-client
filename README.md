# Token Pilot Client (Java)

고객 Java 애플리케이션의 LLM 사용량을 Token Pilot Control Plane(`token-pilot-cloud-api`)으로 보내고, 고객이 고른 범위에서만 Control Plane 판정을 호출 전에 집행하는 Client SDK입니다. 기본은 **Observe 모드**(사용량 전송만, 호출 차단 없음, [#12](https://github.com/tokenpliot/token-pilot-cloud-api/issues/12))이고, 판정·집행은 명시적으로 켤 때만 동작합니다([#18](https://github.com/tokenpliot/token-pilot-cloud-api/issues/18), 아래 [판정과 집행](#판정과-집행-enforce)).

## 모듈

| 모듈 | Java | 의존성 | 역할 |
| --- | --- | --- | --- |
| `token-pilot-client` | 17+ | 없음(JDK만) | 이벤트 모델, 계약 검증, 비동기 배치 전송, 재시도, 통계, 판정·집행·예약 정산(`io.tokenpilot.client.enforce`) |
| `token-pilot-client-tokenpilot` | 25 | `token-pilot-core`, (선택) Spring Boot 4.1, (선택) Spring AI 2.0 | Token Pilot 원장(`LedgerListener`) 이벤트 전달, `ChatClient` 집행 advisor, Spring Boot 자동 구성 |

`token-pilot-client`는 Jackson을 포함해 런타임 의존성이 없습니다. Jackson 2를 쓰는 앱과 Jackson 3을 쓰는 앱(Spring Boot 4) 모두에서 충돌 없이 쓸 수 있게 하기 위해서입니다.

## 동작 보장 (Observe)

- `record()`는 크기가 제한된 메모리 큐에 넣기만 합니다. 네트워크를 기다리지 않고 전송 문제로 예외를 던지지 않으므로, Control Plane이 느리거나 내려가도 LLM 호출은 그대로 진행됩니다. 큐가 가득 차면 이벤트를 버리고 `QUEUE_FULL`로 집계합니다.
- 별도 스레드 하나가 이벤트를 보냅니다. 기본 경로는 최대 100건 또는 512KiB(설정 가능, 서버 한도 1MiB) 단위의 `POST /api/ingestion/events/batch`이고, `ingestionApi(OBSERVE_V2)`를 고르면 Observe 원장 경로로 한 건씩 보냅니다(아래 [전송 경로](#전송-경로)).
- 네트워크 오류, 타임아웃, 5xx, 429, `retryable=true` 항목은 지수 백오프(전체 지터)로 최대 `maxAttempts`번 다시 보냅니다. 재시도는 같은 불변 이벤트를 그대로 보내므로 서버가 `eventId`로 중복을 걸러 `DUPLICATE`로 답합니다(중복 회계 없음).
- 401/403/404/400처럼 요청 전체가 거부되면 재시도하지 않고 `REQUEST_REFUSED`로 버립니다. 413이면 배치를 반으로 나눠 다시 보냅니다.
- `close()`는 새 이벤트를 받지 않고, 큐에 남은 이벤트를 `shutdownTimeout`까지 전송한 뒤 남은 것은 `SHUTDOWN_TIMEOUT`으로 집계합니다.
- 모든 이벤트는 결국 전달(`created`/`duplicates`)되거나 사유별로 버려집니다(`dropped`). `stats()`와 `DeliveryListener`로 확인할 수 있습니다. API 키와 metadata 값은 로그나 통계에 남기지 않습니다.
- 원문 prompt/completion을 보내지 않습니다. `prompt`, `system_prompt`, `completion`, `messages`, `content`, `response` metadata 키는 전송 전에 제거합니다.

## 전송 경로

| | `INGESTION_BATCH` (기본) | `OBSERVE_V2` |
| --- | --- | --- |
| 서버 경로 | `POST /api/ingestion/events/batch` | `POST /api/v2/observe/events` |
| 저장 위치 | 기존 사용량 테이블(기존 대시보드·예산 집계) | Observe 원장(`/api/observe/ledger/*` 조회, 미정산·가격 미상 집계) |
| 비용 | 클라이언트가 계산한 USD를 보냄 | 보내지 않음. 서버가 자기 가격표로 계산하고, 계산할 수 없으면 0이 아니라 가격 미상(`UNPRICED`)으로 남김 |
| 요청 단위 | 최대 100건 배치 | 한 건씩, 같은 스레드에서 순서대로. 네트워크·5xx 실패가 나면 그 회차를 멈추고 남은 이벤트만 다시 보냄 |
| 보내지 않는 값 | 없음 | `totalTokens`, 비용, `pricingVersion`, `sourceType`. 서버 v2 계약에 해당 필드가 없음. metadata(`trace_id` 포함)는 보내며, v2 metadata를 지원하기 전 서버는 이를 무시함 |
| 서버 설정 | 없음 | `token-pilot.observe.ingestion.enabled=true`가 필요. 꺼져 있으면 404로 거부되어 `REQUEST_REFUSED`로 버려짐 |

`OBSERVE_V2`의 토큰은 포함 관계(`INCLUSIVE_V1`)로 해석됩니다. `promptTokens`에는 캐시 토큰이, `completionTokens`에는 추론 토큰이 들어 있어야 하며, provider 응답과 Token Pilot 원장은 이미 이 형태입니다. 캐시·추론 토큰을 모르면 0이 아니라 비워서(null) 보내고, 서버는 요율이 다르면 그 이벤트를 가격 미상으로 기록합니다.

기본값을 `INGESTION_BATCH`로 둔 이유는 서버의 v2 경로가 기본으로 꺼져 있어서입니다. Observe 원장을 쓰는 파일럿에서는 서버에서 v2를 켠 뒤 `OBSERVE_V2`로 바꿉니다.

## 사용법

### 평범한 Java

```java
TokenPilotClient client = TokenPilotClient.create(TokenPilotClientConfig.builder()
    .endpoint("https://control-plane.example.com")
    .apiKey(System.getenv("TOKEN_PILOT_API_KEY"))   // 프로젝트 API 키 (X-API-Key)
    .projectKey("support-copilot")
    .environment("prod")
    .build());

client.record(UsageEvent.builder()
    .provider("openai")
    .model("gpt-4o-mini")
    .promptTokens(1200)
    .completionTokens(400)
    .totalTokens(1600L)
    .totalCostUsd(new BigDecimal("0.00042"))
    .pricingVersion("2026-05-01")
    .traceId(currentTraceId)
    .build());

// 종료 시
client.close();
```

### Token Pilot 원장과 연결

```java
LedgerManager ledger = LedgerComponents.defaultLedgerManager(pricingRegistry, costCalculator,
    List.of(TokenPilotLedgerForwarder.builder(client).provider("openai").build()));
```

### Spring Boot (Token Pilot starter 사용 앱)

`token-pilot-client-tokenpilot` 의존성 하나를 추가하고 설정하면, Token Pilot 자동 구성이 `LedgerListener` 빈을 수집하므로 별도 코드 없이 원장 기록이 전송됩니다. 애플리케이션이 종료될 때 큐에 남은 이벤트를 전송합니다.

```yaml
token-pilot:
  client:
    endpoint: https://control-plane.example.com   # 설정하면 활성화
    ingestion-api: observe-v2                     # 생략하면 ingestion-batch
    api-key: ${TOKEN_PILOT_API_KEY}
    project-key: support-copilot
    environment: prod
    provider: openai
    forwarded-tags:          # 원장 태그 → metadata 키 (원문을 담지 않는 태그만)
      tenant: tenant_id
```

## 판정과 집행 (Enforce)

[ADR 0001](https://github.com/tokenpliot/token-pilot-cloud-api/blob/main/docs/adr/0001-decision-enforcement-boundary.md)의 책임 분리를 따릅니다. Control Plane은 판정만 하고, 막을지는 이 SDK가 **고객 설정으로만** 정합니다.

| 판정 \ 모드 | `OBSERVE` (기본) | `ADVISORY` | `ENFORCE` |
| --- | --- | --- | --- |
| `ALLOW` | 진행 | 진행 | 진행 |
| `DENY_POLICY`, `DENY_BUDGET` | 진행 | 진행 + 권고 | 집행 범위면 차단, 아니면 진행 + 권고 |
| `REQUIRE_APPROVAL` | 진행 | 진행 + 권고 | 집행 범위면 승인 대기(차단), 아니면 진행 + 권고 |
| `INDETERMINATE` | 진행 | 진행 + 권고 | `on-indeterminate: fail-closed`면 차단, 아니면 진행 + 권고 |
| `CONTROL_PLANE_UNAVAILABLE` | 진행 | 진행 + 권고 | `on-unavailable: fail-closed`면 차단, 아니면 진행 + 권고 |

- **HTTP 오류와 통신 실패는 절대 `DENY_*`가 되지 않습니다.** 408·429·5xx·연결 실패·타임아웃은 `CONTROL_PLANE_UNAVAILABLE`, 그 밖의 4xx(401 포함)는 `INDETERMINATE`입니다. 오류 응답 본문이 `DENY_*`를 담고 있어도 무시합니다.
- **판정 대기 시간에 상한이 있습니다.** `decision-timeout`(기본 1초, 최대 5초)을 넘기면 `CONTROL_PLANE_UNAVAILABLE`로 처리하고, 그다음은 `on-unavailable` 설정을 따릅니다.
- **예약 정산**: `ENFORCE`에서 `ALLOW`와 함께 받은 예약은 호출 뒤 백그라운드에서 정산합니다. 성공 + 가격 확인 → `COMMIT`(실제 사용량·비용), provider에 닿기 전 실패(`CallNotDispatchedException`) → `RELEASE`, provider 예외·사용량 없음·가격 미상 → `RECONCILIATION_REQUIRED`(결과가 불명확하므로 예약을 0으로 만들지 않음). 정산은 같은 `Idempotency-Key`와 같은 본문으로 재시도하며, 실패해도 호출 결과를 바꾸지 않습니다.
- **멱등성**: 같은 `requestId`로 다시 실행하면 판정 요청도 같은 `Idempotency-Key`(`dec:<requestId>`)로 가서 서버가 처음 판정과 예약을 돌려줍니다. 재시도할 때는 앱의 요청 id를 유지하세요.
- 판정 요청에는 토큰 추정치와 모델만 갑니다. 원문 prompt는 입력 토큰 추정에만 쓰이고 전송되지 않습니다.

### Spring Boot + Spring AI

`enforcement.enabled=true`일 때만 모든 `ChatClient`에 advisor가 붙습니다. 켜면 `OBSERVE`에서도 호출마다 판정을 기다리므로(최대 `decision-timeout`) 기본은 꺼져 있습니다.

```yaml
token-pilot:
  client:
    endpoint: https://control-plane.example.com
    api-key: ${TOKEN_PILOT_API_KEY}     # 프로젝트·환경 키 = 프로젝트/환경 범위
    project-key: support-copilot
    environment: prod
    provider: openai
    enforcement:
      enabled: true
      mode: advisory                    # 이 키의 프로젝트/환경 기본값
      decision-timeout: 800ms
      endpoints:                        # 호출 지점별 opt-in
        checkout-summary:
          mode: enforce
          enforced-outcomes: [DENY_POLICY, DENY_BUDGET]
          on-unavailable: fail-closed   # 생략하면 위 기본값(fail-open)을 물려받음
```

```java
try {
    ChatClientResponse response = chatClient.prompt()
        .user(question)
        .advisors(a -> a.param("tokenpilot.request.id", commandId)              // 재시도 시 같은 값
                        .param("tokenpilot.enforcement.endpoint", "checkout-summary"))
        .call()
        .chatClientResponse();
    response.context().get("tokenpilot.enforcement.outcome");   // ALLOW, DENY_BUDGET, ... (권고 표시용)
    response.context().get("tokenpilot.enforcement.action");    // PROCEED, PROCEED_WITH_ADVISORY
} catch (EnforcementException blocked) {
    blocked.decision().outcome();   // 차단 원인: 정책 거부인지, 판정 불가·장애(fail-closed)인지
    blocked.approvalId();           // REQUIRE_APPROVAL 집행 시
}
```

advisor는 Token Pilot `LedgerAdvisor` 바로 바깥(`LOWEST_PRECEDENCE - 2`)에서 돌기 때문에, 차단된 호출은 Token Pilot 원장과 모델 어느 쪽에도 닿지 않습니다. 실제 사용량은 Token Pilot의 `UsageExtractor`로 읽고, 비용은 Token Pilot 가격표(`token-pilot.pricing.plans`)로 계산합니다. 응답 모델의 USD 가격이 없으면 비용을 추정하지 않고 `RECONCILIATION_REQUIRED`로 정산합니다.

### 평범한 Java

```java
ControlPlaneEnforcer enforcer = ControlPlaneEnforcer.create(EnforcerConfig.builder()
    .endpoint("https://control-plane.example.com")
    .apiKey(System.getenv("TOKEN_PILOT_API_KEY"))
    .environment("prod")
    .policy(EnforcementPolicy.builder()
        .defaults(EnforcementSettings.defaults())                       // OBSERVE
        .endpoint("checkout-summary", EnforcementSettings.enforce())    // 이 호출 지점만 ENFORCE
        .build())
    .build());

EnforcedResult<MyResponse> result = enforcer.execute(
    CallRequest.builder().requestId(commandId).endpoint("checkout-summary")
        .provider("openai").model("gpt-4o-mini").estimatedInputTokens(1200).maxOutputTokens(800).build(),
    () -> provider.call(...),                                   // 호출 전 실패면 CallNotDispatchedException
    response -> ActualUsage.priced(response.inputTokens(), response.outputTokens(), promptCost, completionCost));
```

## 원장 이벤트 매핑

| Control Plane 필드 | 값 |
| --- | --- |
| `eventId`, `requestId` | 원장 기록마다 새 UUID. 한 요청이 원장 기록을 여러 번 남길 수 있고, 서버는 `requestId`도 유일해야 하기 때문 |
| `promptTokens` / `completionTokens` | `inputTokens` / `outputTokens` |
| `cachedPromptTokens` / `reasoningTokens` | `cacheReadInputTokens` / `reasoningOutputTokens` |
| `totalTokens` | `inputTokens + outputTokens`를 명시적으로 보냄. Token Pilot의 입력·출력 합계에는 캐시·추론 토큰이 이미 포함되어 있어서, 서버가 합계를 계산하면 이중 집계됨 |
| `totalCostUsd` | 비용 통화가 USD일 때만 보냄. 다른 통화는 `cost_currency` metadata에만 남김 |
| metadata | `usage_source`, `app_request_id`(`tokenpilot.request.id` 태그), `trace_id`, `forwardTag`로 지정한 태그만. 원장 태그에는 호출 컨텍스트의 모든 문자열이 들어 있으므로 전부 보내지 않음. Token Pilot은 요청 id를 만들지 않으므로 `app_request_id`는 앱이 `ChatClient` advisor param(`a.param("tokenpilot.request.id", id)`)으로 넘길 때만 채워짐 |
| `pricingVersion`, `sourceType` | 기본값 `tokenpilot-local`, `tokenpilot-java` |

## 알려진 한계

아래 한계 중 정책 결정이 필요한 것(스트리밍 집행, v2 metadata, 가격표 없는 모델, 기본 전송 경로, 승인 대기)은 [`docs/open-decisions.md`](docs/open-decisions.md)에 선택지와 권장안을 정리했습니다.

- 판정·정산 API(`/api/control/v1/decisions`, `/reservations/{id}/settlements`)는 서버에 아직 구현되지 않았습니다(#15, #17). 이 SDK는 [`control-plane-v1.yaml`](https://github.com/tokenpliot/token-pilot-cloud-api/blob/main/docs/api/control-plane-v1.yaml) 계약을 기준으로 만들었고, 서버가 나오기 전에 `enforcement.enabled=true`로 켜면 판정 요청이 404(`INDETERMINATE`)를 받습니다. `on-indeterminate: fail-closed`가 아니면 호출은 막히지 않습니다.
- 집행 advisor는 동기 `ChatClient.call()`만 다룹니다. 스트리밍(`stream()`)에는 붙지 않습니다.
- `REQUIRE_APPROVAL` 집행은 `approvalId`와 함께 호출을 막는 데까지입니다. 승인 상태 조회(`GET /approvals/{id}`)와 승인 후 재실행은 앱이 같은 `requestId`로 다시 호출해 처리합니다.
- `token-pilot-core`는 아직 배포되지 않았습니다. 빌드할 때는 `../tokenpilot` 체크아웃을 소스로 포함하거나(`-PtokenpilotDir=...`로 위치 변경) `mavenLocal`에서 가져옵니다.
- Token Pilot의 Spring AI advisor는 가격표가 없는 모델(`UNPRICED`)이나 요청·응답 모델이 다른 호출(`RECONCILIATION_REQUIRED`)을 원장에 기록하지 않습니다. 이 SDK는 `ChatClient`에 `UnreconciledUsageAdvisor`를 붙여 그런 호출도 토큰과 함께 비용 없이 보냅니다(metadata `pricing_reconciliation`). 원장에 기록된 호출은 `LedgerListener`로만 보내므로 두 번 가지 않습니다.
- `INGESTION_BATCH` 계약(`schemaVersion 2026-10-01`)에는 가격 미상을 표현하는 방법이 없어서, 위 호출이 배치 경로로 가면 서버에 비용 0으로 저장됩니다. 가격 미상을 구분해야 하면 `OBSERVE_V2`를 씁니다.
- Token Pilot 예산 기능(`token-pilot.budget.enabled=true`)과 이 SDK의 사용량 전달은 함께 쓰지 않습니다. 예산 기능을 켜면 advisor가 `LedgerListener` 대신 예약 정산 경로를 써서 전달이 멈춥니다. 예산 집행이 필요하면 Token Pilot 로컬 예산 대신 Control Plane 판정·집행(Enforce)을 씁니다.
- `INGESTION_BATCH`와 v2 모두 `Retry-After`를 주지 않아 재시도 간격은 클라이언트 설정으로 정합니다.
- 큐는 프로세스 메모리에만 있습니다. 프로세스가 비정상 종료되면 아직 보내지 못한 이벤트는 사라집니다(디스크 버퍼는 범위 밖).
- 재시도를 모두 실패하면 배치마다 WARNING 로그를 남깁니다. Control Plane이 오래 내려가 있으면 로그가 많아질 수 있습니다.

## 개발

```bash
git clone https://github.com/tokenpliot/tokenpilot ../tokenpilot   # token-pilot-core 소스
./gradlew test

# 고객 앱 fixture (의존성 하나): 실제 Control Plane으로 전송
cd fixtures/plain-java-app
TP_ENDPOINT=http://localhost:8080 TP_API_KEY=... TP_PROJECT_KEY=... TP_ENVIRONMENT=prod ../../gradlew run
# Observe 원장 경로: TP_INGESTION_API=OBSERVE_V2 를 추가
```

JDK 25 toolchain이 필요합니다. `token-pilot-client`는 `--release 17`로 컴파일됩니다.

## 라이선스

[MIT](LICENSE)
