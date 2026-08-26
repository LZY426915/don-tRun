import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";

import { createServer } from "../src/app.js";
import { validConfig } from "./fixtures.js";

async function withServer(
  run: (baseUrl: string) => Promise<void>
): Promise<void> {
  const server = createServer(validConfig());
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address() as AddressInfo;

  try {
    await run(`http://127.0.0.1:${address.port}`);
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close((error?: Error) => (error ? reject(error) : resolve()));
    });
  }
}

test("GET /health returns only service status", async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/health`);

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: "ok" });
  });
});

test("unknown routes return the stable error contract", async () => {
  await withServer(async (baseUrl) => {
    const response = await fetch(`${baseUrl}/missing`, {
      headers: { "X-Request-Id": "request-404" }
    });

    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), {
      error: {
        code: "NOT_FOUND",
        message: "The requested route was not found.",
        requestId: "request-404",
        retryable: false
      }
    });
  });
});
