package com.dark.tool_neuron.service

import com.dark.tool_neuron.models.table_schema.McpServer
import org.json.JSONArray
import org.json.JSONObject

data class McpToolReference(
    val server: McpServer,
    val toolName: String
)

data class McpToolMapping(
    val toolsJson: String,
    val toolRegistry: Map<String, McpToolReference>,
    val totalToolCount: Int = 0,
    val estimatedTokens: Int = 0,
    val skippedDisabledTools: Int = 0
)

/**
 * Result of context validation for MCP tools
 */
data class McpContextValidation(
    val isValid: Boolean,
    val toolCount: Int,
    val estimatedTokens: Int,
    val availableContext: Int,
    val serverNames: List<String>
)

object McpToolMapper {
    
    /**
     * Approximate tokens per character ratio for tool definitions.
     * 
     * This is a conservative estimate for token counting. Actual tokenization
     * varies significantly by model and tokenizer:
     * - GPT-style BPE tokenizers: ~4 chars/token for English text
     * - SentencePiece (LLaMA): ~3.5 chars/token
     * - JSON structure tends to tokenize less efficiently due to special chars
     * 
     * We use 4 chars/token as a conservative estimate that should work for most
     * models. For critical applications, consider using actual tokenizer counts.
     */
    private const val CHARS_PER_TOKEN = 4
    
    /**
     * Maximum percentage of context that tools should consume.
     * Leave room for the actual conversation.
     */
    private const val MAX_CONTEXT_USAGE_PERCENT = 0.3
    
    /**
     * Minimum context that must remain for conversation after tool definitions.
     */
    private const val MIN_CONVERSATION_TOKENS = 512
    
    fun sanitizeIdentifier(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    /**
     * Build a mapping of MCP tools, filtering out disabled tools.
     * 
     * @param serverTools Map of servers to their tools
     * @param contextSize Optional context size for validation (0 = no validation)
     * @return McpToolMapping with tools JSON and registry
     * @throws McpContextOverflowException if tools exceed context size
     */
    fun buildMapping(
        serverTools: Map<McpServer, List<McpToolInfo>>,
        contextSize: Int = 0
    ): McpToolMapping {
        val toolsArray = JSONArray()
        val registry = mutableMapOf<String, McpToolReference>()
        var skippedDisabledTools = 0

        serverTools.forEach { (server, tools) ->
            val serverPrefix = sanitizeIdentifier(server.name).ifBlank { "mcp" }
            val disabledTools = server.getDisabledTools()
            
            tools.forEach { tool ->
                // Skip disabled tools
                if (disabledTools.contains(tool.name)) {
                    skippedDisabledTools++
                    return@forEach
                }
                
                val toolSlug = sanitizeIdentifier(tool.name).ifBlank { "tool" }
                val toolId = "${serverPrefix}_${toolSlug}"
                toolsArray.put(buildToolDefinition(toolId, tool))
                registry[toolId] = McpToolReference(server, tool.name)
            }
        }

        val toolsJson = toolsArray.toString()
        val estimatedTokens = estimateTokenCount(toolsJson)
        
        // Validate context size if provided
        if (contextSize > 0) {
            val validation = validateContextUsage(
                toolsJson = toolsJson,
                contextSize = contextSize,
                serverNames = serverTools.keys.map { it.name }
            )
            
            if (!validation.isValid) {
                throw McpContextOverflowException(
                    toolCount = registry.size,
                    estimatedTokens = validation.estimatedTokens,
                    availableContext = validation.availableContext,
                    serverNames = validation.serverNames
                )
            }
        }

        return McpToolMapping(
            toolsJson = toolsJson,
            toolRegistry = registry,
            totalToolCount = registry.size,
            estimatedTokens = estimatedTokens,
            skippedDisabledTools = skippedDisabledTools
        )
    }
    
    /**
     * Estimate the token count for a given string.
     * Uses a simple character-based approximation.
     */
    fun estimateTokenCount(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / CHARS_PER_TOKEN) + 1
    }
    
    /**
     * Validate that tools don't consume too much of the context.
     * 
     * @param toolsJson The tools JSON string
     * @param contextSize The total context size in tokens
     * @param serverNames Names of servers contributing tools
     * @return Validation result
     */
    fun validateContextUsage(
        toolsJson: String,
        contextSize: Int,
        serverNames: List<String> = emptyList()
    ): McpContextValidation {
        val estimatedTokens = estimateTokenCount(toolsJson)
        val maxAllowedTokens = (contextSize * MAX_CONTEXT_USAGE_PERCENT).toInt()
        val minRequired = MIN_CONVERSATION_TOKENS
        
        // Tools are valid if they don't exceed the max percentage
        // AND leave enough room for minimum conversation
        val remainingForConversation = contextSize - estimatedTokens
        val isValid = estimatedTokens <= maxAllowedTokens && remainingForConversation >= minRequired
        
        return McpContextValidation(
            isValid = isValid,
            toolCount = try {
                JSONArray(toolsJson).length()
            } catch (e: Exception) {
                0
            },
            estimatedTokens = estimatedTokens,
            availableContext = maxAllowedTokens,
            serverNames = serverNames
        )
    }

    private fun buildToolDefinition(toolId: String, tool: McpToolInfo): JSONObject {
        val function = JSONObject().apply {
            put("name", toolId)
            tool.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
            put("parameters", buildParameters(tool.inputSchema))
        }

        return JSONObject().apply {
            put("type", "function")
            put("function", function)
        }
    }

    private fun buildParameters(inputSchema: String?): JSONObject {
        val parsedSchema = inputSchema?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        return (parsedSchema ?: JSONObject()).apply {
            if (!has("type")) {
                put("type", "object")
            }
        }
    }
}
