# Secure API Proxy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every provider API key embedded in the Android application with a secure Alibaba Cloud Function Compute proxy that judges can use without entering credentials.

**Architecture:** A dependency-light Node.js 20 TypeScript web function exposes fixed DeepSeek, Qwen, and Amap routes, injects provider keys from Function Compute environment variables, and issues short-lived anonymous session tokens. The Android app talks only to this backend while inventory storage and agent tool execution remain on-device.

**Tech Stack:** Node.js 20, TypeScript, Node built-in HTTP/fetch/crypto/test APIs, Alibaba Cloud Function Compute Web Function, Kotlin 2.1, Android API 26+, OkHttp 4.12, kotlinx.serialization, Room 2.6.1, JUnit 4, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-08-27-api-proxy-design.md`

## Global Constraints

- No active DeepSeek, Qwen, or Amap key may exist in Git-tracked files, Android resources, BuildConfig fields, logs, tests, or the APK.
- Judges install and use the app without registration or provider-key entry.
- Room inventory, category, location, rating, and chat data remain on the device.
- The server never accepts a provider hostname, URL, API key, or arbitrary model ID from the client.
- The server does not persist request or response bodies.
- Images and audio are size-limited before provider forwarding.
- Android minSdk remains 26, targetSdk remains 35, and JVM target remains 17.
- Provider requests are non-streaming in this implementation.
- Existing leaked provider keys are revoked after the proxy deployment passes end-to-end verification.
- Git history rewriting remains a separate explicitly approved operation.

---

## File Structure

### Server files to create

- `server/package.json`: build, test, start, and package metadata.
- `server/tsconfig.json`: Node 20 TypeScript compilation.
- `server/.env.example`: secret variable names with empty values.
- `server/DEPLOY.md`: exact Function Compute console settings and verification commands.
- `server/src/config.ts`: strict environment parsing and provider allowlists.
- `server/src/errors.ts`: stable `ApiError` and JSON error responses.
- `server/src/auth.ts`: installation ID hashing and HMAC-signed session tokens.
- `server/src/limits.ts`: body limits and best-effort per-instance request throttling.
- `server/src/http.ts`: JSON body parsing, request IDs, response helpers, and redacted logging.
- `server/src/providers/deepseek.ts`: fixed DeepSeek forwarding.
- `server/src/providers/qwen.ts`: purpose-to-model routing and Qwen forwarding.
- `server/src/providers/amap.ts`: fixed Amap endpoint calls.
- `server/src/router.ts`: route dispatch and authorization policy.
- `server/src/app.ts`: Node HTTP server listening on `FC_CUSTOM_LISTEN_PORT`, `PORT`, or 9000.
- `server/test/*.test.ts`: config, auth, routing, limits, and provider contract tests.

### Android files to create

- `app/src/main/java/com/youshu/app/data/network/BackendApiClient.kt`: session management and authenticated JSON calls.
- `app/src/main/java/com/youshu/app/data/network/BackendApiException.kt`: stable backend error parsing.
- `app/src/test/java/com/youshu/app/data/network/BackendApiClientTest.kt`: MockWebServer contract tests.

### Android files to modify

- `.gitignore`: ignore `server/.env`, `server/node_modules`, `server/dist`, and deployment ZIPs.
- `gradle/libs.versions.toml`: add MockWebServer test dependency.
- `app/build.gradle.kts`: remove provider keys; add `YOUSHU_BACKEND_BASE_URL` only.
- `app/src/main/java/com/youshu/app/data/agent/AgentClient.kt`: send DeepSeek requests through the backend.
- `app/src/main/java/com/youshu/app/data/ai/AiInferenceRepository.kt`: send Qwen requests through the backend with an explicit purpose.
- `app/src/main/java/com/youshu/app/data/agent/WeatherAgentTool.kt`: keep device location acquisition local and send Amap operations through the backend.
- `app/src/main/java/com/youshu/app/data/repository/AiModelRepository.kt`: remove BuildConfig key fallback.
- `app/src/main/java/com/youshu/app/data/local/database/AppDatabase.kt`: database version 10 migration that clears saved provider keys.
- `app/src/main/java/com/youshu/app/ui/screen/profile/ProfileScreen.kt`: replace editable API-key management with server-managed service status.
- `README.md`: document the proxy architecture without secrets.

---

### Task 1: Server configuration, errors, and health endpoint

**Files:**
- Create: `server/package.json`
- Create: `server/tsconfig.json`
- Create: `server/.env.example`
- Create: `server/src/config.ts`
- Create: `server/src/errors.ts`
- Create: `server/src/http.ts`
- Create: `server/src/router.ts`
- Create: `server/src/app.ts`
- Test: `server/test/config.test.ts`
- Test: `server/test/health.test.ts`

**Interfaces:**
- Produces: `loadConfig(env: NodeJS.ProcessEnv): ServerConfig`
- Produces: `createRouter(deps: RouterDependencies): (request: IncomingMessage, response: ServerResponse) => Promise<void>`
- Produces: `createServer(config: ServerConfig): http.Server`
- Produces: stable error shape `{ error: { code, message, requestId, retryable } }`

- [ ] **Step 1: Add server package metadata and TypeScript configuration**

Use Node's built-in HTTP, fetch, crypto, and test modules so production has no runtime dependencies. Add only `typescript` and `@types/node` as development dependencies. Configure `npm run build` as `tsc`, `npm test` as `npm run build && node --test dist/test/*.test.js`, and `npm start` as `node dist/src/app.js`.

- [ ] **Step 2: Write failing configuration tests**

```ts
test("loadConfig rejects missing secrets", () => {
  assert.throws(() => loadConfig({}), /DEEPSEEK_API_KEY/);
});

test("loadConfig never accepts provider URLs from the environment", () => {
  const config = loadConfig(validEnvironment());
  assert.equal(config.deepseekBaseUrl, "https://api.deepseek.com");
  assert.equal(config.qwenBaseUrl, "https://dashscope.aliyuncs.com/compatible-mode/v1");
});
```

- [ ] **Step 3: Run tests and confirm they fail**

Run from `server/`: `pnpm install && pnpm test`

Expected: compilation fails because `loadConfig` and server modules do not exist.

- [ ] **Step 4: Implement strict configuration and error helpers**

Define:

```ts
export interface ServerConfig {
  deepseekApiKey: string;
  qwenApiKey: string;
  amapWebApiKey: string;
  sessionSigningSecret: string;
  deepseekModel: string;
  qwenVisionModel: string;
  qwenSpeechModel: string;
  allowedAppVersions: ReadonlySet<string>;
  deepseekBaseUrl: "https://api.deepseek.com";
  qwenBaseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1";
  amapBaseUrl: "https://restapi.amap.com/v3";
}
```

Require every secret to be nonblank and require `SESSION_SIGNING_SECRET` to contain at least 32 characters. Add `ApiError` with `status`, `code`, `safeMessage`, and `retryable` fields. Do not include upstream response bodies in logs or public errors.

- [ ] **Step 5: Implement `GET /health` and default 404 handling**

`/health` returns `200 {"status":"ok"}` without running authentication or provider checks. Unknown routes return `404` using the stable error contract.

- [ ] **Step 6: Run server tests**

Run: `pnpm test`

Expected: config and health tests pass.

- [ ] **Step 7: Commit**

```bash
git add server .gitignore
git commit -m "feat: scaffold secure API proxy"
```

### Task 2: Anonymous sessions, request limits, and redacted logging

**Files:**
- Create: `server/src/auth.ts`
- Create: `server/src/limits.ts`
- Modify: `server/src/http.ts`
- Modify: `server/src/router.ts`
- Test: `server/test/auth.test.ts`
- Test: `server/test/limits.test.ts`

**Interfaces:**
- Produces: `issueSession(installationId: string, appVersion: string, config: ServerConfig, now?: number): SessionResponse`
- Produces: `verifySession(token: string, config: ServerConfig, now?: number): SessionClaims`
- Produces: `assertWithinLimit(key: string, rule: RateLimitRule, now?: number): void`
- Consumes: `ServerConfig` and `ApiError` from Task 1.

- [ ] **Step 1: Write failing session tests**

```ts
test("session expires after 24 hours", () => {
  const issued = issueSession("550e8400-e29b-41d4-a716-446655440000", "1.2.0", config, 1_000);
  assert.throws(() => verifySession(issued.token, config, 86_401_001), /expired/i);
});

test("tampered token is rejected", () => {
  const issued = issueSession(validInstallationId, "1.2.0", config, 1_000);
  assert.throws(() => verifySession(`${issued.token}x`, config, 2_000), /signature/i);
});
```

- [ ] **Step 2: Run tests and confirm they fail**

Run: `pnpm test`

Expected: missing auth and limiter modules.

- [ ] **Step 3: Implement HMAC session tokens**

Use base64url JSON payload plus HMAC-SHA256 signature from Node `crypto`. Claims are:

```ts
interface SessionClaims {
  installationHash: string;
  appVersion: string;
  issuedAt: number;
  expiresAt: number;
}
```

Hash installation IDs before placing them in tokens or logs. Validate UUID format and allowed app versions. Never use the installation ID as a secret.

- [ ] **Step 4: Implement best-effort per-instance sliding-window limits**

Use separate rules for session creation, text, image/audio, and Amap routes. Return `429 RATE_LIMITED` when exceeded. Bound the map size and remove expired buckets to avoid unbounded memory growth.

- [ ] **Step 5: Add `POST /v1/session` and route authorization**

All `/v1/*` routes except `/v1/session` require `Authorization: Bearer <session-token>`. Log request ID, route, status, latency, size, and installation hash only.

- [ ] **Step 6: Run tests**

Run: `pnpm test`

Expected: token tampering, expiry, bad version, and rate-limit tests pass.

- [ ] **Step 7: Commit**

```bash
git add server/src server/test
git commit -m "feat: protect proxy with anonymous sessions"
```

### Task 3: DeepSeek and Qwen provider proxies

**Files:**
- Create: `server/src/providers/deepseek.ts`
- Create: `server/src/providers/qwen.ts`
- Modify: `server/src/router.ts`
- Test: `server/test/ai-proxy.test.ts`

**Interfaces:**
- Produces: `forwardDeepSeek(body: unknown, config: ServerConfig, requestId: string, fetchImpl?: typeof fetch): Promise<ProviderResponse>`
- Produces: `forwardQwen(purpose: QwenPurpose, body: unknown, config: ServerConfig, requestId: string, fetchImpl?: typeof fetch): Promise<ProviderResponse>`
- Defines: `type QwenPurpose = "vision" | "speech"`
- Consumes: `ServerConfig`, `ApiError`, authenticated request context, and body limits.

- [ ] **Step 1: Write failing proxy contract tests with mocked fetch**

```ts
test("DeepSeek proxy overwrites model and authorization", async () => {
  const fetchSpy = recordingFetch(okResponse(toolCallResponse));
  await forwardDeepSeek({ model: "attacker-model", messages: [], tools: [] }, config, "req-1", fetchSpy);
  assert.equal(fetchSpy.lastJson.model, config.deepseekModel);
  assert.equal(fetchSpy.lastHeaders.authorization, `Bearer ${config.deepseekApiKey}`);
});

test("Qwen vision purpose chooses only the configured vision model", async () => {
  const fetchSpy = recordingFetch(okResponse(chatResponse));
  await forwardQwen("vision", { model: "other", messages: [] }, config, "req-2", fetchSpy);
  assert.equal(fetchSpy.lastJson.model, config.qwenVisionModel);
});
```

- [ ] **Step 2: Run tests and confirm they fail**

Run: `pnpm test`

Expected: missing provider modules.

- [ ] **Step 3: Implement request allowlisting**

Accept only `messages`, `tools`, `tool_choice`, `temperature`, `top_p`, `max_tokens`, and `stream=false`. Reject streaming and malformed message arrays. Deep-copy accepted JSON and overwrite `model` and `stream` server-side.

- [ ] **Step 4: Implement fixed upstream calls and safe error mapping**

DeepSeek target: `https://api.deepseek.com/chat/completions`.

Qwen target: `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`.

Use 15-second connect behavior through fetch defaults plus an `AbortSignal.timeout(90_000)`. Map provider authentication and billing failures to generic `502 PROVIDER_UNAVAILABLE`; never reveal which key failed or include the upstream body.

- [ ] **Step 5: Add authenticated routes**

- `POST /v1/deepseek/chat/completions`
- `POST /v1/qwen/chat/completions` with required header `X-YouShu-Purpose: vision|speech`

- [ ] **Step 6: Run tests**

Run: `pnpm test`

Expected: response bodies, status codes, DeepSeek tool calls, and Qwen multimodal message arrays are preserved.

- [ ] **Step 7: Commit**

```bash
git add server/src/providers server/src/router.ts server/test/ai-proxy.test.ts
git commit -m "feat: proxy DeepSeek and Qwen requests"
```

### Task 4: Amap proxy routes

**Files:**
- Create: `server/src/providers/amap.ts`
- Modify: `server/src/router.ts`
- Test: `server/test/amap-proxy.test.ts`

**Interfaces:**
- Produces: `locateIp(input: IpLocationInput, config: ServerConfig, fetchImpl?: typeof fetch): Promise<Record<string, unknown>>`
- Produces: `geocode(input: GeocodeInput, config: ServerConfig, fetchImpl?: typeof fetch): Promise<Record<string, unknown>>`
- Produces: `reverseGeocode(input: ReverseGeocodeInput, config: ServerConfig, fetchImpl?: typeof fetch): Promise<Record<string, unknown>>`
- Produces: `weather(input: WeatherInput, config: ServerConfig, fetchImpl?: typeof fetch): Promise<Record<string, unknown>>`

- [ ] **Step 1: Write failing Amap validation tests**

```ts
test("reverse geocode rejects invalid coordinates", async () => {
  await assert.rejects(
    () => reverseGeocode({ longitude: 999, latitude: 999 }, config, neverFetch),
    /INVALID_REQUEST/
  );
});

test("weather adds the server key", async () => {
  const fetchSpy = recordingFetch(okResponse({ status: "1" }));
  await weather({ adcode: "330100", extensions: "all" }, config, fetchSpy);
  assert.equal(fetchSpy.lastUrl.searchParams.get("key"), config.amapWebApiKey);
});
```

- [ ] **Step 2: Run tests and confirm they fail**

Run: `pnpm test`

Expected: missing Amap provider module.

- [ ] **Step 3: Implement exact input schemas and fixed paths**

Validate longitude `[-180, 180]`, latitude `[-90, 90]`, adcode as six digits, city/address as 1-80 characters, and `extensions` as `base|all`. The routes map only to `/ip`, `/geocode/geo`, `/geocode/regeo`, and `/weather/weatherInfo` under the fixed Amap base URL.

- [ ] **Step 4: Add authenticated Amap routes**

- `POST /v1/amap/ip-location`
- `POST /v1/amap/geocode`
- `POST /v1/amap/reverse-geocode`
- `POST /v1/amap/weather`

- [ ] **Step 5: Run tests**

Run: `pnpm test`

Expected: validation, key injection, upstream error, and response pass-through tests pass.

- [ ] **Step 6: Commit**

```bash
git add server/src/providers/amap.ts server/src/router.ts server/test/amap-proxy.test.ts
git commit -m "feat: proxy Amap location and weather"
```

### Task 5: Function Compute deployment package and instructions

**Files:**
- Modify: `.gitignore`
- Create: `server/DEPLOY.md`
- Create: `server/scripts/package.ps1`
- Test: generated `server/deploy/youshu-api-proxy.zip` (ignored)

**Interfaces:**
- Consumes: compiled `server/dist/src/app.js` and empty-value `.env.example`.
- Produces: uploadable ZIP with startup command `node dist/src/app.js` and listening port `9000`.

- [ ] **Step 1: Add deployment artifact ignores**

Ignore:

```gitignore
/server/.env
/server/node_modules/
/server/dist/
/server/deploy/
```

- [ ] **Step 2: Add a packaging script**

The PowerShell script runs `pnpm install --frozen-lockfile`, `pnpm test`, creates `server/deploy`, and archives `dist`, `package.json`, and `pnpm-lock.yaml`. It verifies the archive does not contain `.env`, source maps, tests, or strings matching common provider-key prefixes.

- [ ] **Step 3: Write exact Function Compute settings**

Document:

- Region: `华东1（杭州）`
- Function type: Web function
- Runtime: custom runtime, Node.js 20-compatible
- Startup command: `node dist/src/app.js`
- Listening port: `9000`
- Timeout: `120` seconds
- Minimum instances: `0`
- Public HTTPS access: enabled
- Required environment variables from the spec
- No NAS, GPU, VPC, or provisioned instance

- [ ] **Step 4: Build and inspect the ZIP**

Run: `powershell -ExecutionPolicy Bypass -File server/scripts/package.ps1`

Expected: tests pass and `server/deploy/youshu-api-proxy.zip` contains no secret values.

- [ ] **Step 5: Commit**

```bash
git add .gitignore server/DEPLOY.md server/scripts/package.ps1 server/pnpm-lock.yaml
git commit -m "docs: add Function Compute deployment package"
```

### Task 6: Android backend session client

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/youshu/app/data/network/BackendApiException.kt`
- Create: `app/src/main/java/com/youshu/app/data/network/BackendApiClient.kt`
- Test: `app/src/test/java/com/youshu/app/data/network/BackendApiClientTest.kt`

**Interfaces:**
- Produces: `suspend fun postJson(path: String, body: String, purpose: String? = null): String`
- Produces: `suspend fun postJsonObject(path: String, body: JsonObject): JsonObject`
- Produces: `BackendApiException(code: String, safeMessage: String, retryable: Boolean)`
- Consumes: `BuildConfig.YOUSHU_BACKEND_BASE_URL`.

- [ ] **Step 1: Add MockWebServer and write failing session tests**

```kotlin
@Test
fun postJson_createsSessionAndRetriesOnceAfter401() = runTest {
    server.enqueue(jsonResponse("""{"token":"token-1","expiresAt":9999999999999}"""))
    server.enqueue(MockResponse().setResponseCode(401).setBody(expiredErrorJson))
    server.enqueue(jsonResponse("""{"token":"token-2","expiresAt":9999999999999}"""))
    server.enqueue(jsonResponse("""{"choices":[]}"""))

    val response = client.postJson("/v1/deepseek/chat/completions", "{}")

    assertEquals("{\"choices\":[]}", response)
}
```

- [ ] **Step 2: Run Android unit tests and confirm failure**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*BackendApiClientTest"`

Expected: missing backend client and MockWebServer dependency.

- [ ] **Step 3: Replace provider BuildConfig fields**

Remove all three `DEFAULT_*_API_KEY` fields and hardcoded key variables. Add:

```kotlin
buildConfigField(
    "String",
    "YOUSHU_BACKEND_BASE_URL",
    (localProperties.getProperty("youshu.backend.baseUrl")
        ?: "https://replace-after-deploy.invalid").toBuildConfigString()
)
```

- [ ] **Step 4: Implement installation identity, session cache, and JSON calls**

Generate one UUID in private SharedPreferences. Cache token and expiry. Create a session when missing or expired. Attach `Authorization: Bearer <token>`, `X-Request-Id`, and optional `X-YouShu-Purpose`. On one `401`, clear the token, create a new session, and retry exactly once.

- [ ] **Step 5: Implement stable error parsing**

Parse the server error shape. Convert offline and timeout failures to existing natural Chinese messages. Never include response bodies, tokens, or headers in thrown messages.

- [ ] **Step 6: Run Android unit tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*BackendApiClientTest"`

Expected: session creation, header attachment, one-time refresh, error parsing, and no-infinite-retry tests pass.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/youshu/app/data/network app/src/test/java/com/youshu/app/data/network
git commit -m "feat: add authenticated backend client"
```

### Task 7: Route DeepSeek and Qwen through the backend

**Files:**
- Modify: `app/src/main/java/com/youshu/app/data/agent/AgentClient.kt`
- Modify: `app/src/main/java/com/youshu/app/data/ai/AiInferenceRepository.kt`
- Modify: `app/src/main/java/com/youshu/app/data/repository/AiModelRepository.kt`
- Test: `app/src/test/java/com/youshu/app/data/network/BackendRoutingTest.kt`

**Interfaces:**
- Consumes: `BackendApiClient.postJson(...)` from Task 6.
- Preserves: existing `AgentClient.sendMessage`, `AgentClient.sendImageMessage`, `AiInferenceRepository.parseSearchQuery`, `recognizeImage`, `describeImageForAgent`, and `transcribeSpeech` signatures.

- [ ] **Step 1: Write failing routing tests**

Assert that agent and search-parsing bodies reach `/v1/deepseek/chat/completions`, image recognition uses `/v1/qwen/chat/completions` with purpose `vision`, and speech uses the Qwen route with purpose `speech`.

- [ ] **Step 2: Run tests and confirm failure**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*BackendRoutingTest"`

Expected: current classes still construct provider URLs and authorization headers.

- [ ] **Step 3: Inject `BackendApiClient` into `AgentClient`**

Remove the private OkHttp client, provider URL builder, API-key checks, and `Authorization` header. Keep request JSON construction, response parsing, local tool execution, and all public methods unchanged. Replace `executeApiCall(config, body)` with backend calls to `/v1/deepseek/chat/completions`.

- [ ] **Step 4: Inject `BackendApiClient` into `AiInferenceRepository`**

Keep image/audio encoding and JSON parsing on-device. Route calls by purpose:

- search parsing: DeepSeek route
- item photo recognition: `vision`
- agent image description: `vision`
- voice transcription: `speech`

Remove endpoint and API-key requirements. The server selects models.

- [ ] **Step 5: Remove BuildConfig key fallback from `AiModelRepository`**

Return DAO rows without injecting defaults. This repository remains temporarily for service labels and migration compatibility only.

- [ ] **Step 6: Run focused and full Android unit tests**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: all tests pass and existing agent/tool response parsing remains unchanged.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/youshu/app/data/agent/AgentClient.kt app/src/main/java/com/youshu/app/data/ai/AiInferenceRepository.kt app/src/main/java/com/youshu/app/data/repository/AiModelRepository.kt app/src/test
git commit -m "refactor: route AI requests through secure proxy"
```

### Task 8: Route Amap through the backend

**Files:**
- Modify: `app/src/main/java/com/youshu/app/data/agent/WeatherAgentTool.kt`
- Test: `app/src/test/java/com/youshu/app/data/agent/WeatherProxyParsingTest.kt`

**Interfaces:**
- Consumes: `BackendApiClient.postJsonObject(...)` from Task 6.
- Preserves: `suspend fun getWeatherContext(city: String, intent: String): String`.

- [ ] **Step 1: Extract and test weather JSON parsing**

Write tests for live and forecast Amap JSON, empty city fallback, and provider errors without requiring Android location services.

- [ ] **Step 2: Run tests and confirm failure**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*WeatherProxyParsingTest"`

Expected: parsing is private and requests still contain the Amap key.

- [ ] **Step 3: Replace direct Amap HTTP calls**

Keep Android permission checks and GPS acquisition local. Send coordinates, city text, adcode, and forecast mode as JSON to the four backend routes. Remove URL encoding, direct Amap URLs, key resolution, and provider authorization details.

- [ ] **Step 4: Preserve weather context formatting**

Keep the same human-readable context sent to DeepSeek, including current weather, today/tomorrow forecast, source, and recommendation instructions.

- [ ] **Step 5: Run tests**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: weather parsing and all existing tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/youshu/app/data/agent/WeatherAgentTool.kt app/src/test/java/com/youshu/app/data/agent/WeatherProxyParsingTest.kt
git commit -m "refactor: route weather through secure proxy"
```

### Task 9: Clear stored keys and replace the API-key settings UI

**Files:**
- Modify: `app/src/main/java/com/youshu/app/data/local/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/youshu/app/ui/screen/profile/ProfileScreen.kt`
- Modify: `app/src/main/java/com/youshu/app/ui/viewmodel/ProfileViewModel.kt`
- Test: `app/src/test/java/com/youshu/app/data/repository/AiModelRepositoryTest.kt`

**Interfaces:**
- Produces: Room migration `MIGRATION_9_10`.
- Preserves: service metadata rows for DeepSeek, Qwen, and Amap.

- [ ] **Step 1: Write a failing repository test**

Verify the repository never injects BuildConfig keys and returns blank `apiKey` values unchanged.

- [ ] **Step 2: Add Room migration 9 to 10**

Set database version to 10 and execute:

```sql
UPDATE ai_model_configs SET apiKey = '';
```

Register `MIGRATION_9_10` in `addMigrations`. Also clear keys in `PrepopulateCallback.onOpen` so malformed legacy rows cannot reintroduce them.

- [ ] **Step 3: Replace editable API-key management UI**

Rename the menu to `AI 服务状态`. Display fixed services with the text `已由服务器安全配置`. Remove add, edit, delete, endpoint, and API-key fields from the user-facing dialog. Keep a short explanation that keys are never stored on the phone.

- [ ] **Step 4: Remove unused ViewModel mutation functions if no longer referenced**

Keep only the model/service read flow required by the status dialog. Confirm `rg "addAiModel|updateAiModel|deleteAiModel" app/src/main` returns no UI references before deleting methods.

- [ ] **Step 5: Run tests and build**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug`

Expected: tests pass, Room schema compiles, and debug APK builds.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/youshu/app/data/local/database/AppDatabase.kt app/src/main/java/com/youshu/app/ui/screen/profile/ProfileScreen.kt app/src/main/java/com/youshu/app/ui/viewmodel/ProfileViewModel.kt app/src/test/java/com/youshu/app/data/repository/AiModelRepositoryTest.kt
git commit -m "fix: remove provider keys from device storage"
```

### Task 10: Security scan, documentation, and end-to-end build

**Files:**
- Modify: `README.md`
- Modify: `server/DEPLOY.md`
- Verify: `app/build/outputs/apk/debug/app-debug.apk`
- Verify: `server/deploy/youshu-api-proxy.zip`

**Interfaces:**
- Consumes: all previous tasks.
- Produces: deployable server ZIP and Android debug APK with no provider credentials.

- [ ] **Step 1: Document the public architecture**

Explain that contributors can run the server with their own untracked `.env`, while production secrets belong in Function Compute environment variables. Do not include example values resembling real keys.

- [ ] **Step 2: Run server verification**

Run from `server/`: `pnpm test`

Expected: all server tests pass.

- [ ] **Step 3: Run Android verification**

Run: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`

Expected: all unit tests pass and APK builds successfully.

- [ ] **Step 4: Scan tracked files and build outputs for secrets**

Search tracked files, server ZIP, generated BuildConfig, and APK strings for all revoked key values and common key assignments. The verification command must receive revoked key fingerprints from local untracked input so the values never appear in shell history, test files, or documentation.

Expected: zero matches for provider keys; provider names and public API hostnames may remain only in server source and documentation.

- [ ] **Step 5: Start the local proxy with non-production test credentials and run smoke checks**

Verify `/health`, session creation, unauthorized rejection, request-size rejection, and mocked provider routes. Real-provider tests run only after new keys are entered locally or in Function Compute.

- [ ] **Step 6: Create deployment artifacts**

Run: `powershell -ExecutionPolicy Bypass -File server/scripts/package.ps1`

Expected:

- `server/deploy/youshu-api-proxy.zip`
- `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 7: Commit final documentation**

```bash
git add README.md server/DEPLOY.md
git commit -m "docs: document secure proxy deployment"
```

- [ ] **Step 8: Cloud handoff checklist**

Do not commit or paste new keys into chat. In the Function Compute console, the user creates the Web function, uploads the ZIP, enters environment variables, and sends only the resulting public HTTPS base URL. Update `youshu.backend.baseUrl`, rebuild the APK, and run real end-to-end tests before revoking old keys.
