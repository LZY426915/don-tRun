import { ApiError } from "../errors.js";
import type { ServerConfig } from "../config.js";
import { buildChatRequest, callJsonProvider, type ProviderResponse } from "./shared.js";
import { providerErrorFor } from "./provider-errors.js";

export type QwenPurpose = "vision" | "speech";

export const QWEN_REALTIME_MODEL = "qwen3-asr-flash-realtime";
const QWEN_TOKEN_URL = "https://dashscope.aliyuncs.com/api/v1/tokens?expire_in_seconds=180";
const QWEN_REALTIME_WS_URL =
  `wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=${QWEN_REALTIME_MODEL}`;

export interface QwenRealtimeTokenResponse {
  token: string;
  expiresAt: number;
  model: string;
  websocketUrl: string;
}

export async function forwardQwen(
  purpose: QwenPurpose,
  body: unknown,
  config: ServerConfig,
  requestId: string,
  fetchImpl: typeof fetch = fetch
): Promise<ProviderResponse> {
  const model = purpose === "vision" ? config.qwenVisionModel : config.qwenSpeechModel;
  const requestBody = buildChatRequest(
    body,
    model,
    purpose === "vision"
      ? { stream: false, trustedOverrides: { enable_thinking: false } }
      : { stream: false }
  );
  return callJsonProvider(
    `${config.qwenBaseUrl}/chat/completions`,
    config.qwenApiKey,
    requestBody,
    requestId,
    fetchImpl
  );
}

/**
 * Mints a short-lived token for the untrusted Android client. The permanent
 * Qwen key never leaves this server.
 */
export async function issueQwenRealtimeToken(
  config: ServerConfig,
  requestId: string,
  fetchImpl: typeof fetch = fetch
): Promise<QwenRealtimeTokenResponse> {
  let response: Response;
  try {
    response = await fetchImpl(QWEN_TOKEN_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.qwenApiKey}`,
        "X-Request-Id": requestId
      },
      signal: AbortSignal.timeout(15_000)
    });
  } catch {
    throw new ApiError(
      502,
      "PROVIDER_UNAVAILABLE",
      "语音服务暂时不可用，请稍后重试。",
      true
    );
  }

  if (!response.ok) {
    throw providerErrorFor(response.status);
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError(
      502,
      "PROVIDER_INVALID_RESPONSE",
      "语音服务返回的数据格式异常。",
      true
    );
  }

  if (!isRecord(payload) || typeof payload.token !== "string" || payload.token.trim() === "") {
    throw new ApiError(
      502,
      "PROVIDER_INVALID_RESPONSE",
      "语音服务没有返回有效的临时凭据。",
      true
    );
  }
  const expiresAt = payload.expires_at;
  if (typeof expiresAt !== "number" || !Number.isFinite(expiresAt) || expiresAt <= 0) {
    throw new ApiError(
      502,
      "PROVIDER_INVALID_RESPONSE",
      "语音服务返回的临时凭据已失效。",
      true
    );
  }

  return {
    token: payload.token.trim(),
    expiresAt,
    model: QWEN_REALTIME_MODEL,
    websocketUrl: QWEN_REALTIME_WS_URL
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
