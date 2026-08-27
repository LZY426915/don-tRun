import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import test from "node:test";

import { createServer } from "../src/app.js";
import { validConfig } from "./fixtures.js";

test("authenticated DeepSeek route streams normalized SSE events", async () => {
  const providerBody = [
    'data: {"choices":[{"delta":{"content":"你"}}]}\n\n',
    'data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}\n\n',
    "data: [DONE]\n\n"
  ];
  await withServer(async () => providerSseResponse(providerBody), async ({ baseUrl, token }) => {
    const response = await postStream(baseUrl, token);
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /text\/event-stream/);
    assert.equal(response.headers.get("x-accel-buffering"), "no");
    const body = await response.text();
    assert.match(body, /event: text-delta\ndata: {"text":"你"}/);
    assert.match(body, /event: text-delta\ndata: {"text":"好"}/);
    assert.match(body, /event: done\ndata: {"finishReason":"stop"}/);
  });
});

test("streaming route retries one retryable provider response before output", async () => {
  let attempts = 0;
  const fetchImpl: typeof fetch = async () => {
    attempts += 1;
    if (attempts === 1) return new Response("busy", { status: 429 });
    return providerSseResponse([
      'data: {"choices":[{"delta":{"content":"恢复"},"finish_reason":"stop"}]}\n\n'
    ]);
  };

  await withServer(fetchImpl, async ({ baseUrl, token }) => {
    const response = await postStream(baseUrl, token);
    assert.equal(response.status, 200);
    assert.match(await response.text(), /恢复/);
    assert.equal(attempts, 2);
  });
});

test("streaming route does not retry after a visible delta", async () => {
  let attempts = 0;
  const fetchImpl: typeof fetch = async () => {
    attempts += 1;
    const encoder = new TextEncoder();
    return new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(
            encoder.encode('data: {"choices":[{"delta":{"content":"已输出"}}]}\n\n')
          );
          setTimeout(() => controller.error(new Error("provider disconnected")), 10);
        }
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } }
    );
  };

  await withServer(fetchImpl, async ({ baseUrl, token }) => {
    const response = await postStream(baseUrl, token);
    const body = await response.text();
    assert.match(body, /已输出/);
    assert.match(body, /event: error/);
    assert.equal(attempts, 1);
  });
});

test("closing the client stream aborts the provider request", async () => {
  let providerAborted = false;
  const fetchImpl: typeof fetch = async (_input, init) => {
    const signal = init?.signal;
    const encoder = new TextEncoder();
    return new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(
            encoder.encode('data: {"choices":[{"delta":{"content":"开始"}}]}\n\n')
          );
          signal?.addEventListener(
            "abort",
            () => {
              providerAborted = true;
              controller.error(new Error("aborted"));
            },
            { once: true }
          );
        }
      }),
      { status: 200, headers: { "Content-Type": "text/event-stream" } }
    );
  };

  await withServer(fetchImpl, async ({ baseUrl, token }) => {
    await cancelAfterFirstChunk(baseUrl, token);
    await waitFor(() => providerAborted);
    assert.equal(providerAborted, true);
  });
});

async function withServer(
  fetchImpl: typeof fetch,
  run: (context: { baseUrl: string; token: string }) => Promise<void>
): Promise<void> {
  const server = createServer(validConfig(), { fetchImpl, logger: () => undefined });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  const baseUrl = `http://127.0.0.1:${port}`;
  try {
    const sessionResponse = await fetch(`${baseUrl}/v1/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        installationId: "550e8400-e29b-41d4-a716-446655440000",
        appVersion: "1.2.0"
      })
    });
    const { token } = await sessionResponse.json() as { token: string };
    await run({ baseUrl, token });
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

function postStream(baseUrl: string, token: string): Promise<Response> {
  return fetch(`${baseUrl}/v1/deepseek/chat/completions`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      Accept: "text/event-stream"
    },
    body: JSON.stringify({ messages: [{ role: "user", content: "你好" }], stream: true })
  });
}

function providerSseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      }
    }),
    { status: 200, headers: { "Content-Type": "text/event-stream" } }
  );
}

async function cancelAfterFirstChunk(baseUrl: string, token: string): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const url = new URL(`${baseUrl}/v1/deepseek/chat/completions`);
    const request = http.request(
      {
        hostname: url.hostname,
        port: url.port,
        path: url.pathname,
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json"
        }
      },
      (response) => {
        response.once("data", () => {
          response.destroy();
          request.destroy();
          resolve();
        });
      }
    );
    request.once("error", (error) => {
      if ((error as NodeJS.ErrnoException).code === "ECONNRESET") resolve();
      else reject(error);
    });
    request.end(JSON.stringify({ messages: [], stream: true }));
  });
}

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 2_000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("Timed out waiting for condition");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
