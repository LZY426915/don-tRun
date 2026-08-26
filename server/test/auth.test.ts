import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";

import { issueSession, verifySession } from "../src/auth.js";
import { createServer } from "../src/app.js";
import { validConfig } from "./fixtures.js";

const installationId = "550e8400-e29b-41d4-a716-446655440000";

test("issued sessions contain a hash instead of the installation ID", () => {
  const config = validConfig();
  const issued = issueSession(installationId, "1.2.0", config, 1_000);

  const claims = verifySession(issued.token, config, 2_000);

  assert.notEqual(claims.installationHash, installationId);
  assert.match(claims.installationHash, /^[a-f0-9]{64}$/);
  assert.equal(claims.appVersion, "1.2.0");
  assert.equal(claims.issuedAt, 1_000);
  assert.equal(claims.expiresAt, 86_401_000);
});

test("session expires after 24 hours", () => {
  const config = validConfig();
  const issued = issueSession(installationId, "1.2.0", config, 1_000);

  assert.throws(
    () => verifySession(issued.token, config, 86_401_001),
    /expired/i
  );
});

test("tampered session token is rejected", () => {
  const config = validConfig();
  const issued = issueSession(installationId, "1.2.0", config, 1_000);

  assert.throws(
    () => verifySession(`${issued.token}x`, config, 2_000),
    /signature/i
  );
});

test("session rejects app versions outside the allowlist", () => {
  assert.throws(
    () => issueSession(installationId, "9.9.9", validConfig(), 1_000),
    /APP_VERSION_NOT_ALLOWED/
  );
});

test("POST /v1/session issues a token and protected routes require it", async () => {
  const server = createServer(validConfig());
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  const baseUrl = `http://127.0.0.1:${port}`;

  try {
    const unauthorized = await fetch(`${baseUrl}/v1/unknown`, { method: "POST" });
    assert.equal(unauthorized.status, 401);
    assert.equal((await unauthorized.json() as { error: { code: string } }).error.code, "UNAUTHORIZED");

    const sessionResponse = await fetch(`${baseUrl}/v1/session`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ installationId, appVersion: "1.2.0" })
    });
    assert.equal(sessionResponse.status, 200);
    const session = await sessionResponse.json() as { token: string; expiresAt: number };
    assert.ok(session.token);
    assert.ok(session.expiresAt > Date.now());

    const authenticated = await fetch(`${baseUrl}/v1/unknown`, {
      method: "POST",
      headers: { Authorization: `Bearer ${session.token}` }
    });
    assert.equal(authenticated.status, 404);
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close((error?: Error) => (error ? reject(error) : resolve()));
    });
  }
});
