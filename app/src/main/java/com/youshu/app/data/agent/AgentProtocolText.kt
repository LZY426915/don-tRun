package com.youshu.app.data.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ParsedAgentProtocolText(
    val visibleText: String,
    val toolCalls: List<ToolCall>
)

internal object AgentProtocolText {
    private val protocolStart = Regex("(?is)<\\s*\\|[^>]*DSML[^>]*tool_calls[^>]*>")
    private val invoke = Regex("(?is)invoke[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*>")
    private val parameterBlock = Regex(
        "(?is)<\\s*\\|[^>]*DSML[^>]*parameter([^>]*)>(.*?)<\\s*/\\s*\\|[^>]*DSML[^>]*parameter[^>]*>"
    )
    private val parameterAttribute = Regex(
        "(?is)parameter[^>]*name\\s*=\\s*\"([^\"]+)\"[^>]*(?:string|value)\\s*=\\s*\"([^\"]*)\"[^>]*/?>"
    )
    private val nameAttribute = Regex("(?is)name\\s*=\\s*\"([^\"]+)\"")

    fun parse(text: String): ParsedAgentProtocolText {
        val start = protocolStart.find(text) ?: return ParsedAgentProtocolText(text, emptyList())
        val protocol = text.substring(start.range.first)
        val calls = invoke.findAll(protocol).mapIndexed { index, match ->
            val rawToolName = match.groupValues[1]
            val toolName = normalizeToolName(rawToolName)
            val invocationStart = match.range.first
            val nextInvocation = invoke.find(protocol, match.range.last + 1)?.range?.first ?: protocol.length
            val invocation = protocol.substring(invocationStart, nextInvocation)
            val rawParameters = extractParameters(invocation)
            val normalizedParameters = normalizeArguments(toolName, rawParameters)
            val arguments = buildJsonObject {
                normalizedParameters.forEach { (name, value) -> put(name, value) }
            }.toString()
            ToolCall(
                id = "dsml-$index",
                name = toolName,
                arguments = arguments
            )
        }.toList()
        return ParsedAgentProtocolText(
            visibleText = text.substring(0, start.range.first).trim(),
            toolCalls = calls
        )
    }

    private fun extractParameters(invocation: String): Map<String, String> {
        val parameters = linkedMapOf<String, String>()
        parameterBlock.findAll(invocation).forEach { match ->
            val name = nameAttribute.find(match.groupValues[1])?.groupValues?.getOrNull(1)
            if (!name.isNullOrBlank()) {
                parameters[name] = decodeProtocolValue(match.groupValues[2].trim())
            }
        }
        parameterAttribute.findAll(invocation).forEach { match ->
            val name = match.groupValues[1]
            val value = match.groupValues[2]
            if (name !in parameters && !value.equals("true", ignoreCase = true)) {
                parameters[name] = decodeProtocolValue(value)
            }
        }
        return parameters
    }

    private fun normalizeArguments(
        toolName: String,
        parameters: Map<String, String>
    ): Map<String, String> {
        return when (toolName) {
            "update_item_location" -> {
                val keyword = parameters["keyword"]
                    ?: parameters["item_name"]
                    ?: parameters["name"]
                    ?: ""
                val parent = parameters["new_parent"].orEmpty().trim()
                val leaf = (parameters["target_location"] ?: parameters["new_location"])
                    .orEmpty()
                    .trim()
                val target = when {
                    parent.isBlank() -> leaf
                    leaf.isBlank() -> parent
                    leaf.startsWith(parent) -> leaf
                    else -> "$parent / $leaf"
                }
                mapOf("keyword" to keyword, "target_location" to target)
            }
            "find_related_items" -> buildMap {
                put("query", parameters["query"] ?: parameters["keyword"].orEmpty())
                parameters["status_scope"]?.let { put("status_scope", it) }
            }
            else -> parameters.mapKeys { (name, _) -> normalizeParameterName(name) }
        }
    }

    private fun decodeProtocolValue(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun normalizeToolName(name: String): String = when (name.trim()) {
        "prepare_move_item", "confirm_move_item" -> "update_item_location"
        else -> name.trim()
    }

    private fun normalizeParameterName(name: String): String = when (name.trim()) {
        "item_name" -> "keyword"
        "new_location" -> "target_location"
        else -> name.trim()
    }
}

internal class AgentVisibleTextGuard {
    private val pending = StringBuilder()

    var protocolDetected: Boolean = false
        private set

    fun accept(delta: String): String {
        if (protocolDetected || delta.isEmpty()) return ""
        pending.append(delta)
        val content = pending.toString()
        if (Regex("(?is)<\\s*\\|[^>]*DSML").containsMatchIn(content)) {
            protocolDetected = true
            val markerIndex = content.indexOf('<')
            pending.clear()
            return content.substring(0, markerIndex.coerceAtLeast(0))
        }
        val possibleMarker = content.lastIndexOf('<')
        return if (possibleMarker >= 0) {
            val visible = content.substring(0, possibleMarker)
            pending.delete(0, possibleMarker)
            visible
        } else {
            pending.clear()
            content
        }
    }

    fun flush(): String {
        if (protocolDetected) return ""
        return pending.toString().also { pending.clear() }
    }
}
