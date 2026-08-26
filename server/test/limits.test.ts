import assert from "node:assert/strict";
import test from "node:test";

import { createRateLimiter } from "../src/limits.js";

test("sliding-window limiter rejects the request after the configured maximum", () => {
  const limiter = createRateLimiter(100);
  const rule = { maxRequests: 2, windowMs: 1_000 };

  limiter.assertWithinLimit("install-1", rule, 1_000);
  limiter.assertWithinLimit("install-1", rule, 1_500);

  assert.throws(
    () => limiter.assertWithinLimit("install-1", rule, 1_999),
    /RATE_LIMITED/
  );
});

test("sliding-window limiter releases requests after the window", () => {
  const limiter = createRateLimiter(100);
  const rule = { maxRequests: 1, windowMs: 1_000 };

  limiter.assertWithinLimit("install-2", rule, 1_000);
  limiter.assertWithinLimit("install-2", rule, 2_001);
});

test("rate limiter bounds tracked keys", () => {
  const limiter = createRateLimiter(2);
  const rule = { maxRequests: 1, windowMs: 60_000 };

  limiter.assertWithinLimit("oldest", rule, 1_000);
  limiter.assertWithinLimit("middle", rule, 1_001);
  limiter.assertWithinLimit("newest", rule, 1_002);

  assert.equal(limiter.trackedKeyCount(), 2);
});
