package com.dark.tool_neuron.service

/**
 * Base exception class for MCP-related errors
 */
sealed class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Exception thrown when the combined tools JSON exceeds the available context size.
 * This happens when there are too many MCP tools registered relative to the model's context window.
 *
 * @param toolCount Number of tools that were attempted to be loaded
 * @param estimatedTokens Estimated number of tokens the tools JSON would consume
 * @param availableContext Available context size in tokens
 * @param serverNames Names of servers contributing tools
 */
class McpContextOverflowException(
    val toolCount: Int,
    val estimatedTokens: Int,
    val availableContext: Int,
    val serverNames: List<String>
) : McpException(
    buildMessage(toolCount, estimatedTokens, availableContext, serverNames)
) {
    companion object {
        private fun buildMessage(
            toolCount: Int,
            estimatedTokens: Int,
            availableContext: Int,
            serverNames: List<String>
        ): String {
            val serversText = when {
                serverNames.isEmpty() -> "unknown servers"
                serverNames.size == 1 -> "'${serverNames[0]}'"
                serverNames.size <= 3 -> serverNames.joinToString(", ") { "'$it'" }
                else -> "${serverNames.take(2).joinToString(", ") { "'$it'" }} and ${serverNames.size - 2} more"
            }
            return "Too many MCP tools ($toolCount tools from $serversText) exceed the available context size. " +
                    "Estimated tool tokens: $estimatedTokens, available context: $availableContext. " +
                    "Please disable some MCP servers or individual tools to reduce the context usage."
        }
    }
}

/**
 * Exception thrown when a specific tool cannot be found in the registry
 *
 * @param toolName The name of the tool that was not found
 */
class McpToolNotFoundException(
    val toolName: String
) : McpException("MCP tool not found: '$toolName'. The tool may have been disabled or the server disconnected.")

/**
 * Exception thrown when an MCP server returns an invalid response
 *
 * @param serverName Name of the server that returned the invalid response
 * @param details Additional details about what was invalid
 */
class McpInvalidResponseException(
    val serverName: String,
    val details: String
) : McpException("Invalid response from MCP server '$serverName': $details")

/**
 * Exception thrown when an MCP server connection times out
 *
 * @param serverName Name of the server that timed out
 * @param timeoutMs Timeout duration in milliseconds
 */
class McpConnectionTimeoutException(
    val serverName: String,
    val timeoutMs: Long
) : McpException("Connection to MCP server '$serverName' timed out after ${timeoutMs}ms")

/**
 * Exception thrown when an MCP server is disabled but a tool call is attempted
 *
 * @param serverName Name of the disabled server
 */
class McpServerDisabledException(
    val serverName: String
) : McpException("MCP server '$serverName' is disabled. Enable the server to use its tools.")

/**
 * Exception thrown when a specific tool is disabled on a server
 *
 * @param toolName Name of the disabled tool
 * @param serverName Name of the server the tool belongs to
 */
class McpToolDisabledException(
    val toolName: String,
    val serverName: String
) : McpException("Tool '$toolName' is disabled on server '$serverName'. Enable the tool to use it.")
