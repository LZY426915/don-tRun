import assert from "node:assert/strict";
import test from "node:test";

import {
  encodeSseEvent,
  parseProviderSse,
  type YouShuStreamEvent
} from "../src/sse.js";

test("provider SSE survives UTF-8 chunk boundaries and ignores reasoning", async () => {
  const source = [
    'data: {"choices":[{"delta":{"content":"你","reasoning_content":"secret"}}]}\n\n',
    ': keep-alive\n\n',
    'data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}\n\n',
    "data: [DONE]\n\n"
  ].join("");
  const bytes = new TextEncoder().encode(source);
  const chineseByte = bytes.indexOf(0xe4);
  const events = await collectEvents(
    byteStream([
      bytes.slice(0, chineseByte + 1),
      bytes.slice(chineseByte + 1, chineseByte + 2),
      bytes.slice(chineseByte + 2)
    ])
  );

  assert.deepEqual(events, [
    { type: "text-delta", text: "你" },
    { type: "text-delta", text: "好" },
    { type: "done", finishReason: "stop" }
  ]);
  assert.equal(JSON.stringify(events).includes("secret"), false);
});

test("provider SSE joins repeated data lines and assembles tool fragments", async () => {
  const source = [
    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\n",
    "data: \"id\":\"call-1\",\"function\":{\"name\":\"list_\",\"arguments\":\"{\\\"q\\\":\"}}]}}]}\n\n",
    'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"items","arguments":"\\\"水\\\"}"}}]},"finish_reason":"tool_calls"}]}\n\n'
  ].join("");

  assert.deepEqual(await collectEvents(byteStream([new TextEncoder().encode(source)])), [
    {
      type: "tool-call-delta",
      index: 0,
      id: "call-1",
      name: "list_",
      arguments: '{"q":'
    },
    {
      type: "tool-call-delta",
      index: 0,
      name: "items",
      arguments: '"水"}'
    },
    { type: "done", finishReason: "tool_calls" }
  ]);
});

test("provider SSE flushes a final frame without a trailing blank line", async () => {
  const source = 'data: {"choices":[{"delta":{"content":"完成"},"finish_reason":null}]}'
  assert.deepEqual(await collectEvents(byteStream([new TextEncoder().encode(source)])), [
    { type: "text-delta", text: "完成" }
  ]);
});

test("normalized SSE encoder emits an event name and JSON payload", () => {
  assert.equal(
    encodeSseEvent({ type: "text-delta", text: "你好" }),
    'event: text-delta\ndata: {"text":"你好"}\n\n'
  );
  assert.equal(
    encodeSseEvent({ type: "done", finishReason: null }),
    'event: done\ndata: {"finishReason":null}\n\n'
  );
});

async function collectEvents(
  stream: ReadableStream<Uint8Array>
): Promise<YouShuStreamEvent[]> {
  const events: YouShuStreamEvent[] = [];
  for await (const event of parseProviderSse(stream)) {
    events.push(event);
  }
  return events;
}

function byteStream(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      controller.close();
    }
  });
}
