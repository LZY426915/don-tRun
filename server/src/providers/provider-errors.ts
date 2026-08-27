import { ApiError } from "../errors.js";

export function providerErrorFor(status: number): ApiError {
  if (status === 400) {
    return new ApiError(
      503,
      "PROVIDER_CONFIGURATION_ERROR",
      "AI 服务配置需要更新。",
      false
    );
  }
  if (status === 401 || status === 403) {
    return new ApiError(
      503,
      "PROVIDER_CREDENTIALS_INVALID",
      "AI 服务凭据无效。",
      false
    );
  }
  if (status === 429) {
    return new ApiError(
      429,
      "PROVIDER_RATE_LIMITED",
      "AI 服务繁忙，请稍后重试。",
      true
    );
  }
  return new ApiError(
    502,
    "PROVIDER_UNAVAILABLE",
    "AI 服务暂时不可用，请稍后重试。",
    status >= 500
  );
}
