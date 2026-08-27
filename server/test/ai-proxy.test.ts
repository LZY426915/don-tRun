import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";

import { createServer } from "../src/app.js";
import { forwardDeepSeek } from "../src/providers/deepseek.js";
import { forwardQwen } from "../src/providers/qwen.js";
import { validConfig } from "./fixtures.js";
import { jsonResponse, recordingFetch } from "./fetch-fixtures.js";

const toolCallResponse = {
  choices: [
    {
      message: {
        role: "assistant",
        tool_calls: [{ id: "call-1", type: "function", function: { name: "list_items", arguments: "{}" } }]
      }
    }
  ]
};

test("DeepSeek proxy overwrites model and authorization while preserving tool calls", async () => {
  const config = validConfig();
  const recorder = recordingFetch(jsonResponse(toolCallResponse));
  const tools = [{ type: "function", function: { name: "list_items", parameters: { type: "object" } } }];

  const result = await forwardDeepSeek(
    { model: "attacker-model", messages: [], tools, stream: false, unapproved: "drop-me" },
    config,
    "req-1",
    recorder.fetch
  );

  assert.equal(recorder.lastUrl?.toString(), "https://api.deepseek.com/chat/completions");
  assert.equal(recorder.lastHeaders.authorization, `Bearer ${config.deepseekApiKey}`);
  assert.equal(recorder.lastHeaders["x-request-id"], "req-1");
  assert.equal(recorder.lastJson?.model, config.deepseekModel);
  assert.equal(recorder.lastJson?.stream, false);
  assert.deepEqual(recorder.lastJson?.thinking, { type: "disabled" });
  assert.deepEqual(recorder.lastJson?.tools, tools);
  assert.equal("unapproved" in (recorder.lastJson ?? {}), false);
  assert.deepEqual(result, { status: 200, body: toolCallResponse });
});

test("DeepSeek proxy rejects streaming and malformed messages", async () => {
  const neverFetch: typeof fetch = async () => {
    throw new Error("fetch must not run");
  };

  await assert.rejects(
    () => forwardDeepSeek({ messages: [], stream: true }, validConfig(), "req-2", neverFetch),
    /INVALID_REQUEST/
  );
  await assert.rejects(
    () => forwardDeepSeek({ messages: "not-an-array" }, validConfig(), "req-3", neverFetch),
    /INVALID_REQUEST/
  );
});

test("Qwen purpose selects only the configured model and preserves multimodal messages", async () => {
  const config = validConfig();
  const recorder = recordingFetch(jsonResponse({ choices: [] }));
  const messages = [
    {
      role: "user",
      content: [
        { type: "text", text: "describe" },
        { type: "image_url", image_url: { url: "data:image/jpeg;base64,dGVzdA==" } }
      ]
    }
  ];

  await forwardQwen(
    "vision",
    { model: "other", messages },
    config,
    "req-4",
    recorder.fetch
  );

  assert.equal(recorder.lastUrl?.toString(), "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions");
  assert.equal(recorder.lastJson?.model, config.qwenVisionModel);
  assert.deepEqual(recorder.lastJson?.messages, messages);
  assert.equal(recorder.lastJson?.enable_thinking, false);
});

test("Qwen speech purpose selects the speech model", async () => {
  const config = validConfig();
  const recorder = recordingFetch(jsonResponse({ choices: [] }));

  await forwardQwen("speech", { messages: [] }, config, "req-5", recorder.fetch);

  assert.equal(recorder.lastJson?.model, config.qwenSpeechModel);
  assert.equal("enable_thinking" in (recorder.lastJson ?? {}), false);
});

for (const expected of [
  { upstream: 400, status: 503, code: "PROVIDER_CONFIGURATION_ERROR", retryable: false },
  { upstream: 401, status: 503, code: "PROVIDER_CREDENTIALS_INVALID", retryable: false },
  { upstream: 403, status: 503, code: "PROVIDER_CREDENTIALS_INVALID", retryable: false },
  { upstream: 429, status: 429, code: "PROVIDER_RATE_LIMITED", retryable: true },
  { upstream: 500, status: 502, code: "PROVIDER_UNAVAILABLE", retryable: true }
]) {
  test(`provider status ${expected.upstream} is mapped to a safe error`, async () => {
    const recorder = recordingFetch(
      jsonResponse({ message: "sensitive upstream detail" }, expected.upstream)
    );

    await assert.rejects(
      () => forwardDeepSeek({ messages: [] }, validConfig(), "req-6", recorder.fetch),
      (error: unknown) => {
        assert.equal((error as { status: number }).status, expected.status);
        assert.equal((error as { code: string }).code, expected.code);
        assert.equal((error as { retryable: boolean }).retryable, expected.retryable);
        assert.equal(String(error).includes("sensitive upstream detail"), false);
        return true;
      }
    );
  });
}

test("authenticated AI routes use the fixed providers and require a Qwen purpose", async () => {
  const recorder = recordingFetch(jsonResponse({ choices: [] }));
  const server = createServer(validConfig(), { fetchImpl: recorder.fetch, logger: () => undefined });
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

    const deepseekResponse = await fetch(`${baseUrl}/v1/deepseek/chat/completions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ messages: [] })
    });
    assert.equal(deepseekResponse.status, 200);
    assert.equal(recorder.lastUrl?.hostname, "api.deepseek.com");

    const missingPurpose = await fetch(`${baseUrl}/v1/qwen/chat/completions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ messages: [] })
    });
    assert.equal(missingPurpose.status, 400);
    assert.equal(
      (await missingPurpose.json() as { error: { code: string } }).error.code,
      "INVALID_PURPOSE"
    );
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close((error?: Error) => (error ? reject(error) : resolve()));
    });
  }
});
