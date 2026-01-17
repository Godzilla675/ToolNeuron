package com.dark.tool_neuron.models.table_schema

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Transport type for MCP server connections
 */
enum class McpTransportType {
    SSE,  // Server-Sent Events (HTTP)
    STREAMABLE_HTTP  // Streamable HTTP transport
}

/**
 * Connection status of an MCP server (runtime only, not persisted)
 */
enum class McpConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Entity representing a remote MCP (Model Context Protocol) server configuration.
 * MCP servers provide tools, resources, and prompts to LLM applications.
 */
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey
    val id: String,
    
    /** Display name for the server */
    val name: String,
    
    /** Server URL (e.g., "https://api.example.com/mcp") */
    val url: String,
    
    /** Transport type for the connection */
    val transportType: McpTransportType = McpTransportType.SSE,
    
    /** Optional API key for authentication */
    val apiKey: String? = null,
    
    /** Whether the server is enabled */
    val isEnabled: Boolean = true,
    
    /** Last error message if connection failed */
    val lastError: String? = null,
    
    /** Timestamp when the server was added */
    val createdAt: Long = System.currentTimeMillis(),
    
    /** Timestamp when the server was last modified */
    val updatedAt: Long = System.currentTimeMillis(),
    
    /** Timestamp when last successfully connected */
    val lastConnectedAt: Long? = null,
    
    /** Optional description */
    val description: String = "",
    
    /** Custom headers as JSON string (e.g., for additional auth) */
    val customHeadersJson: String? = null,
    
    /** JSON array of disabled tool names (tools from this server that should not be used) */
    val disabledToolsJson: String? = null
) {
    companion object {
        fun generateId(): String = java.util.UUID.randomUUID().toString()
    }
    
    /**
     * Get the list of disabled tool names for this server
     */
    fun getDisabledTools(): Set<String> {
        return try {
            if (disabledToolsJson.isNullOrBlank()) {
                emptySet()
            } else {
                val jsonArray = org.json.JSONArray(disabledToolsJson)
                (0 until jsonArray.length()).map { jsonArray.getString(it) }.toSet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }
    
    /**
     * Check if a specific tool is disabled
     */
    fun isToolDisabled(toolName: String): Boolean {
        return getDisabledTools().contains(toolName)
    }
    
    /**
     * Create a copy with updated disabled tools list
     */
    fun withDisabledTools(disabledTools: Set<String>): McpServer {
        val json = if (disabledTools.isEmpty()) {
            null
        } else {
            org.json.JSONArray(disabledTools.toList()).toString()
        }
        return copy(disabledToolsJson = json, updatedAt = System.currentTimeMillis())
    }
}
