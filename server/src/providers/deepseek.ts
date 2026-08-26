import type { ServerConfig } from "../config.js";
import { buildChatRequest, callJsonProvider, type ProviderResponse } from "./shared.js";

export async function forwardDeepSeek(
  body: unknown,
  config: ServerConfig,
  requestId: string,
  fetchImpl: typeof fetch = fetch
): Promise<ProviderResponse> {
  const requestBody = buildChatRequest(body, config.deepseekModel);
  return callJsonProvider(
    `${config.deepseekBaseUrl}/chat/completions`,
    config.deepseekApiKey,
    requestBody,
    requestId,
    fetchImpl
  );
}
