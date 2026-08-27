package com.youshu.app.data.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private val toolIdentityJson = Json { ignoreUnknownKeys = true }

internal fun toolCallIdentity(toolCall: ToolCall): String {
    val canonicalArguments = runCatching {
        canonicalize(toolIdentityJson.parseToJsonElement(toolCall.arguments)).toString()
    }.getOrElse {
        toolCall.arguments.replace(Regex("\\s+"), "")
    }
    return "${toolCall.name}:$canonicalArguments"
}

private fun canonicalize(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries
            .sortedBy { it.key }
            .associate { (key, value) -> key to canonicalize(value) }
    )
    is JsonArray -> JsonArray(element.map(::canonicalize))
    else -> element
}
