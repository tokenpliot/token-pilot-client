# Token Pilot Client (Java)

고객 Java 애플리케이션의 LLM 사용량을 Token Pilot Control Plane(`token-pilot-cloud-api`)으로 보내는 Client SDK입니다. 현재 범위는 **Observe 모드**(사용량 전송만, 호출 차단 없음)이며 [token-pilot-cloud-api#12](https://github.com/tokenpliot/token-pilot-cloud-api/issues/12)를 구현합니다. Enforce 연동(#18)은 서버의 판정·예약 API(#14~#17) 이후에 다룹니다.

## 모듈

| 모듈 | Java | 의존성 | 역할 |
| --- | --- | --- | --- |
| `token-pilot-client` | 17+ | 없음(JDK만) | 이벤트 모델, 계약 검증, 비동기 배치 전송, 재시도, 통계 |
| `token-pilot-client-tokenpilot` | 25 | `token-pilot-core`, (선택) Spring Boot 4.1 | Token Pilot 원장(`LedgerListener`) 이벤트 전달, Spring Boot 자동 구성 |

`token-pilot-client`는 Jackson을 포함해 런타임 의존성이 없습니다. Jackson 2를 쓰는 앱과 Jackson 3을 쓰는 앱(Spring Boot 4) 모두에서 충돌 없이 쓸 수 있게 하기 위해서입니다.

## 동작 보장 (Observe)

- `record()`는 크기가 제한된 메모리 큐에 넣기만 합니다. 네트워크를 기다리지 않고 전송 문제로 예외를 던지지 않으므로, Control Plane이 느리거나 내려가도 LLM 호출은 그대로 진행됩니다. 큐가 가득 차면 이벤트를 버리고 `QUEUE_FULL`로 집계합니다.
- 별도 스레드 하나가 최대 100건 또는 512KiB(설정 가능, 서버 한도 1MiB) 단위로 `POST /api/ingestion/events/batch`에 보냅니다.
- 네트워크 오류, 타임아웃, 5xx, 429, `retryable=true` 항목은 지수 백오프(전체 지터)로 최대 `maxAttempts`번 다시 보냅니다. 재시도는 같은 불변 이벤트를 그대로 보내므로 서버가 `eventId`로 중복을 걸러 `DUPLICATE`로 답합니다(중복 회계 없음).
- 401/403/404/400처럼 요청 전체가 거부되면 재시도하지 않고 `REQUEST_REFUSED`로 버립니다. 413이면 배치를 반으로 나눠 다시 보냅니다.
- `close()`는 새 이벤트를 받지 않고, 큐에 남은 이벤트를 `shutdownTimeout`까지 전송한 뒤 남은 것은 `SHUTDOWN_TIMEOUT`으로 집계합니다.
- 모든 이벤트는 결국 전달(`created`/`duplicates`)되거나 사유별로 버려집니다(`dropped`). `stats()`와 `DeliveryListener`로 확인할 수 있습니다. API 키와 metadata 값은 로그나 통계에 남기지 않습니다.
- 원문 prompt/completion을 보내지 않습니다. `prompt`, `system_prompt`, `completion`, `messages`, `content`, `response` metadata 키는 전송 전에 제거합니다.

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
    api-key: ${TOKEN_PILOT_API_KEY}
    project-key: support-copilot
    environment: prod
    provider: openai
    forwarded-tags:          # 원장 태그 → metadata 키 (원문을 담지 않는 태그만)
      tenant: tenant_id
```

## 원장 이벤트 매핑

| Control Plane 필드 | 값 |
| --- | --- |
| `eventId`, `requestId` | 원장 기록마다 새 UUID. 한 요청이 원장 기록을 여러 번 남길 수 있고, 서버는 `requestId`도 유일해야 하기 때문 |
| `promptTokens` / `completionTokens` | `inputTokens` / `outputTokens` |
| `cachedPromptTokens` / `reasoningTokens` | `cacheReadInputTokens` / `reasoningOutputTokens` |
| `totalTokens` | `inputTokens + outputTokens`를 명시적으로 보냄. Token Pilot의 입력·출력 합계에는 캐시·추론 토큰이 이미 포함되어 있어서, 서버가 합계를 계산하면 이중 집계됨 |
| `totalCostUsd` | 비용 통화가 USD일 때만 보냄. 다른 통화는 `cost_currency` metadata에만 남김 |
| metadata | `usage_source`, `app_request_id`(`tokenpilot.request.id` 태그), `trace_id`, `forwardTag`로 지정한 태그만. 원장 태그에는 호출 컨텍스트의 모든 문자열이 들어 있으므로 전부 보내지 않음 |
| `pricingVersion`, `sourceType` | 기본값 `tokenpilot-local`, `tokenpilot-java` |

## 알려진 한계

- `token-pilot-core`는 아직 배포되지 않았습니다. 빌드할 때는 `../tokenpilot` 체크아웃을 소스로 포함하거나(`-PtokenpilotDir=...`로 위치 변경) `mavenLocal`에서 가져옵니다.
- 서버 계약(`schemaVersion 2026-10-01`)에는 가격 미상(unpriced)을 표현하는 방법이 없습니다. Token Pilot 원장도 가격표가 없는 모델을 0 USD로 기록하므로, 서버에는 0으로 저장됩니다.
- 서버가 `Retry-After`를 주지 않아 재시도 간격은 클라이언트 설정으로 정합니다.
- 큐는 프로세스 메모리에만 있습니다. 프로세스가 비정상 종료되면 아직 보내지 못한 이벤트는 사라집니다(디스크 버퍼는 범위 밖).
- 재시도를 모두 실패하면 배치마다 WARNING 로그를 남깁니다. Control Plane이 오래 내려가 있으면 로그가 많아질 수 있습니다.

## 개발

```bash
git clone https://github.com/tokenpliot/tokenpilot ../tokenpilot   # token-pilot-core 소스
./gradlew test

# 고객 앱 fixture (의존성 하나): 실제 Control Plane으로 전송
cd fixtures/plain-java-app
TP_ENDPOINT=http://localhost:8080 TP_API_KEY=... TP_PROJECT_KEY=... TP_ENVIRONMENT=prod ../../gradlew run
```

JDK 25 toolchain이 필요합니다. `token-pilot-client`는 `--release 17`로 컴파일됩니다.

## 라이선스

[MIT](LICENSE)
