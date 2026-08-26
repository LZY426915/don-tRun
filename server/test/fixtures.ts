import type { ServerConfig } from "../src/config.js";

export function validEnvironment(): NodeJS.ProcessEnv {
  return {
    DEEPSEEK_API_KEY: "test-deepseek-key",
    QWEN_API_KEY: "test-qwen-key",
    AMAP_WEB_API_KEY: "test-amap-key",
    SESSION_SIGNING_SECRET: "test-session-signing-secret-at-least-32-characters",
    DEEPSEEK_MODEL: "test-deepseek-model",
    QWEN_VISION_MODEL: "test-qwen-vision-model",
    QWEN_SPEECH_MODEL: "test-qwen-speech-model",
    ALLOWED_APP_VERSIONS: "1.2.0,1.2.1"
  };
}

export function validConfig(): ServerConfig {
  return {
    deepseekApiKey: "test-deepseek-key",
    qwenApiKey: "test-qwen-key",
    amapWebApiKey: "test-amap-key",
    sessionSigningSecret: "test-session-signing-secret-at-least-32-characters",
    deepseekModel: "test-deepseek-model",
    qwenVisionModel: "test-qwen-vision-model",
    qwenSpeechModel: "test-qwen-speech-model",
    allowedAppVersions: new Set(["1.2.0", "1.2.1"]),
    deepseekBaseUrl: "https://api.deepseek.com",
    qwenBaseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    amapBaseUrl: "https://restapi.amap.com/v3"
  };
}
