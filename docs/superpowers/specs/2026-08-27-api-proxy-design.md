# API Proxy Security Design

Date: 2026-08-27
Status: Approved direction, pending implementation plan

## 1. Context

The Android application currently calls DeepSeek, Alibaba Cloud Model Studio (Qwen), and Amap Web Service APIs directly. Provider API keys are compiled into the APK through `BuildConfig`, so anyone with repository or APK access can recover and misuse them.

The competition build must continue to work immediately after installation. Judges must not be asked to enter provider API keys or create accounts.

## 2. Goals

- Remove all provider API keys from the Android source, Git-tracked configuration, and APK.
- Keep the judge experience unchanged: install the APK and use AI, speech, image, and weather features directly.
- Preserve the current on-device inventory database and local agent tool execution.
- Use a mainland-China-accessible backend with minimal operations work.
- Limit the financial impact of automated or abusive requests.
- Keep backend source code safe to publish in the public repository.

## 3. Non-goals

- User registration, SMS login, or account synchronization.
- Moving the Room inventory database to the cloud.
- Storing chat history, photos, audio, or inventory data on the server.
- Building a general-purpose forwarding proxy.
- Guaranteeing that a no-login public client can never be automated or abused. The design limits abuse and protects provider credentials, but a public client cannot provide perfect caller identity.

## 4. Chosen Architecture

Use an Alibaba Cloud Function Compute web function deployed in a mainland China region. The backend will be a Node.js 20 TypeScript service stored in the public repository under `server/`.

```text
Android application
  | HTTPS + short-lived session token
  v
Alibaba Cloud Function Compute API proxy
  |-- DeepSeek chat completions
  |-- Qwen chat, vision, and speech requests
  `-- Amap geocoding, IP location, and weather requests
```

The Android application sends the same model request content it currently produces. The backend validates and forwards only approved fields to fixed provider endpoints, injects the corresponding server-side API key, and returns a compatible response.

The backend must never accept a provider URL from the client. Provider hosts, paths, models, and timeouts are allowlisted in server code or server environment variables.

## 5. Repository Layout

```text
youshu/
  app/                         Android application
  server/
    src/
      app.ts                   HTTP routes and startup
      config.ts                Environment validation
      auth.ts                  Anonymous session tokens
      limits.ts                Request validation and rate limiting
      providers/
        deepseek.ts
        qwen.ts
        amap.ts
      errors.ts                Stable API error contract
    test/
    package.json
    tsconfig.json
    .env.example               Variable names only, no values
  docs/
```

The real `.env` file is ignored by Git. Production secrets exist only in Function Compute environment variables.

## 6. Server API

### 6.1 Health

`GET /health`

Returns service status without exposing configuration or provider availability details.

### 6.2 Anonymous session

`POST /v1/session`

Request:

```json
{
  "installationId": "random UUID generated on first app launch",
  "appVersion": "1.2.0"
}
```

Response:

```json
{
  "token": "short-lived signed token",
  "expiresAt": 0
}
```

The token is signed with `SESSION_SIGNING_SECRET`, expires after 24 hours, and contains only a hashed installation identifier and app version. It is not a provider credential. The app refreshes it when needed.

### 6.3 DeepSeek

`POST /v1/deepseek/chat/completions`

Accepts the allowlisted OpenAI-compatible chat fields required by `AgentClient`, including messages, tools, tool choice, model options, and non-streaming responses. The server always chooses the configured DeepSeek model and provider endpoint. The client cannot override the host or API key.

### 6.4 Qwen

`POST /v1/qwen/chat/completions`

Accepts the allowlisted OpenAI-compatible fields required by text parsing, image recognition, image description, and speech recognition. Image and audio payload limits are enforced before forwarding. The server chooses an allowlisted model according to an explicit request purpose rather than accepting arbitrary model IDs.

Allowed purposes:

- `vision`
- `speech`

Text-only inventory search parsing continues to use the DeepSeek route so provider behavior does not change during the security migration.

### 6.5 Amap

The backend exposes task-specific routes rather than a generic Amap proxy:

- `POST /v1/amap/ip-location`
- `POST /v1/amap/geocode`
- `POST /v1/amap/reverse-geocode`
- `POST /v1/amap/weather`

Each route validates its own city, adcode, IP, or coordinate fields. The Amap key is added only by the server.

## 7. Android Changes

### 7.1 Configuration

Remove the three provider key `BuildConfig` fields and replace them with one non-secret backend base URL. Debug builds may override the backend URL through untracked `local.properties`.

The API-key management screen must no longer be required for the competition build. Existing Room rows may remain for model labels and migration compatibility, but provider keys must be ignored and cleared during migration.

### 7.2 Networking

Add a small backend client responsible for:

- creating and refreshing anonymous sessions;
- attaching the session token;
- adding request IDs;
- mapping stable server errors to user-facing Chinese messages;
- retrying only safe transient failures.

`AgentClient` will send its OpenAI-compatible requests to the DeepSeek proxy route. `AiInferenceRepository` will send Qwen requests to the Qwen proxy route. `WeatherAgentTool` will use the four Amap proxy routes.

### 7.3 Local data and tool calls

Inventory records, location trees, categories, ratings, and mutation tools remain on the device. DeepSeek can continue to return tool calls, and the Android application continues executing those tools locally. The server does not receive the Room database itself, but prompts may still contain the same selected inventory context currently sent to the model.

## 8. Secret Management

Function Compute environment variables:

- `DEEPSEEK_API_KEY`
- `QWEN_API_KEY`
- `AMAP_WEB_API_KEY`
- `SESSION_SIGNING_SECRET`
- `DEEPSEEK_MODEL`
- `QWEN_VISION_MODEL`
- `QWEN_SPEECH_MODEL`
- `ALLOWED_APP_VERSIONS`

No real value appears in `.env.example`, tests, logs, documentation, Android resources, Gradle files, or Git history created by this implementation.

All previously published provider keys must be revoked before the new backend is considered usable. Removing them from the latest commit alone does not make leaked keys safe.

Rewriting existing Git history is a separate destructive operation because it requires a force push and affects every teammate's clone. It will be proposed and performed only after explicit confirmation. Revoking leaked keys is mandatory regardless of whether history is rewritten.

## 9. Abuse Controls

The competition version uses defense in depth:

- Short-lived signed anonymous session tokens.
- Session creation throttled by source IP and installation ID.
- Request throttling by route, source IP, and installation ID.
- A low global requests-per-second ceiling at the public ingress.
- Maximum request sizes for text, image, and audio.
- Fixed model and provider allowlists.
- Provider-side spending alerts and hard budget limits where available.
- Timeouts and bounded retries to prevent request multiplication.

Anonymous sessions reduce accidental sharing and make revocation possible, but they are not proof of a genuine device. If the application later becomes a public product, add real user authentication or a platform attestation mechanism and persistent distributed rate-limit storage.

## 10. Privacy and Logging

The server does not persist request bodies or provider responses. Operational logs contain only:

- generated request ID;
- route name;
- HTTP status;
- response latency;
- payload size;
- provider error category;
- hashed installation identifier.

Logs must never contain API keys, authorization headers, full prompts, images, audio, precise coordinates, or inventory contents.

## 11. Error Contract

All backend errors use this shape:

```json
{
  "error": {
    "code": "RATE_LIMITED",
    "message": "请求过于频繁，请稍后再试。",
    "requestId": "generated request ID",
    "retryable": true
  }
}
```

Important statuses:

- `400`: invalid request
- `401`: missing or expired session
- `413`: payload too large
- `429`: rate limited or quota reached
- `502`: provider rejected or failed
- `504`: provider timeout

The Android app retries a session-expired request once after refreshing the token. It does not automatically retry mutation requests or repeated large uploads.

## 12. Deployment

Initial deployment uses the Function Compute test domain for integration. Before judges depend on the service, use a stable HTTPS endpoint. A custom domain is preferred when one is available and properly configured; otherwise the Function Compute endpoint is acceptable for the limited competition evaluation period with monitoring enabled.

Deployment steps:

1. Create a Function Compute web function in a mainland China region.
2. Deploy the compiled `server/` package.
3. Configure environment variables in the Function Compute console.
4. Configure public HTTPS access and throttling.
5. Verify `/health` and each provider route.
6. Put the backend base URL into the Android build configuration.
7. Build and test a fresh APK.
8. Revoke old provider keys and confirm they no longer work.

## 13. Testing

### Server automated tests

- Environment validation fails closed when a required secret is missing.
- Provider routes reject unsupported models and fields.
- Authorization headers from clients are discarded.
- Session expiry and refresh behavior.
- Text, image, and audio size limits.
- Provider timeout and error mapping.
- Logs are redacted.
- Mocked DeepSeek tool-call responses remain OpenAI compatible.

### Android tests

- Session token creation, caching, expiry, and one-time refresh.
- DeepSeek tool calls still reach local inventory tools.
- Qwen image and speech requests preserve existing behavior.
- Weather geocoding and forecasts preserve existing behavior.
- User-friendly errors for offline, timeout, rate limit, and service outage.

### End-to-end checks

- Normal chat.
- Inventory query and mutation through agent tool calls.
- Photo recognition.
- Agent image question.
- Voice recognition.
- Current and forecast weather with device location and named cities.
- Operation after a clean APK installation with no provider keys entered.

## 14. Rollout and Rollback

The first APK using the proxy is tested alongside the existing build. Provider keys are rotated only after the proxy build passes end-to-end checks, then the old keys are revoked immediately.

Rollback changes only the backend deployment version or Android backend base URL. It must never restore provider keys to the APK.

## 15. Acceptance Criteria

- No active provider key exists in tracked files or the built APK.
- A clean installation works without asking judges for API keys.
- DeepSeek chat and local agent tools work through the proxy.
- Qwen image recognition and speech recognition work through the proxy.
- Amap location and weather work through the proxy.
- Oversized, unauthorized, and excessive requests are rejected.
- Provider failures produce stable, understandable Android errors.
- Backend logs contain no request content or secrets.
- Old exposed keys are revoked and show no successful new usage.
