import { ApiError } from "./errors.js";

export interface RateLimitRule {
  maxRequests: number;
  windowMs: number;
}

export interface RateLimiter {
  assertWithinLimit(key: string, rule: RateLimitRule, now?: number): void;
  trackedKeyCount(): number;
}

export const RATE_LIMITS = {
  session: { maxRequests: 20, windowMs: 60 * 60 * 1_000 },
  text: { maxRequests: 30, windowMs: 60_000 },
  media: { maxRequests: 8, windowMs: 60_000 },
  amap: { maxRequests: 30, windowMs: 60_000 }
} as const satisfies Record<string, RateLimitRule>;

export function createRateLimiter(maxTrackedKeys = 10_000): RateLimiter {
  if (!Number.isInteger(maxTrackedKeys) || maxTrackedKeys < 1) {
    throw new Error("maxTrackedKeys must be a positive integer");
  }

  const buckets = new Map<string, number[]>();

  return {
    assertWithinLimit(key, rule, now = Date.now()): void {
      if (rule.maxRequests < 1 || rule.windowMs < 1) {
        throw new Error("Rate limit rules must contain positive values");
      }

      const cutoff = now - rule.windowMs;
      const timestamps = (buckets.get(key) ?? []).filter((timestamp) => timestamp > cutoff);
      if (timestamps.length >= rule.maxRequests) {
        throw new ApiError(
          429,
          "RATE_LIMITED",
          "Too many requests. Please try again later.",
          true
        );
      }

      timestamps.push(now);
      buckets.delete(key);
      buckets.set(key, timestamps);

      while (buckets.size > maxTrackedKeys) {
        const oldestKey = buckets.keys().next().value as string | undefined;
        if (!oldestKey) break;
        buckets.delete(oldestKey);
      }
    },

    trackedKeyCount(): number {
      return buckets.size;
    }
  };
}

const defaultLimiter = createRateLimiter();

export function assertWithinLimit(
  key: string,
  rule: RateLimitRule,
  now = Date.now()
): void {
  defaultLimiter.assertWithinLimit(key, rule, now);
}
