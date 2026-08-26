export interface PublicErrorBody {
  error: {
    code: string;
    message: string;
    requestId: string;
    retryable: boolean;
  };
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly safeMessage: string,
    readonly retryable = false
  ) {
    super(`${code}: ${safeMessage}`);
    this.name = "ApiError";
  }
}

export function toPublicError(error: unknown, requestId: string): {
  status: number;
  body: PublicErrorBody;
} {
  const apiError =
    error instanceof ApiError
      ? error
      : new ApiError(500, "INTERNAL_ERROR", "The request could not be completed.", true);

  return {
    status: apiError.status,
    body: {
      error: {
        code: apiError.code,
        message: apiError.safeMessage,
        requestId,
        retryable: apiError.retryable
      }
    }
  };
}
