import type { IncomingMessage, ServerResponse } from "node:http";
import { createHash } from "node:crypto";
import { isIP } from "node:net";

import { issueSession, verifySession } from "./auth.js";
import type { ServerConfig } from "./config.js";
import { ApiError, toPublicError } from "./errors.js";
import {
  bearerTokenFor,
  notFound,
  readJsonBody,
  requestIdFor,
  sendError,
  sendJson,
  sendSseHeaders
} from "./http.js";
import { createRateLimiter, RATE_LIMITS } from "./limits.js";
import { forwardDeepSeek, openDeepSeekStream } from "./providers/deepseek.js";
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
import { encodeSseEvent, parseProviderSse } from "./sse.js";

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
        if (isRecord(parsed.value) && parsed.value.stream === true) {
          await streamDeepSeekResponse(
            request,
            response,
            parsed.value,
            deps.config,
            requestId,
            fetchImpl
          );
          return;
        }
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
      if (!response.destroyed && !response.headersSent) {
        sendError(response, error, requestId);
      }
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

async function streamDeepSeekResponse(
  request: IncomingMessage,
  response: ServerResponse,
  body: Record<string, unknown>,
  config: ServerConfig,
  requestId: string,
  fetchImpl: typeof fetch
): Promise<void> {
  const clientAbort = new AbortController();
  const abortUpstream = () => clientAbort.abort();
  response.once("close", abortUpstream);
  let heartbeat: NodeJS.Timeout | undefined;

  try {
    const providerResponse = await openStreamWithRetry(
      body,
      config,
      requestId,
      clientAbort.signal,
      fetchImpl
    );
    sendSseHeaders(response);
    heartbeat = setInterval(() => {
      if (!response.destroyed && !response.writableEnded) response.write(": keep-alive\n\n");
    }, 15_000);

    try {
      for await (const event of parseProviderSse(providerResponse.body!)) {
        if (clientAbort.signal.aborted || response.destroyed) return;
        response.write(encodeSseEvent(event));
      }
      if (!response.writableEnded && !response.destroyed) response.end();
    } catch (error) {
      if (clientAbort.signal.aborted || response.destroyed) return;
      const publicError = toPublicError(error, requestId).body.error;
      response.write(encodeSseEvent({ type: "error", ...publicError }));
      response.end();
    }
  } finally {
    if (heartbeat) clearInterval(heartbeat);
    response.off("close", abortUpstream);
    clientAbort.abort();
  }
}

async function openStreamWithRetry(
  body: Record<string, unknown>,
  config: ServerConfig,
  requestId: string,
  signal: AbortSignal,
  fetchImpl: typeof fetch
): Promise<Response> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      return await openDeepSeekStream(body, config, requestId, signal, fetchImpl);
    } catch (error) {
      if (signal.aborted || attempt === 1 || !(error instanceof ApiError) || !error.retryable) {
        throw error;
      }
      await abortableDelay(250, signal);
    }
  }
  throw new ApiError(502, "PROVIDER_UNAVAILABLE", "AI 服务暂时不可用，请稍后重试。", true);
}

async function abortableDelay(milliseconds: number, signal: AbortSignal): Promise<void> {
  if (signal.aborted) throw signal.reason;
  await new Promise<void>((resolve, reject) => {
    const finish = () => {
      signal.removeEventListener("abort", cancel);
      resolve();
    };
    const cancel = () => {
      clearTimeout(timeout);
      reject(signal.reason);
    };
    const timeout = setTimeout(finish, milliseconds);
    signal.addEventListener("abort", cancel, { once: true });
  });
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
