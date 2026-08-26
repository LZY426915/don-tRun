import type { ServerConfig } from "../config.js";
import { buildChatRequest, callJsonProvider, type ProviderResponse } from "./shared.js";

export type QwenPurpose = "vision" | "speech";

export async function forwardQwen(
  purpose: QwenPurpose,
  body: unknown,
  config: ServerConfig,
  requestId: string,
  fetchImpl: typeof fetch = fetch
): Promise<ProviderResponse> {
  const model = purpose === "vision" ? config.qwenVisionModel : config.qwenSpeechModel;
  const requestBody = buildChatRequest(body, model);
  return callJsonProvider(
    `${config.qwenBaseUrl}/chat/completions`,
    config.qwenApiKey,
    requestBody,
    requestId,
    fetchImpl
  );
}
