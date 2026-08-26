package com.youshu.app.data.network

import java.io.IOException

class BackendApiException(
    val code: String,
    val safeMessage: String,
    val retryable: Boolean,
    val requestId: String? = null,
    cause: Throwable? = null
) : IOException(safeMessage, cause)
