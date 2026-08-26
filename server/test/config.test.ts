import assert from "node:assert/strict";
import test from "node:test";

import { loadConfig } from "../src/config.js";
import { validEnvironment } from "./fixtures.js";

test("loadConfig rejects a missing provider secret", () => {
  const env = validEnvironment();
  delete env.DEEPSEEK_API_KEY;

  assert.throws(() => loadConfig(env), /DEEPSEEK_API_KEY/);
});

test("loadConfig rejects a short session signing secret", () => {
  const env = validEnvironment();
  env.SESSION_SIGNING_SECRET = "too-short";

  assert.throws(() => loadConfig(env), /SESSION_SIGNING_SECRET/);
});

test("loadConfig fixes provider URLs instead of reading environment overrides", () => {
  const env = validEnvironment();
  env.DEEPSEEK_BASE_URL = "https://attacker.invalid";
  env.QWEN_BASE_URL = "https://attacker.invalid";

  const config = loadConfig(env);

  assert.equal(config.deepseekBaseUrl, "https://api.deepseek.com");
  assert.equal(
    config.qwenBaseUrl,
    "https://dashscope.aliyuncs.com/compatible-mode/v1"
  );
  assert.equal(config.amapBaseUrl, "https://restapi.amap.com/v3");
});

test("loadConfig trims and deduplicates allowed app versions", () => {
  const env = validEnvironment();
  env.ALLOWED_APP_VERSIONS = " 1.2.0,1.2.1,1.2.0 ";

  const config = loadConfig(env);

  assert.deepEqual([...config.allowedAppVersions], ["1.2.0", "1.2.1"]);
});
