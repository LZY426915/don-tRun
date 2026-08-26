import { isIP } from "node:net";

import type { ServerConfig } from "../config.js";
import { ApiError } from "../errors.js";

export interface IpLocationInput {
  ip?: string;
}

export interface GeocodeInput {
  city: string;
  address: string;
}

export interface ReverseGeocodeInput {
  longitude: number;
  latitude: number;
  extensions?: "base" | "all";
}

export interface WeatherInput {
  adcode: string;
  extensions?: "base" | "all";
}

export async function locateIp(
  input: IpLocationInput,
  config: ServerConfig,
  fetchImpl: typeof fetch = fetch
): Promise<Record<string, unknown>> {
  if (input.ip !== undefined && isIP(input.ip) === 0) {
    throw invalidRequest("ip must be a valid IPv4 or IPv6 address.");
  }

  const params = new URLSearchParams();
  if (input.ip) params.set("ip", input.ip);
  return callAmap("/ip", params, config, fetchImpl);
}

export async function geocode(
  input: GeocodeInput,
  config: ServerConfig,
  fetchImpl: typeof fetch = fetch
): Promise<Record<string, unknown>> {
  const city = requiredText(input.city, "city");
  const address = requiredText(input.address, "address");
  const params = new URLSearchParams({ city, address });
  return callAmap("/geocode/geo", params, config, fetchImpl);
}

export async function reverseGeocode(
  input: ReverseGeocodeInput,
  config: ServerConfig,
  fetchImpl: typeof fetch = fetch
): Promise<Record<string, unknown>> {
  if (!Number.isFinite(input.longitude) || input.longitude < -180 || input.longitude > 180) {
    throw invalidRequest("longitude must be between -180 and 180.");
  }
  if (!Number.isFinite(input.latitude) || input.latitude < -90 || input.latitude > 90) {
    throw invalidRequest("latitude must be between -90 and 90.");
  }
  const extensions = validateExtensions(input.extensions);
  const params = new URLSearchParams({
    location: `${input.longitude},${input.latitude}`,
    extensions
  });
  return callAmap("/geocode/regeo", params, config, fetchImpl);
}

export async function weather(
  input: WeatherInput,
  config: ServerConfig,
  fetchImpl: typeof fetch = fetch
): Promise<Record<string, unknown>> {
  if (!/^\d{6}$/.test(input.adcode)) {
    throw invalidRequest("adcode must contain exactly six digits.");
  }
  const params = new URLSearchParams({
    city: input.adcode,
    extensions: validateExtensions(input.extensions)
  });
  return callAmap("/weather/weatherInfo", params, config, fetchImpl);
}

async function callAmap(
  path: "/ip" | "/geocode/geo" | "/geocode/regeo" | "/weather/weatherInfo",
  params: URLSearchParams,
  config: ServerConfig,
  fetchImpl: typeof fetch
): Promise<Record<string, unknown>> {
  const url = new URL(`${config.amapBaseUrl}${path}`);
  for (const [name, value] of params) url.searchParams.set(name, value);
  url.searchParams.set("key", config.amapWebApiKey);

  let response: Response;
  try {
    response = await fetchImpl(url, {
      method: "GET",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(30_000)
    });
  } catch {
    throw providerUnavailable(true);
  }
  if (!response.ok) {
    throw providerUnavailable(response.status >= 500 || response.status === 429);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    throw providerUnavailable(true);
  }
  if (!isRecord(body) || body.status !== "1") {
    throw providerUnavailable(false);
  }
  return body;
}

function requiredText(value: unknown, field: string): string {
  if (typeof value !== "string") {
    throw invalidRequest(`${field} must be text.`);
  }
  const trimmed = value.trim();
  if (trimmed.length < 1 || trimmed.length > 80) {
    throw invalidRequest(`${field} must contain 1 to 80 characters.`);
  }
  return trimmed;
}

function validateExtensions(value: unknown): "base" | "all" {
  if (value === undefined) return "base";
  if (value !== "base" && value !== "all") {
    throw invalidRequest("extensions must be base or all.");
  }
  return value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalidRequest(detail: string): ApiError {
  return new ApiError(400, "INVALID_REQUEST", `Invalid Amap request: ${detail}`);
}

function providerUnavailable(retryable: boolean): ApiError {
  return new ApiError(
    502,
    "PROVIDER_UNAVAILABLE",
    "The location or weather service is temporarily unavailable.",
    retryable
  );
}
