# Streaming Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stream DeepSeek replies into the Android agent chat with stop-generation support, stable local tool calling, current provider models, and actionable sanitized errors.

**Architecture:** The Function Compute Web Function converts OpenAI-compatible DeepSeek SSE into a small YouShu SSE protocol and keeps Qwen media calls non-streaming. Android parses that protocol into a `Flow`, the agent buffers tool rounds but streams the final answer, and the view model updates one persistent assistant message until completion, failure, or cancellation.

**Tech Stack:** Node.js 20, TypeScript, Node HTTP server, Fetch/Web Streams, Kotlin 2.x, coroutines `Flow`, OkHttp 4.12, Jetpack Compose, JUnit 4, MockWebServer.

**Spec:** `docs/superpowers/specs/2026-08-27-streaming-agent-design.md`

## Global Constraints

- Keep DeepSeek, Qwen, and Amap API keys only in Function Compute environment variables.
- Use `DEEPSEEK_MODEL=deepseek-v4-pro`, `QWEN_VISION_MODEL=qwen3.7-plus`, and `QWEN_SPEECH_MODEL=qwen3-asr-flash`.
- Force DeepSeek `thinking.type=disabled` and Qwen vision `enable_thinking=false` at the proxy boundary.
- Do not log prompts, replies, tool arguments/results, images, audio, location, authorization headers, provider bodies, or API keys.
- Preserve non-streaming JSON behavior for Qwen vision/speech and existing DeepSeek callers during migration.
- Do not show raw reasoning content or tool-call arguments in the Android UI.
- Execute local mutation tools at most once per user turn.
- Persist chat history only when a reply completes, fails, or is stopped, not for each token.
- Use test-driven development for every behavior change.

---

## File Structure

### Server

- Create `server/src/sse.ts`: parse provider SSE frames and encode normalized YouShu SSE events.
- Create `server/src/providers/provider-errors.ts`: map upstream status codes to sanitized stable errors.
- Modify `server/src/providers/shared.ts`: accept explicit streaming mode and trusted provider overrides.
- Modify `server/src/providers/deepseek.ts`: open DeepSeek V4 non-thinking JSON or SSE requests.
- Modify `server/src/providers/qwen.ts`: force non-thinking Qwen vision requests.
- Modify `server/src/http.ts`: send SSE headers and normalized frames without compression or content length.
- Modify `server/src/router.ts`: stream DeepSeek responses, heartbeat idle connections, and abort upstream fetches on disconnect.
- Modify `server/test/ai-proxy.test.ts`: provider model, thinking, and safe-error coverage.
- Create `server/test/sse.test.ts`: parser/encoder unit coverage.
- Create `server/test/streaming-proxy.test.ts`: authenticated route, chunk delivery, and cancellation coverage.

### Android

- Create `app/src/main/java/com/youshu/app/data/network/BackendStreamEvent.kt`: typed normalized backend stream events.
- Create `app/src/main/java/com/youshu/app/data/network/BackendSseReader.kt`: strict line-oriented SSE parser.
- Modify `app/src/main/java/com/youshu/app/data/network/BackendApiClient.kt`: authenticated `postSse` flow and cancellation.
- Create `app/src/main/java/com/youshu/app/data/agent/AgentReplyEvent.kt`: UI-facing append/reset/completion events.
- Create `app/src/main/java/com/youshu/app/data/agent/AgentIntentRouter.kt`: distinguish general conversation, ambiguous tool use, and required app-data operations.
- Create `app/src/main/java/com/youshu/app/data/agent/AgentStreamAssembler.kt`: accumulate text and fragmented tool calls for one provider round.
- Modify `app/src/main/java/com/youshu/app/data/agent/AgentClient.kt`: streaming tool loop and final answer flow.
- Create `app/src/main/java/com/youshu/app/ui/viewmodel/StreamingMessageReducer.kt`: pure assistant-message state transitions.
- Modify `app/src/main/java/com/youshu/app/data/agent/ChatHistoryService.kt`: add `STOPPED` message status.
- Modify `app/src/main/java/com/youshu/app/ui/viewmodel/AgentChatViewModel.kt`: one in-flight generation job, token updates, cancellation, and final persistence.
- Modify `app/src/main/java/com/youshu/app/ui/screen/agent/AgentChatScreen.kt`: stop icon and stopped/interrupted presentation.
- Extend existing Android JVM tests and add focused parser, intent, assembler, and reducer tests.

---

### Task 1: Provider Models, Non-Thinking Overrides, And Safe Errors

**Files:**
- Create: `server/src/providers/provider-errors.ts`
- Modify: `server/src/providers/shared.ts`
- Modify: `server/src/providers/deepseek.ts`
- Modify: `server/src/providers/qwen.ts`
- Modify: `server/test/ai-proxy.test.ts`
- Modify: `server/test/fixtures.ts`

**Interfaces:**
- Produces: `providerErrorFor(status: number): ApiError`
- Produces: `buildChatRequest(body, model, options): Record<string, unknown>` where `options` is `{ stream: boolean; trustedOverrides?: Record<string, unknown> }`.
- Preserves: `forwardDeepSeek(...)` and `forwardQwen(...)` non-streaming return types.

- [ ] **Step 1: Update fixture model names and write failing provider tests**

Add assertions equivalent to:

```ts
assert.equal(config.deepseekModel, "deepseek-v4-pro");
assert.equal(config.qwenVisionModel, "qwen3.7-plus");
assert.deepEqual(recorder.lastJson?.thinking, { type: "disabled" });
assert.equal(recorder.lastJson?.enable_thinking, false);
```

Add status mapping cases for 400, 401, 403, 429, and 500 without asserting any upstream response body.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
cd server
pnpm run build
node --test dist/test/ai-proxy.test.js
```

Expected: FAIL because trusted overrides and provider-specific error codes do not exist.

- [ ] **Step 3: Implement trusted overrides and safe error mapping**

Use these exact stable mappings:

```ts
export function providerErrorFor(status: number): ApiError {
  if (status === 400) return new ApiError(503, "PROVIDER_CONFIGURATION_ERROR", "AI 服务配置需要更新。", false);
  if (status === 401 || status === 403) return new ApiError(503, "PROVIDER_CREDENTIALS_INVALID", "AI 服务凭据无效。", false);
  if (status === 429) return new ApiError(429, "PROVIDER_RATE_LIMITED", "AI 服务繁忙，请稍后重试。", true);
  return new ApiError(502, "PROVIDER_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试。", status >= 500);
}
```

DeepSeek applies:

```ts
trustedOverrides: { thinking: { type: "disabled" } }
```

Qwen vision applies:

```ts
trustedOverrides: { enable_thinking: false }
```

Qwen speech does not add `enable_thinking`.

- [ ] **Step 4: Run server tests and verify GREEN**

Run `pnpm test` from `server`.

Expected: all existing and new tests pass with no upstream details in errors.

- [ ] **Step 5: Commit**

```powershell
git add server/src/providers server/test
git commit -m "fix: support current non-thinking provider models"
```

---

### Task 2: Server SSE Parsing And Normalized Events

**Files:**
- Create: `server/src/sse.ts`
- Create: `server/test/sse.test.ts`

**Interfaces:**
- Produces: `parseProviderSse(stream: ReadableStream<Uint8Array>): AsyncGenerator<YouShuStreamEvent>`.
- Produces: `encodeSseEvent(event: YouShuStreamEvent): string`.
- Produces event union:

```ts
export type YouShuStreamEvent =
  | { type: "text-delta"; text: string }
  | { type: "tool-call-delta"; index: number; id?: string; name?: string; arguments?: string }
  | { type: "done"; finishReason: string | null }
  | { type: "error"; code: string; message: string; retryable: boolean; requestId: string };
```

- [ ] **Step 1: Write failing parser and encoder tests**

Cover: an SSE frame split inside UTF-8 Chinese text, multiple `data:` lines, heartbeat comments, `[DONE]`, `delta.content`, fragmented `delta.tool_calls`, and a final frame without a trailing blank line.

Example assertion:

```ts
assert.deepEqual(events, [
  { type: "text-delta", text: "你" },
  { type: "text-delta", text: "好" },
  { type: "done", finishReason: "stop" }
]);
```

- [ ] **Step 2: Run the focused test and verify RED**

Run these commands from `server`:

```powershell
pnpm run build
node --test dist/test/sse.test.js
```

Expected: FAIL because `server/src/sse.ts` does not exist.

- [ ] **Step 3: Implement incremental SSE decoding**

Use `TextDecoder` with `{ stream: true }`, retain an incomplete line between chunks, join repeated `data:` fields with `\n`, ignore comment lines beginning with `:`, parse OpenAI chunks, and never emit `reasoning_content`.

Encode normalized events as:

```ts
return `event: ${event.type}\ndata: ${JSON.stringify(payload)}\n\n`;
```

- [ ] **Step 4: Run the focused and full server test suites**

Run `pnpm test`.

Expected: parser tests and the unchanged proxy tests pass.

- [ ] **Step 5: Commit**

```powershell
git add server/src/sse.ts server/test/sse.test.ts
git commit -m "feat: normalize provider SSE events"
```

---

### Task 3: Function Compute Streaming Route And Cancellation

**Files:**
- Modify: `server/src/providers/shared.ts`
- Modify: `server/src/providers/deepseek.ts`
- Modify: `server/src/http.ts`
- Modify: `server/src/router.ts`
- Create: `server/test/streaming-proxy.test.ts`
- Modify: `server/test/fetch-fixtures.ts`

**Interfaces:**
- Produces: `openDeepSeekStream(body, config, requestId, signal, fetchImpl): Promise<Response>`.
- Produces: `sendSseHeaders(response: ServerResponse): void`.
- Consumes: `parseProviderSse` and `encodeSseEvent` from Task 2.

- [ ] **Step 1: Write failing authenticated streaming-route tests**

The integration test must create a session, POST `stream: true`, then assert:

```ts
assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);
const body = await response.text();
assert.match(body, /event: text-delta/);
assert.match(body, /event: done/);
```

Add a second test whose client closes early and whose fake provider signal observes `aborted === true`. Add a third test where the first provider attempt returns 429 or 500 before any downstream event and the second attempt streams successfully; assert exactly two attempts. A failure after a text delta must not retry.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
pnpm run build
node --test dist/test/streaming-proxy.test.js
```

Expected: FAIL because streaming is still rejected.

- [ ] **Step 3: Implement streaming request forwarding**

Set upstream `stream: true`, combine the client abort signal with the 90-second timeout using `AbortSignal.any`, and validate non-2xx status before sending downstream SSE headers.

Retry once after 250 ms only when the first attempt fails before downstream SSE headers with a retryable fetch error, 429, or 5xx response. Never retry after any downstream text/tool event because that could duplicate visible text or local operations.

Set downstream headers:

```ts
response.writeHead(200, {
  "Content-Type": "text/event-stream; charset=utf-8",
  "Cache-Control": "no-cache, no-transform",
  "Transfer-Encoding": "chunked",
  "X-Accel-Buffering": "no"
});
```

Write `: keep-alive\n\n` every 15 seconds while no provider event is emitted. Clear the interval in `finally`. On `request.close`, abort the upstream request. Once headers are sent, convert failures to a normalized sanitized `error` event rather than JSON.

- [ ] **Step 4: Verify cancellation, redacted logs, and JSON compatibility**

Run `pnpm test`.

Expected: streaming integration tests pass; existing non-streaming tests still pass; logger assertions contain no prompt or provider body.

- [ ] **Step 5: Commit**

```powershell
git add server/src server/test
git commit -m "feat: stream DeepSeek responses through Function Compute"
```

---

### Task 4: Android SSE Client

**Files:**
- Create: `app/src/main/java/com/youshu/app/data/network/BackendStreamEvent.kt`
- Create: `app/src/main/java/com/youshu/app/data/network/BackendSseReader.kt`
- Modify: `app/src/main/java/com/youshu/app/data/network/BackendApiClient.kt`
- Modify: `app/src/test/java/com/youshu/app/data/network/BackendApiClientTest.kt`
- Create: `app/src/test/java/com/youshu/app/data/network/BackendSseReaderTest.kt`

**Interfaces:**
- Produces: `fun postSse(path: String, body: String): Flow<BackendStreamEvent>`.
- Produces:

```kotlin
sealed interface BackendStreamEvent {
    data class TextDelta(val text: String) : BackendStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?
    ) : BackendStreamEvent
    data class Done(val finishReason: String?) : BackendStreamEvent
}
```

Stream `error` frames throw `BackendApiException` with the provided sanitized code, message, retryable flag, and request ID.

- [ ] **Step 1: Write failing line-parser tests**

Test comments, blank-frame delimiters, repeated `data:` lines, all event types, malformed JSON, and EOF after a final nonblank line.

- [ ] **Step 2: Run parser tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.youshu.app.data.network.BackendSseReaderTest"
```

Expected: FAIL because the parser does not exist.

- [ ] **Step 3: Implement the strict parser**

Read via Okio `BufferedSource.readUtf8Line()`, accumulate `event` plus all `data` lines until a blank line, ignore comments, decode JSON with the existing `Json { ignoreUnknownKeys = true }`, and reject unknown event names as `INVALID_RESPONSE`.

- [ ] **Step 4: Write failing authenticated stream and cancellation tests**

Use `MockWebServer` throttled chunked bodies and assert the exact event list. Start collection in a coroutine, cancel after the first text delta, and assert collection returns promptly without receiving later deltas or `Done`.

- [ ] **Step 5: Implement `BackendApiClient.postSse`**

Build the same headers as `postJson`, add `Accept: text/event-stream`, refresh one expired session before stream consumption, expose body events through `flow {}` plus `flowOn(Dispatchers.IO)`, and close the OkHttp response in `finally`. Coroutine cancellation must invoke `Call.cancel()`.

- [ ] **Step 6: Run Android network tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.youshu.app.data.network.*"
```

Expected: all backend JSON and SSE tests pass.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/youshu/app/data/network app/src/test/java/com/youshu/app/data/network
git commit -m "feat: add authenticated Android SSE client"
```

---

### Task 5: Agent Intent Routing And Streaming Tool Rounds

**Files:**
- Create: `app/src/main/java/com/youshu/app/data/agent/AgentReplyEvent.kt`
- Create: `app/src/main/java/com/youshu/app/data/agent/AgentIntentRouter.kt`
- Create: `app/src/main/java/com/youshu/app/data/agent/AgentStreamAssembler.kt`
- Modify: `app/src/main/java/com/youshu/app/data/agent/AgentClient.kt`
- Create: `app/src/test/java/com/youshu/app/data/agent/AgentIntentRouterTest.kt`
- Create: `app/src/test/java/com/youshu/app/data/agent/AgentStreamAssemblerTest.kt`

**Interfaces:**
- Produces:

```kotlin
sealed interface AgentReplyEvent {
    data class AppendText(val text: String) : AgentReplyEvent
    data object ResetText : AgentReplyEvent
    data object Completed : AgentReplyEvent
}

enum class AgentRoute { GENERAL, TOOL_AUTO, TOOL_REQUIRED }

data class AgentRound(
    val text: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?
)

internal data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)
```

- Produces: `fun streamMessage(history: List<ChatMessage>, newMessage: String): Flow<AgentReplyEvent>`.
- Retains temporarily: existing `sendMessage(...)` as `streamMessage(...).toFinalText()` for callers not yet migrated in this task.

- [ ] **Step 1: Write failing intent tests**

Required assertions:

```kotlin
assertEquals(GENERAL, router.route("说谎者悖论到底矛盾在哪里？"))
assertEquals(GENERAL, router.route("忒修斯之船还是原来的船吗？"))
assertEquals(GENERAL, router.route("全能者能创造自己举不起的石头吗？"))
assertEquals(TOOL_AUTO, router.route("矿泉水在哪儿？"))
assertEquals(TOOL_REQUIRED, router.route("把农夫山泉标记成用完"))
assertEquals(TOOL_REQUIRED, router.route("明天天气怎么样？"))
```

- [ ] **Step 2: Run intent tests and verify RED**

Run the single test class with `:app:testDebugUnitTest`.

Expected: FAIL because `AgentIntentRouter` does not exist.

- [ ] **Step 3: Implement high-confidence routing**

General questions never force a tool. Inventory-like queries use `TOOL_AUTO`. Only compound domain intent, explicit weather, or explicit mutation of an item/category/location uses `TOOL_REQUIRED`. Do not classify from one generic verb or interrogative alone.

- [ ] **Step 4: Write failing fragmented tool-call assembly tests**

Feed deltas where the function name and JSON arguments arrive in multiple fragments, then assert one complete `ToolCall`. Also verify text concatenation and reset signaling when a tool call follows visible text.

- [ ] **Step 5: Implement `AgentStreamAssembler`**

Move the existing private `ToolCall` out of `AgentClient` into `AgentStreamAssembler.kt`. Store tool calls by `index`, append `id`, `name`, and `arguments` fragments in arrival order, and expose one immutable `AgentRound` on `Done`.

- [ ] **Step 6: Write failing streaming agent-loop tests around extracted pure helpers**

Test these transitions without Room or Android context:

- General reply emits text immediately and completes without a tool.
- Required tool round buffers text, assembles the call, executes exactly once, then streams the final round.
- An unexpected tool call after visible text emits `ResetText`.
- A mutation follow-up excludes mutation tools and cannot execute the same call twice.
- A failed final wording round emits the verified deterministic tool fallback.

- [ ] **Step 7: Implement the streaming tool loop**

Build DeepSeek bodies with `stream: true`. For general and `TOOL_AUTO`, begin with tools in auto mode; for `TOOL_REQUIRED`, apply the existing narrow tool selection and buffer until the round ends. After tool execution, append assistant/tool messages and stream a final no-mutation-tools round. Do not convert absence of tool calls into `ChatMessageStatus.ERROR` for general or auto routes.

- [ ] **Step 8: Run all agent tests and verify GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.youshu.app.data.agent.*"
```

Expected: paradox prompts remain normal chat, natural inventory phrasing still selects tools, and fragmented calls assemble deterministically.

- [ ] **Step 9: Commit**

```powershell
git add app/src/main/java/com/youshu/app/data/agent app/src/test/java/com/youshu/app/data/agent
git commit -m "feat: stream agent replies across local tool rounds"
```

---

### Task 6: View Model Message Updates And Stop Generation

**Files:**
- Modify: `app/src/main/java/com/youshu/app/data/agent/ChatHistoryService.kt`
- Create: `app/src/main/java/com/youshu/app/ui/viewmodel/StreamingMessageReducer.kt`
- Modify: `app/src/main/java/com/youshu/app/ui/viewmodel/AgentChatViewModel.kt`
- Create: `app/src/test/java/com/youshu/app/ui/viewmodel/StreamingMessageReducerTest.kt`

**Interfaces:**
- Adds: `ChatMessageStatus.STOPPED`.
- Produces:

```kotlin
object StreamingMessageReducer {
    fun append(message: ChatMessage, delta: String): ChatMessage
    fun reset(message: ChatMessage): ChatMessage
    fun complete(message: ChatMessage): ChatMessage
    fun stop(message: ChatMessage): ChatMessage
    fun fail(message: ChatMessage, safeMessage: String): ChatMessage
}
```

- Adds: `fun stopGenerating()` to `AgentChatViewModel`.

- [ ] **Step 1: Write failing reducer tests**

Verify append preserves message ID, reset clears only content, completion maps `LOADING` to `NORMAL`, stop preserves partial text and uses `STOPPED`, empty stop uses `已停止生成`, and failure preserves partial content plus a concise interruption line.

- [ ] **Step 2: Run reducer tests and verify RED**

Run the focused JVM test class.

Expected: FAIL because `STOPPED` and the reducer do not exist.

- [ ] **Step 3: Implement reducer and stopped status**

All functions return `message.copy(...)`; no Android framework dependency is allowed in the reducer.

- [ ] **Step 4: Integrate one in-flight generation job**

Add:

```kotlin
private var generationJob: Job? = null

fun stopGenerating() {
    generationJob?.cancel(CancellationException("Stopped by user"))
}
```

On submit, append the user message and one assistant `LOADING` placeholder before collection. Replace that placeholder by ID for each event. In `finally`, set `_isReplying` false. Save the conversation only after completion, error, or cancellation. Reuse the same collection helper after Qwen image description completes.

- [ ] **Step 5: Verify cancellation semantics manually at the reducer boundary and with coroutine tests**

Use `runTest` to cancel a flow after two deltas and assert that the final message contains those two deltas with `STOPPED` status and that no later delta is applied.

- [ ] **Step 6: Run all Android unit tests**

Run `.\gradlew.bat :app:testDebugUnitTest`.

Expected: all repository, backend, agent, reducer, and existing tests pass.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/youshu/app/data/agent/ChatHistoryService.kt app/src/main/java/com/youshu/app/ui/viewmodel app/src/test/java/com/youshu/app/ui/viewmodel
git commit -m "feat: retain and stop streaming chat replies"
```

---

### Task 7: Compose Stop Control And Streaming Presentation

**Files:**
- Modify: `app/src/main/java/com/youshu/app/ui/screen/agent/AgentChatScreen.kt`

**Interfaces:**
- `AgentInputBar` consumes `isReplying: Boolean`, `onSend: () -> Unit`, and `onStop: () -> Unit`.
- The screen calls `viewModel.stopGenerating()` when the stop icon is pressed.

- [ ] **Step 1: Add the stop callback to the composable contract**

When `isReplying` is true, replace the send action with `Icons.Rounded.StopCircle`, content description `停止生成`, and a fixed-size icon button so the input layout does not shift.

- [ ] **Step 2: Present stopped and interrupted states without red styling**

`STOPPED` uses the normal assistant bubble plus a small secondary `已停止` label. Only `ERROR` uses the existing red border/text treatment. `LOADING` with empty content keeps the current progress indicator; once content arrives, the indicator must not cover text.

- [ ] **Step 3: Build and inspect Compose compilation**

Run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL with no missing callback or exhaustive `when` errors.

- [ ] **Step 4: Commit**

```powershell
git add app/src/main/java/com/youshu/app/ui/screen/agent/AgentChatScreen.kt
git commit -m "feat: add stop control to streaming chat"
```

---

### Task 8: Deployment Package, Documentation, And Full Verification

**Files:**
- Modify: `server/DEPLOY.md`
- Generate ignored artifact: `server/youshu-api-proxy.zip`
- Generate artifact after deployment URL is known: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**
- Function startup remains `node dist/src/app.js` on port `9000`.
- Health check remains `GET /health` returning `{"status":"ok"}`.

- [ ] **Step 1: Update deployment documentation**

Document the three current model environment values, Web Function SSE requirement, 120-second timeout, port 9000, public HTTP trigger, and `/health` verification. State that old exposed keys must be revoked and never appear in screenshots or Git history.

- [ ] **Step 2: Run fresh full verification**

Run:

```powershell
cd server
pnpm test
pnpm run build
cd ..
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: server tests report zero failures, TypeScript build exits 0, Android unit tests pass, and debug APK build exits 0.

- [ ] **Step 3: Run a current-tree secret scan**

Run:

```powershell
rg -n --hidden -g '!**/.git/**' -g '!**/build/**' -g '!server/dist/**' 'sk-[A-Za-z0-9_-]{16,}|DEEPSEEK_API_KEY\s*=\s*[^<\s]|QWEN_API_KEY\s*=\s*[^<\s]|AMAP_WEB_API_KEY\s*=\s*[^<\s]' .
```

Expected: no plaintext credential matches.

- [ ] **Step 4: Rebuild the Function Compute ZIP**

Run:

```powershell
Remove-Item -LiteralPath 'server\youshu-api-proxy.zip' -ErrorAction SilentlyContinue
Compress-Archive -Path 'server\package.json','server\dist' -DestinationPath 'server\youshu-api-proxy.zip'
tar -tf 'server\youshu-api-proxy.zip'
```

Expected: archive contains `package.json` and `dist/src/app.js`; it contains no `.env`, `local.properties`, source maps with secrets, or Android files.

- [ ] **Step 5: Commit documentation and report the deployment handoff**

```powershell
git add server/DEPLOY.md
git commit -m "docs: update streaming proxy deployment"
```

Report the new ZIP path, the exact environment variable names and non-secret model values, and instruct the user to replace the previously selected ZIP before creating the function.
