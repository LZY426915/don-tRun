export interface RecordingFetch {
  fetch: typeof fetch;
  lastUrl: URL | null;
  lastHeaders: Record<string, string>;
  lastJson: Record<string, unknown> | null;
}

export function recordingFetch(response: Response): RecordingFetch {
  const recorder: RecordingFetch = {
    fetch: async (input, init) => {
      recorder.lastUrl = new URL(
        typeof input === "string" || input instanceof URL ? input : input.url
      );
      recorder.lastHeaders = Object.fromEntries(new Headers(init?.headers).entries());
      recorder.lastJson =
        typeof init?.body === "string"
          ? JSON.parse(init.body) as Record<string, unknown>
          : null;
      return response;
    },
    lastUrl: null,
    lastHeaders: {},
    lastJson: null
  };
  return recorder;
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" }
  });
}
