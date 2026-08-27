import { ApiError } from "./errors.js";

export type YouShuStreamEvent =
  | { type: "text-delta"; text: string }
  | {
      type: "tool-call-delta";
      index: number;
      id?: string;
      name?: string;
      arguments?: string;
    }
  | { type: "done"; finishReason: string | null }
  | {
      type: "error";
      code: string;
      message: string;
      retryable: boolean;
      requestId: string;
    };

export async function* parseProviderSse(
  stream: ReadableStream<Uint8Array>
): AsyncGenerator<YouShuStreamEvent> {
  const decoder = new TextDecoder();
  const reader = stream.getReader();
  let pending = "";
  let dataLines: string[] = [];
  let doneEmitted = false;

  const dispatchFrame = (): YouShuStreamEvent[] => {
    if (dataLines.length === 0) return [];
    const data = dataLines.join("\n");
    dataLines = [];
    if (data.trim() === "[DONE]") {
      if (doneEmitted) return [];
      doneEmitted = true;
      return [{ type: "done", finishReason: null }];
    }

    const events = eventsFromProviderData(data);
    if (events.some((event) => event.type === "done")) doneEmitted = true;
    return events;
  };

  const consumeLine = (line: string): YouShuStreamEvent[] => {
    if (line === "") return dispatchFrame();
    if (line.startsWith(":")) return [];
    if (line === "data") {
      dataLines.push("");
    } else if (line.startsWith("data:")) {
      const value = line.slice(5);
      dataLines.push(value.startsWith(" ") ? value.slice(1) : value);
    }
    return [];
  };

  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      pending += decoder.decode(value, { stream: true });
      let newline = pending.indexOf("\n");
      while (newline >= 0) {
        const rawLine = pending.slice(0, newline);
        pending = pending.slice(newline + 1);
        const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
        for (const event of consumeLine(line)) yield event;
        newline = pending.indexOf("\n");
      }
    }

    pending += decoder.decode();
    if (pending.length > 0) {
      const line = pending.endsWith("\r") ? pending.slice(0, -1) : pending;
      for (const event of consumeLine(line)) yield event;
    }
    for (const event of dispatchFrame()) yield event;
    if (!doneEmitted) {
      throw new ApiError(
        502,
        "PROVIDER_STREAM_TRUNCATED",
        "AI 服务返回了不完整的响应，请稍后重试。",
        true
      );
    }
  } finally {
    reader.releaseLock();
  }
}

export function encodeSseEvent(event: YouShuStreamEvent): string {
  const { type, ...payload } = event;
  return `event: ${type}\ndata: ${JSON.stringify(payload)}\n\n`;
}

function eventsFromProviderData(data: string): YouShuStreamEvent[] {
  let payload: unknown;
  try {
    payload = JSON.parse(data) as unknown;
  } catch {
    throw invalidProviderResponse();
  }
  if (!isRecord(payload) || !Array.isArray(payload.choices)) {
    throw invalidProviderResponse();
  }

  const events: YouShuStreamEvent[] = [];
  for (const choiceValue of payload.choices) {
    if (!isRecord(choiceValue)) continue;
    const delta = isRecord(choiceValue.delta) ? choiceValue.delta : {};
    if (typeof delta.content === "string" && delta.content.length > 0) {
      events.push({ type: "text-delta", text: delta.content });
    }
    if (Array.isArray(delta.tool_calls)) {
      for (const callValue of delta.tool_calls) {
        if (!isRecord(callValue) || !Number.isInteger(callValue.index)) continue;
        const event: Extract<YouShuStreamEvent, { type: "tool-call-delta" }> = {
          type: "tool-call-delta",
          index: callValue.index as number
        };
        if (typeof callValue.id === "string") event.id = callValue.id;
        if (isRecord(callValue.function)) {
          if (typeof callValue.function.name === "string") {
            event.name = callValue.function.name;
          }
          if (typeof callValue.function.arguments === "string") {
            event.arguments = callValue.function.arguments;
          }
        }
        events.push(event);
      }
    }
    if (typeof choiceValue.finish_reason === "string") {
      events.push({ type: "done", finishReason: choiceValue.finish_reason });
    }
  }
  return events;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function invalidProviderResponse(): ApiError {
  return new ApiError(
    502,
    "PROVIDER_INVALID_RESPONSE",
    "AI 服务返回了无法识别的响应。",
    true
  );
}
