import { createHash, createHmac, timingSafeEqual } from "node:crypto";

import type { ServerConfig } from "./config.js";
import { ApiError } from "./errors.js";

const SESSION_LIFETIME_MS = 24 * 60 * 60 * 1_000;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface SessionClaims {
  installationHash: string;
  appVersion: string;
  issuedAt: number;
  expiresAt: number;
}

export interface SessionResponse {
  token: string;
  expiresAt: number;
}

function signatureFor(payload: string, secret: string): Buffer {
  return createHmac("sha256", secret).update(payload).digest();
}

export function hashInstallationId(installationId: string): string {
  return createHash("sha256").update(installationId).digest("hex");
}

export function issueSession(
  installationId: string,
  appVersion: string,
  config: ServerConfig,
  now = Date.now()
): SessionResponse {
  if (!UUID_PATTERN.test(installationId)) {
    throw new ApiError(400, "INVALID_REQUEST", "A valid installation ID is required.");
  }
  if (!config.allowedAppVersions.has(appVersion)) {
    throw new ApiError(
      403,
      "APP_VERSION_NOT_ALLOWED",
      "This app version is not allowed to use the service."
    );
  }

  const claims: SessionClaims = {
    installationHash: hashInstallationId(installationId),
    appVersion,
    issuedAt: now,
    expiresAt: now + SESSION_LIFETIME_MS
  };
  const payload = Buffer.from(JSON.stringify(claims)).toString("base64url");
  const signature = signatureFor(payload, config.sessionSigningSecret).toString("base64url");

  return { token: `${payload}.${signature}`, expiresAt: claims.expiresAt };
}

export function verifySession(
  token: string,
  config: ServerConfig,
  now = Date.now()
): SessionClaims {
  const parts = token.split(".");
  if (parts.length !== 2 || !parts[0] || !parts[1]) {
    throw new ApiError(401, "UNAUTHORIZED", "The session token has an invalid signature.");
  }

  const [payload, encodedSignature] = parts as [string, string];
  let suppliedSignature: Buffer;
  try {
    suppliedSignature = Buffer.from(encodedSignature, "base64url");
  } catch {
    throw new ApiError(401, "UNAUTHORIZED", "The session token has an invalid signature.");
  }
  const expectedSignature = signatureFor(payload, config.sessionSigningSecret);
  if (
    suppliedSignature.length !== expectedSignature.length ||
    !timingSafeEqual(suppliedSignature, expectedSignature)
  ) {
    throw new ApiError(401, "UNAUTHORIZED", "The session token has an invalid signature.");
  }

  let claims: SessionClaims;
  try {
    claims = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as SessionClaims;
  } catch {
    throw new ApiError(401, "UNAUTHORIZED", "The session token is invalid.");
  }

  if (
    typeof claims.installationHash !== "string" ||
    !/^[a-f0-9]{64}$/.test(claims.installationHash) ||
    typeof claims.appVersion !== "string" ||
    typeof claims.issuedAt !== "number" ||
    typeof claims.expiresAt !== "number"
  ) {
    throw new ApiError(401, "UNAUTHORIZED", "The session token is invalid.");
  }
  if (now > claims.expiresAt) {
    throw new ApiError(401, "SESSION_EXPIRED", "The session token has expired.");
  }
  if (claims.issuedAt > now + 60_000) {
    throw new ApiError(401, "UNAUTHORIZED", "The session token is invalid.");
  }
  if (!config.allowedAppVersions.has(claims.appVersion)) {
    throw new ApiError(
      403,
      "APP_VERSION_NOT_ALLOWED",
      "This app version is not allowed to use the service."
    );
  }

  return claims;
}
