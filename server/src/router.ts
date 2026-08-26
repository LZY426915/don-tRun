import type { IncomingMessage, ServerResponse } from "node:http";
import { createHash } from "node:crypto";
import { isIP } from "node:net";

import { issueSession, verifySession } from "./auth.js";
import type { ServerConfig } from "./config.js";
import { ApiError } from "./errors.js";
import {
  bearerTokenFor,
  notFound,
  readJsonBody,
  requestIdFor,
  sendError,
  sendJson
} from "./http.js";
import { createRateLimiter, RATE_LIMITS } from "./limits.js";
import { forwardDeepSeek } from "./providers/deepseek.js";
import { forwardQwen, type QwenPurpose } from "./providers/qwen.js";
import {
  geocode,
  locateIp,
  reverseGeocode,
  weather,
  type GeocodeInput,
  type IpLocationInput,
  type ReverseGeocodeInput,
  type WeatherInput
} from "./providers/amap.js";

export interface RequestLogEntry {
  requestId: string;
  route: string;
  status: number;
  latencyMs: number;
  payloadSize: number;
  installationHash: string | null;
}

export interface RouterDependencies {
  config: ServerConfig;
  logger?: (entry: RequestLogEntry) => void;
  fetchImpl?: typeof fetch;
}

export function createRouter(
  deps: RouterDependencies
): (request: IncomingMessage, response: ServerResponse) => Promise<void> {
  const limiter = createRateLimiter();
  const logger = deps.logger ?? ((entry: RequestLogEntry) => process.stdout.write(`${JSON.stringify(entry)}\n`));
  const fetchImpl = deps.fetchImpl ?? fetch;

  return async (request, response) => {
    const requestId = requestIdFor(request);
    const startedAt = Date.now();
    const path = new URL(request.url ?? "/", "http://localhost").pathname;
    let payloadSize = 0;
    let installationHash: string | null = null;

    try {
      if (request.method === "GET" && path === "/health") {
        sendJson(response, 200, { status: "ok" });
        return;
      }

      if (request.method === "POST" && path === "/v1/session") {
        const parsed = await readJsonBody(request, 4_096);
        payloadSize = parsed.byteLength;
        if (!isRecord(parsed.value)) {
          throw invalidSessionRequest();
        }
        const installationId = parsed.value.installationId;
        const appVersion = parsed.value.appVersion;
        if (typeof installationId !== "string" || typeof appVersion !== "string") {
          throw invalidSessionRequest();
        }

        const ipHash = hashClientAddress(request, deps.config.sessionSigningSecret);
        limiter.assertWithinLimit(`session:ip:${ipHash}`, RATE_LIMITS.session);
        limiter.assertWithinLimit(`session:install:${installationId}`, RATE_LIMITS.session);
        const session = issueSession(installationId, appVersion, deps.config);
        installationHash = verifySession(session.token, deps.config).installationHash;
        sendJson(response, 200, session);
        return;
      }

      if (path.startsWith("/v1/")) {
        const token = bearerTokenFor(request);
        installationHash = verifySession(token, deps.config).installationHash;
      }

      if (
        request.method === "POST" &&
        path === "/v1/deepseek/chat/completions" &&
        installationHash
      ) {
        limiter.assertWithinLimit(`text:${installationHash}`, RATE_LIMITS.text);
        const parsed = await readJsonBody(request, 1024 * 1024);
        payloadSize = parsed.byteLength;
        const providerResponse = await forwardDeepSeek(
          parsed.value,
          deps.config,
          requestId,
          fetchImpl
        );
        sendJson(response, providerResponse.status, providerResponse.body);
        return;
      }

      if (
        request.method === "POST" &&
        path === "/v1/qwen/chat/completions" &&
        installationHash
      ) {
        const purpose = qwenPurposeFor(request);
        limiter.assertWithinLimit(`media:${installationHash}`, RATE_LIMITS.media);
        const maxBytes = purpose === "vision" ? 12 * 1024 * 1024 : 25 * 1024 * 1024;
        const parsed = await readJsonBody(request, maxBytes);
        payloadSize = parsed.byteLength;
        const providerResponse = await forwardQwen(
          purpose,
          parsed.value,
          deps.config,
          requestId,
          fetchImpl
        );
        sendJson(response, providerResponse.status, providerResponse.body);
        return;
      }

      if (
        request.method === "POST" &&
        path.startsWith("/v1/amap/") &&
        installationHash
      ) {
        limiter.assertWithinLimit(`amap:${installationHash}`, RATE_LIMITS.amap);
        const parsed = await readJsonBody(request, 16 * 1024);
        payloadSize = parsed.byteLength;
        if (!isRecord(parsed.value)) {
          throw new ApiError(400, "INVALID_REQUEST", "The request body must be an object.");
        }

        const result = await dispatchAmapRoute(
          path,
          parsed.value,
          request,
          deps.config,
          fetchImpl
        );
        sendJson(response, 200, result);
        return;
      }

      throw notFound();
    } catch (error) {
      sendError(response, error, requestId);
    } finally {
      logger({
        requestId,
        route: path,
        status: response.statusCode,
        latencyMs: Date.now() - startedAt,
        payloadSize,
        installationHash
      });
    }
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalidSessionRequest(): ApiError {
  return new ApiError(400, "INVALID_REQUEST", "Installation ID and app version are required.");
}

function hashClientAddress(request: IncomingMessage, secret: string): string {
  const forwarded = request.headers["x-forwarded-for"];
  const address =
    typeof forwarded === "string"
      ? forwarded.split(",", 1)[0]?.trim() ?? "unknown"
      : request.socket.remoteAddress ?? "unknown";
  return createHash("sha256").update(`${secret}:${address}`).digest("hex");
}

function qwenPurposeFor(request: IncomingMessage): QwenPurpose {
  const purpose = request.headers["x-youshu-purpose"];
  if (purpose !== "vision" && purpose !== "speech") {
    throw new ApiError(
      400,
      "INVALID_PURPOSE",
      "X-YouShu-Purpose must be vision or speech."
    );
  }
  return purpose;
}

async function dispatchAmapRoute(
  path: string,
  body: Record<string, unknown>,
  request: IncomingMessage,
  config: ServerConfig,
  fetchImpl: typeof fetch
): Promise<Record<string, unknown>> {
  switch (path) {
    case "/v1/amap/ip-location": {
      const input: IpLocationInput = {};
      if (typeof body.ip === "string") {
        input.ip = body.ip;
      } else {
        const clientIp = clientIpFor(request);
        if (clientIp) input.ip = clientIp;
      }
      return locateIp(input, config, fetchImpl);
    }
    case "/v1/amap/geocode":
      return geocode(body as unknown as GeocodeInput, config, fetchImpl);
    case "/v1/amap/reverse-geocode":
      return reverseGeocode(body as unknown as ReverseGeocodeInput, config, fetchImpl);
    case "/v1/amap/weather":
      return weather(body as unknown as WeatherInput, config, fetchImpl);
    default:
      throw notFound();
  }
}

function clientIpFor(request: IncomingMessage): string | undefined {
  const forwarded = request.headers["x-forwarded-for"];
  const candidate =
    typeof forwarded === "string"
      ? forwarded.split(",", 1)[0]?.trim()
      : request.socket.remoteAddress;
  return candidate && isIP(candidate) !== 0 ? candidate : undefined;
}
