import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";

import { ApiError, toPublicError } from "./errors.js";

export interface ParsedJsonBody {
  value: unknown;
  byteLength: number;
}

export function requestIdFor(request: IncomingMessage): string {
  const supplied = request.headers["x-request-id"];
  if (typeof supplied === "string" && /^[A-Za-z0-9._:-]{1,128}$/.test(supplied)) {
    return supplied;
  }
  return randomUUID();
}

export function sendJson(
  response: ServerResponse,
  status: number,
  body: unknown
): void {
  const json = JSON.stringify(body);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(json),
    "Cache-Control": "no-store"
  });
  response.end(json);
}

export function sendSseHeaders(response: ServerResponse): void {
  response.writeHead(200, {
    "Content-Type": "text/event-stream; charset=utf-8",
    "Cache-Control": "no-cache, no-transform",
    "Transfer-Encoding": "chunked",
    "X-Accel-Buffering": "no"
  });
  response.flushHeaders();
}

export function sendError(
  response: ServerResponse,
  error: unknown,
  requestId: string
): void {
  const publicError = toPublicError(error, requestId);
  sendJson(response, publicError.status, publicError.body);
}

export function notFound(): ApiError {
  return new ApiError(404, "NOT_FOUND", "The requested route was not found.");
}

export async function readJsonBody(
  request: IncomingMessage,
  maxBytes: number
): Promise<ParsedJsonBody> {
  const declaredLength = Number.parseInt(request.headers["content-length"] ?? "0", 10);
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    throw new ApiError(413, "PAYLOAD_TOO_LARGE", "The request body is too large.");
  }

  const chunks: Buffer[] = [];
  let byteLength = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    byteLength += buffer.length;
    if (byteLength > maxBytes) {
      throw new ApiError(413, "PAYLOAD_TOO_LARGE", "The request body is too large.");
    }
    chunks.push(buffer);
  }

  try {
    return {
      value: JSON.parse(Buffer.concat(chunks).toString("utf8")),
      byteLength
    };
  } catch {
    throw new ApiError(400, "INVALID_JSON", "The request body must be valid JSON.");
  }
}

export function bearerTokenFor(request: IncomingMessage): string {
  const authorization = request.headers.authorization;
  const match = typeof authorization === "string" ? /^Bearer\s+(.+)$/i.exec(authorization) : null;
  if (!match?.[1]) {
    throw new ApiError(401, "UNAUTHORIZED", "A valid session token is required.");
  }
  return match[1];
}
