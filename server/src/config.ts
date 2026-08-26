export interface ServerConfig {
  deepseekApiKey: string;
  qwenApiKey: string;
  amapWebApiKey: string;
  sessionSigningSecret: string;
  deepseekModel: string;
  qwenVisionModel: string;
  qwenSpeechModel: string;
  allowedAppVersions: ReadonlySet<string>;
  deepseekBaseUrl: "https://api.deepseek.com";
  qwenBaseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1";
  amapBaseUrl: "https://restapi.amap.com/v3";
}

function requireValue(env: NodeJS.ProcessEnv, name: string): string {
  const value = env[name]?.trim();
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

export function loadConfig(env: NodeJS.ProcessEnv): ServerConfig {
  const sessionSigningSecret = requireValue(env, "SESSION_SIGNING_SECRET");
  if (sessionSigningSecret.length < 32) {
    throw new Error("SESSION_SIGNING_SECRET must contain at least 32 characters");
  }

  const allowedAppVersions = new Set(
    requireValue(env, "ALLOWED_APP_VERSIONS")
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean)
  );
  if (allowedAppVersions.size === 0) {
    throw new Error("ALLOWED_APP_VERSIONS must contain at least one version");
  }

  return {
    deepseekApiKey: requireValue(env, "DEEPSEEK_API_KEY"),
    qwenApiKey: requireValue(env, "QWEN_API_KEY"),
    amapWebApiKey: requireValue(env, "AMAP_WEB_API_KEY"),
    sessionSigningSecret,
    deepseekModel: requireValue(env, "DEEPSEEK_MODEL"),
    qwenVisionModel: requireValue(env, "QWEN_VISION_MODEL"),
    qwenSpeechModel: requireValue(env, "QWEN_SPEECH_MODEL"),
    allowedAppVersions,
    deepseekBaseUrl: "https://api.deepseek.com",
    qwenBaseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    amapBaseUrl: "https://restapi.amap.com/v3"
  };
}
