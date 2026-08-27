import { ApiError } from "../errors.js";
import { providerErrorFor } from "./provider-errors.js";

export interface ProviderResponse {
  status: number;
  body: unknown;
}

const ALLOWED_CHAT_FIELDS = [
  "messages",
  "tools",
  "tool_choice",
  "temperature",
  "top_p",
  "max_tokens"
] as const;

export interface ChatRequestOptions {
  stream: boolean;
  trustedOverrides?: Record<string, unknown>;
}

export function buildChatRequest(
  body: unknown,
  model: string,
  options: ChatRequestOptions = { stream: false }
): Record<string, unknown> {
  if (!isRecord(body) || !Array.isArray(body.messages)) {
    throw invalidRequest("messages must be an array.");
  }
  if (body.stream !== undefined && body.stream !== options.stream) {
    throw invalidRequest(`stream must be ${options.stream}.`);
  }
  if (!body.messages.every(isRecord)) {
    throw invalidRequest("Each message must be an object.");
  }
  if (body.tools !== undefined && !Array.isArray(body.tools)) {
    throw invalidRequest("tools must be an array.");
  }
  if (
    body.tool_choice !== undefined &&
    typeof body.tool_choice !== "string" &&
    !isRecord(body.tool_choice)
  ) {
    throw invalidRequest("tool_choice must be a string or object.");
  }
  for (const field of ["temperature", "top_p"] as const) {
    const value = body[field];
    if (value !== undefined && (typeof value !== "number" || !Number.isFinite(value))) {
      throw invalidRequest(`${field} must be a finite number.`);
    }
  }
  if (
    body.max_tokens !== undefined &&
    (typeof body.max_tokens !== "number" ||
      !Number.isInteger(body.max_tokens) ||
      body.max_tokens < 1)
  ) {
    throw invalidRequest("max_tokens must be a positive integer.");
  }

  const accepted: Record<string, unknown> = {};
  for (const field of ALLOWED_CHAT_FIELDS) {
    if (body[field] !== undefined) {
      accepted[field] = cloneJson(body[field]);
    }
  }
  accepted.model = model;
  accepted.stream = options.stream;
  for (const [key, value] of Object.entries(options.trustedOverrides ?? {})) {
    accepted[key] = cloneJson(value);
  }
  return accepted;
}

export async function callJsonProvider(
  url: string,
  apiKey: string,
  body: Record<string, unknown>,
  requestId: string,
  fetchImpl: typeof fetch
): Promise<ProviderResponse> {
  let response: Response;
  try {
    response = await fetchImpl(url, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
        "X-Request-Id": requestId
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(90_000)
    });
  } catch {
    throw new ApiError(
      502,
      "PROVIDER_UNAVAILABLE",
      "The AI service is temporarily unavailable.",
      true
    );
  }

  if (!response.ok) {
    throw providerErrorFor(response.status);
  }

  try {
    return { status: response.status, body: await response.json() };
  } catch {
    throw new ApiError(
      502,
      "PROVIDER_INVALID_RESPONSE",
      "The AI service returned an invalid response.",
      true
    );
  }
}

function cloneJson(value: unknown): unknown {
  try {
    return JSON.parse(JSON.stringify(value)) as unknown;
  } catch {
    throw invalidRequest("The request contains invalid JSON values.");
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalidRequest(detail: string): ApiError {
  return new ApiError(400, "INVALID_REQUEST", `Invalid AI request: ${detail}`);
}
