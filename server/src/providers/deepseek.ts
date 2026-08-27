import type { ServerConfig } from "../config.js";
import { ApiError } from "../errors.js";
import { providerErrorFor } from "./provider-errors.js";
import { buildChatRequest, callJsonProvider, type ProviderResponse } from "./shared.js";

export async function forwardDeepSeek(
  body: unknown,
  config: ServerConfig,
  requestId: string,
  fetchImpl: typeof fetch = fetch
): Promise<ProviderResponse> {
  const requestBody = buildChatRequest(body, config.deepseekModel, {
    stream: false,
    trustedOverrides: { thinking: { type: "disabled" } }
  });
  return callJsonProvider(
    `${config.deepseekBaseUrl}/chat/completions`,
    config.deepseekApiKey,
    requestBody,
    requestId,
    fetchImpl
  );
}

export async function openDeepSeekStream(
  body: unknown,
  config: ServerConfig,
  requestId: string,
  signal: AbortSignal,
  fetchImpl: typeof fetch = fetch
): Promise<Response> {
  const requestBody = buildChatRequest(body, config.deepseekModel, {
    stream: true,
    trustedOverrides: { thinking: { type: "disabled" } }
  });
  let response: Response;
  try {
    response = await fetchImpl(`${config.deepseekBaseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${config.deepseekApiKey}`,
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        "X-Request-Id": requestId
      },
      body: JSON.stringify(requestBody),
      signal: AbortSignal.any([signal, AbortSignal.timeout(90_000)])
    });
  } catch (error) {
    if (signal.aborted) throw error;
    throw new ApiError(
      502,
      "PROVIDER_UNAVAILABLE",
      "AI 服务暂时不可用，请稍后重试。",
      true
    );
  }
  if (!response.ok) throw providerErrorFor(response.status);
  if (!response.body) {
    throw new ApiError(
      502,
      "PROVIDER_INVALID_RESPONSE",
      "AI 服务返回了无法识别的响应。",
      true
    );
  }
  return response;
}
