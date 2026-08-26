import http from "node:http";
import { pathToFileURL } from "node:url";

import type { ServerConfig } from "./config.js";
import { loadConfig } from "./config.js";
import { createRouter, type RouterDependencies } from "./router.js";

export function createServer(
  config: ServerConfig,
  overrides: Omit<RouterDependencies, "config"> = {}
): http.Server {
  const router = createRouter({ config, ...overrides });
  return http.createServer((request, response) => {
    void router(request, response);
  });
}

function isMainModule(): boolean {
  const entry = process.argv[1];
  return Boolean(entry && import.meta.url === pathToFileURL(entry).href);
}

if (isMainModule()) {
  const config = loadConfig(process.env);
  const rawPort = process.env.FC_CUSTOM_LISTEN_PORT ?? process.env.PORT ?? "9000";
  const port = Number.parseInt(rawPort, 10);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) {
    throw new Error("FC_CUSTOM_LISTEN_PORT or PORT must be a valid TCP port");
  }

  createServer(config).listen(port, "0.0.0.0", () => {
    process.stdout.write(`YouShu API proxy listening on port ${port}\n`);
  });
}
