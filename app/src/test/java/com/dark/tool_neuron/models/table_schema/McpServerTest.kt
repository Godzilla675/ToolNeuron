package com.dark.tool_neuron.models.table_schema

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for McpServer model
 */
class McpServerTest {

    @Test
    fun generateIdCreatesUniqueUuids() {
        val ids = (1..100).map { McpServer.generateId() }.toSet()
        assertEquals(100, ids.size)
    }
    
    @Test
    fun getDisabledToolsReturnsEmptySetForNullJson() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = null
        )
        
        assertTrue(server.getDisabledTools().isEmpty())
    }
    
    @Test
    fun getDisabledToolsReturnsEmptySetForEmptyJson() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = ""
        )
        
        assertTrue(server.getDisabledTools().isEmpty())
    }
    
    @Test
    fun getDisabledToolsReturnsEmptySetForBlankJson() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = "   "
        )
        
        assertTrue(server.getDisabledTools().isEmpty())
    }
    
    @Test
    fun getDisabledToolsReturnsEmptySetForEmptyArrayJson() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = "[]"
        )
        
        assertTrue(server.getDisabledTools().isEmpty())
    }
    
    @Test
    fun getDisabledToolsParsesValidJson() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = """["tool-a", "tool-b", "tool-c"]"""
        )
        
        val disabled = server.getDisabledTools()
        assertEquals(3, disabled.size)
        assertTrue(disabled.contains("tool-a"))
        assertTrue(disabled.contains("tool-b"))
        assertTrue(disabled.contains("tool-c"))
    }
    
    @Test
    fun getDisabledToolsReturnsEmptySetForInvalidJson() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = "not valid json"
        )
        
        assertTrue(server.getDisabledTools().isEmpty())
    }
    
    @Test
    fun isToolDisabledReturnsTrueForDisabledTool() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = """["disabled-tool"]"""
        )
        
        assertTrue(server.isToolDisabled("disabled-tool"))
    }
    
    @Test
    fun isToolDisabledReturnsFalseForEnabledTool() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = """["other-tool"]"""
        )
        
        assertFalse(server.isToolDisabled("enabled-tool"))
    }
    
    @Test
    fun isToolDisabledReturnsFalseWhenNoDisabledTools() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = null
        )
        
        assertFalse(server.isToolDisabled("any-tool"))
    }
    
    @Test
    fun withDisabledToolsCreatesCorrectJson() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp"
        )
        
        val updated = server.withDisabledTools(setOf("tool-a", "tool-b"))
        
        assertNotNull(updated.disabledToolsJson)
        assertTrue(updated.isToolDisabled("tool-a"))
        assertTrue(updated.isToolDisabled("tool-b"))
        assertFalse(updated.isToolDisabled("tool-c"))
    }
    
    @Test
    fun withDisabledToolsSetsNullForEmptySet() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            disabledToolsJson = """["tool-a"]"""
        )
        
        val updated = server.withDisabledTools(emptySet())
        
        assertNull(updated.disabledToolsJson)
        assertFalse(updated.isToolDisabled("tool-a"))
    }
    
    @Test
    fun withDisabledToolsUpdatesTimestamp() {
        val originalTime = 1000000L
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            updatedAt = originalTime
        )
        
        val updated = server.withDisabledTools(setOf("tool-a"))
        
        assertTrue(updated.updatedAt > originalTime)
    }
    
    @Test
    fun withDisabledToolsPreservesOtherFields() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE,
            apiKey = "secret-key",
            isEnabled = true,
            description = "A test server"
        )
        
        val updated = server.withDisabledTools(setOf("tool-a"))
        
        assertEquals(server.id, updated.id)
        assertEquals(server.name, updated.name)
        assertEquals(server.url, updated.url)
        assertEquals(server.transportType, updated.transportType)
        assertEquals(server.apiKey, updated.apiKey)
        assertEquals(server.isEnabled, updated.isEnabled)
        assertEquals(server.description, updated.description)
    }
    
    @Test
    fun defaultValuesAreCorrect() {
        val server = McpServer(
            id = "server-1",
            name = "Test",
            url = "https://example.com"
        )
        
        assertEquals(McpTransportType.SSE, server.transportType)
        assertNull(server.apiKey)
        assertTrue(server.isEnabled)
        assertNull(server.lastError)
        assertNull(server.lastConnectedAt)
        assertEquals("", server.description)
        assertNull(server.customHeadersJson)
        assertNull(server.disabledToolsJson)
    }
    
    @Test
    fun transportTypesAreAvailable() {
        val sseServer = McpServer(
            id = "sse",
            name = "SSE Server",
            url = "https://example.com",
            transportType = McpTransportType.SSE
        )
        
        val httpServer = McpServer(
            id = "http",
            name = "HTTP Server",
            url = "https://example.com",
            transportType = McpTransportType.STREAMABLE_HTTP
        )
        
        assertEquals(McpTransportType.SSE, sseServer.transportType)
        assertEquals(McpTransportType.STREAMABLE_HTTP, httpServer.transportType)
    }
    
    @Test
    fun connectionStatusEnumHasAllValues() {
        val statuses = McpConnectionStatus.values()
        
        assertEquals(4, statuses.size)
        assertTrue(statuses.contains(McpConnectionStatus.DISCONNECTED))
        assertTrue(statuses.contains(McpConnectionStatus.CONNECTING))
        assertTrue(statuses.contains(McpConnectionStatus.CONNECTED))
        assertTrue(statuses.contains(McpConnectionStatus.ERROR))
    }
}
