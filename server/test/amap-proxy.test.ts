import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import test from "node:test";

import { createServer } from "../src/app.js";
import {
  geocode,
  locateIp,
  reverseGeocode,
  weather
} from "../src/providers/amap.js";
import { validConfig } from "./fixtures.js";
import { jsonResponse, recordingFetch } from "./fetch-fixtures.js";

const neverFetch: typeof fetch = async () => {
  throw new Error("fetch must not run");
};

test("reverse geocode rejects coordinates outside the valid ranges", async () => {
  await assert.rejects(
    () => reverseGeocode({ longitude: 999, latitude: 999 }, validConfig(), neverFetch),
    /INVALID_REQUEST/
  );
});

test("weather validates adcode and adds only the server key", async () => {
  const config = validConfig();
  const recorder = recordingFetch(jsonResponse({ status: "1", forecasts: [] }));

  await assert.rejects(
    () => weather({ adcode: "not-an-adcode", extensions: "all" }, config, neverFetch),
    /INVALID_REQUEST/
  );
  const result = await weather(
    { adcode: "330100", extensions: "all" },
    config,
    recorder.fetch
  );

  assert.equal(recorder.lastUrl?.pathname, "/v3/weather/weatherInfo");
  assert.equal(recorder.lastUrl?.searchParams.get("key"), config.amapWebApiKey);
  assert.equal(recorder.lastUrl?.searchParams.get("city"), "330100");
  assert.equal(recorder.lastUrl?.searchParams.get("extensions"), "all");
  assert.deepEqual(result, { status: "1", forecasts: [] });
});

test("geocode keeps the fixed host and safely encodes city and address", async () => {
  const recorder = recordingFetch(jsonResponse({ status: "1", geocodes: [] }));

  await geocode(
    { city: "南京市", address: "大光路 10 号&key=attacker" },
    validConfig(),
    recorder.fetch
  );

  assert.equal(recorder.lastUrl?.origin, "https://restapi.amap.com");
  assert.equal(recorder.lastUrl?.pathname, "/v3/geocode/geo");
  assert.equal(recorder.lastUrl?.searchParams.get("city"), "南京市");
  assert.equal(recorder.lastUrl?.searchParams.get("address"), "大光路 10 号&key=attacker");
  assert.equal(recorder.lastUrl?.searchParams.getAll("key").length, 1);
});

test("IP location rejects invalid IP values", async () => {
  await assert.rejects(
    () => locateIp({ ip: "not-an-ip" }, validConfig(), neverFetch),
    /INVALID_REQUEST/
  );
});

test("Amap business failures are converted to a safe provider error", async () => {
  const recorder = recordingFetch(
    jsonResponse({ status: "0", info: "sensitive provider detail", infocode: "10001" })
  );

  await assert.rejects(
    () => weather({ adcode: "330100" }, validConfig(), recorder.fetch),
    (error: unknown) => {
      assert.equal((error as { status: number }).status, 502);
      assert.equal((error as { code: string }).code, "PROVIDER_UNAVAILABLE");
      assert.equal(String(error).includes("sensitive provider detail"), false);
      return true;
    }
  );
});

test("authenticated Amap route passes through a successful response", async () => {
  const providerBody = { status: "1", lives: [{ city: "南京市", weather: "晴" }] };
  const recorder = recordingFetch(jsonResponse(providerBody));
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

    const response = await fetch(`${baseUrl}/v1/amap/weather`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({ adcode: "320100", extensions: "base" })
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), providerBody);
    assert.equal(recorder.lastUrl?.pathname, "/v3/weather/weatherInfo");
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close((error?: Error) => (error ? reject(error) : resolve()));
    });
  }
});
