# Streaming Agent And Current Model Compatibility

## Context

The Android agent currently waits for a complete JSON response before showing any
assistant text. The secure Function Compute proxy also rejects `stream: true` and
buffers every provider response. This creates a long silent wait and prevents the
chat experience from matching modern mobile AI clients.

The configured DeepSeek model name also needs to move away from the retired
`deepseek-chat` alias. The Qwen vision path can use a newer multimodal model, but
both providers need explicit non-thinking behavior for the app's existing local
tool loop and structured image extraction.

Alibaba Cloud Function Compute Web Functions support SSE when the response uses
chunked transfer encoding:
https://help.aliyun.com/zh/functioncompute/fc/does-function-compute-support-sse-streaming-response

## Goals

- Stream ordinary DeepSeek replies into the Android chat bubble as text arrives.
- Preserve local inventory and weather tool calling across multiple model rounds.
- Stream the final natural-language answer after tool execution.
- Let the user stop generation while preserving text already received.
- Cancel the upstream provider request when the Android request is cancelled.
- Use current, stronger provider models without exposing API keys in the APK.
- Keep image recognition and speech recognition on non-streaming endpoints.

## Non-Goals

- Displaying DeepSeek reasoning content or raw function-call arguments.
- Streaming Qwen image recognition or speech transcription.
- Resuming an interrupted response from the exact provider token boundary.
- Adding WebSocket infrastructure or server-side conversation storage.

## Model Configuration

The initial deployment uses:

- `DEEPSEEK_MODEL=deepseek-v4-pro`
- `QWEN_VISION_MODEL=qwen3.7-plus`
- `QWEN_SPEECH_MODEL=qwen3-asr-flash`

DeepSeek requests explicitly set `thinking.type` to `disabled`. Qwen vision
requests explicitly set `enable_thinking` to `false`. This preserves the stronger
base models while avoiding reasoning-content replay requirements, tool-choice
compatibility problems, and non-JSON visual responses.

These controls are enforced by the proxy rather than accepted from Android, so a
modified client cannot enable expensive thinking behavior through the public
endpoint.

## Streaming Protocol

The existing DeepSeek path remains:

`POST /v1/deepseek/chat/completions`

Requests with `stream: false` continue to return JSON for compatibility. Requests
with `stream: true` return `text/event-stream` and use normalized YouShu events:

```text
event: text-delta
data: {"text":"partial text"}

event: tool-call-delta
data: {"index":0,"id":"...","name":"...","arguments":"..."}

event: done
data: {"finishReason":"stop"}
```

If a failure occurs after streaming headers were sent, the proxy emits a sanitized
event and closes the stream:

```text
event: error
data: {"code":"PROVIDER_STREAM_ERROR","message":"AI service interrupted.","retryable":true}
```

Provider payloads, API keys, reasoning content, prompts, images, and tool results
are never written to server logs. Log entries retain only route, status, latency,
payload size, request ID, and an installation hash.

## Server Flow

1. Authenticate the existing short-lived app session and apply the existing rate
   limit before opening a provider request.
2. Validate the client body and allow `stream` only as a boolean.
3. Override the model and thinking settings with server configuration.
4. For non-streaming calls, retain the existing JSON forwarding behavior.
5. For streaming calls, request OpenAI-compatible SSE from DeepSeek, parse provider
   chunks, and emit only normalized text and tool-call deltas.
6. Send heartbeat comments during long gaps so mobile and intermediary connections
   do not treat an active model request as idle.
7. When the Android connection closes, abort the provider fetch immediately.

The Web Function response sets `Content-Type: text/event-stream`,
`Cache-Control: no-cache`, and chunked transfer encoding. No response compression
is applied because buffering would defeat token-by-token delivery.

## Android Network Flow

`BackendApiClient` gains a streaming POST operation that returns a Kotlin `Flow` of
typed backend stream events. It keeps the existing session creation and one-time
401 refresh behavior before consuming the response body. Once stream consumption
starts, errors are surfaced as stream events rather than retrying a partially shown
answer.

Cancelling collection cancels the OkHttp call. The client parser supports SSE
frames split across arbitrary network buffers, multiple data lines, heartbeats,
and a final frame without an extra trailing newline.

## Agent Flow

`AgentClient` exposes a streaming reply flow rather than returning only a final
string.

- Ordinary conversation streams text deltas immediately.
- Requests identified as inventory, weather, or mutation work buffer model output
  while tool-call deltas are assembled.
- Local tools execute only after a complete, valid tool call is received.
- Tool results are sent back to DeepSeek with mutation tools excluded from the
  follow-up round, preventing duplicate writes.
- The final natural-language response streams to the UI.
- If a model unexpectedly begins a tool call after visible text, the agent emits a
  reset event before continuing so raw pre-tool wording is not left in the bubble.
- A verified deterministic tool result remains available as a fallback if the
  final wording request fails after the local operation has succeeded.

Tool execution itself is not rolled back when the user stops after an operation
has already completed. The final UI message must truthfully say that generation
was stopped after the operation, rather than implying the operation was cancelled.

## View Model And UI

When sending a message, `AgentChatViewModel` immediately inserts one assistant
message with `LOADING` status. Each text delta updates that same message instead of
adding new messages. The conversation list remains stable and Compose redraws only
the changing bubble.

While generation is active:

- The send control becomes a stop icon with an accessible description.
- Pressing it cancels the generation coroutine.
- Existing partial text is retained and marked `STOPPED`.
- If no text arrived, the placeholder becomes a concise stopped message.
- The input becomes available again immediately after cancellation.

Chat history is persisted when the response completes, fails, or is stopped, not
on every token. This avoids excessive SharedPreferences writes.

## Error Handling

- Failure before any text: show the existing friendly error message and `ERROR`
  status.
- Failure after partial text: retain text, append a short interruption indicator,
  and mark `ERROR` so the user can retry.
- User cancellation: retain text and mark `STOPPED`, without presenting it as an
  error.
- Invalid SSE or malformed tool arguments: cancel the provider stream and return a
  sanitized retryable error.
- Provider rate limit or outage before headers: preserve the existing HTTP error
  mapping.

## Testing

Server tests cover:

- DeepSeek V4 model and non-thinking overrides.
- Qwen vision model and non-thinking overrides.
- Normalized text, tool-call, done, heartbeat, and error SSE events.
- Provider cancellation when the client disconnects.
- Non-streaming compatibility and redacted logging.

Android tests cover:

- SSE frames split at arbitrary byte boundaries.
- Multi-line data, heartbeat, done, and error frames.
- Text accumulation, reset behavior, and tool-call assembly.
- Stop-generation cancellation and partial-message preservation.
- Final history persistence without per-token writes.

The final verification runs all server tests, the Android unit tests, the server
build, the Android debug build, and a secret-pattern scan before producing the new
Function Compute ZIP and APK.
