package com.dark.tool_neuron.service

import com.dark.tool_neuron.models.table_schema.McpServer
import com.dark.tool_neuron.models.table_schema.McpTransportType
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class McpToolMapperTest {
    @Test
    fun buildMappingCreatesToolRegistry() {
        val server = McpServer(
            id = "server-1",
            name = "Zapier MCP",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE
        )
        val tool = McpToolInfo(
            name = "send-email",
            description = "Send an email",
            inputSchema = """{"type":"object","properties":{"to":{"type":"string"}}}"""
        )

        val mapping = McpToolMapper.buildMapping(mapOf(server to listOf(tool)))
        val toolsArray = JSONArray(mapping.toolsJson)

        assertEquals(1, toolsArray.length())
        val function = toolsArray.getJSONObject(0).getJSONObject("function")
        assertEquals("zapier_mcp_send_email", function.getString("name"))
        assertEquals("object", function.getJSONObject("parameters").getString("type"))

        val reference = mapping.toolRegistry["zapier_mcp_send_email"]
        assertNotNull(reference)
        assertEquals(server, reference?.server)
        assertEquals("send-email", reference?.toolName)
    }
    
    @Test
    fun buildMappingFiltersDisabledTools() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE,
            disabledToolsJson = """["tool-b"]"""
        )
        
        val tools = listOf(
            McpToolInfo(name = "tool-a", description = "Tool A", inputSchema = null),
            McpToolInfo(name = "tool-b", description = "Tool B (disabled)", inputSchema = null),
            McpToolInfo(name = "tool-c", description = "Tool C", inputSchema = null)
        )
        
        val mapping = McpToolMapper.buildMapping(mapOf(server to tools))
        
        // Should have 2 tools (tool-a and tool-c), not tool-b
        assertEquals(2, mapping.toolRegistry.size)
        assertEquals(1, mapping.skippedDisabledTools)
        assertNotNull(mapping.toolRegistry["test_server_tool_a"])
        assertNull(mapping.toolRegistry["test_server_tool_b"])
        assertNotNull(mapping.toolRegistry["test_server_tool_c"])
    }
    
    @Test
    fun buildMappingHandlesEmptyToolList() {
        val server = McpServer(
            id = "server-1",
            name = "Empty Server",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE
        )
        
        val mapping = McpToolMapper.buildMapping(mapOf(server to emptyList()))
        
        assertEquals("[]", mapping.toolsJson)
        assertTrue(mapping.toolRegistry.isEmpty())
        assertEquals(0, mapping.totalToolCount)
    }
    
    @Test
    fun buildMappingHandlesMultipleServers() {
        val server1 = McpServer(
            id = "server-1",
            name = "Server A",
            url = "https://a.example.com/mcp",
            transportType = McpTransportType.SSE
        )
        val server2 = McpServer(
            id = "server-2",
            name = "Server B",
            url = "https://b.example.com/mcp",
            transportType = McpTransportType.STREAMABLE_HTTP
        )
        
        val tools1 = listOf(McpToolInfo(name = "tool-1", description = "Tool 1", inputSchema = null))
        val tools2 = listOf(McpToolInfo(name = "tool-2", description = "Tool 2", inputSchema = null))
        
        val mapping = McpToolMapper.buildMapping(mapOf(server1 to tools1, server2 to tools2))
        
        assertEquals(2, mapping.toolRegistry.size)
        assertNotNull(mapping.toolRegistry["server_a_tool_1"])
        assertNotNull(mapping.toolRegistry["server_b_tool_2"])
        
        // Verify servers are correctly referenced
        assertEquals(server1, mapping.toolRegistry["server_a_tool_1"]?.server)
        assertEquals(server2, mapping.toolRegistry["server_b_tool_2"]?.server)
    }
    
    @Test
    fun estimateTokenCountReturnsReasonableEstimate() {
        // Empty string
        assertEquals(0, McpToolMapper.estimateTokenCount(""))
        assertEquals(0, McpToolMapper.estimateTokenCount("   "))
        
        // Short string
        val short = "hello"
        assertTrue(McpToolMapper.estimateTokenCount(short) >= 1)
        
        // Long string (1000 characters)
        val long = "a".repeat(1000)
        val estimate = McpToolMapper.estimateTokenCount(long)
        // Should be approximately 250 tokens (1000/4)
        assertTrue(estimate in 200..300)
    }
    
    @Test
    fun validateContextUsageDetectsOverflow() {
        // Small context with large tools JSON
        val largeToolsJson = buildLargeToolsJson(100) // 100 tools
        val validation = McpToolMapper.validateContextUsage(
            toolsJson = largeToolsJson,
            contextSize = 512, // Very small context
            serverNames = listOf("Test Server")
        )
        
        // With 100 tools, this should overflow a 512 token context
        assertFalse(validation.isValid)
        assertEquals(100, validation.toolCount)
        assertTrue(validation.estimatedTokens > 0)
    }
    
    @Test
    fun validateContextUsagePassesForSmallToolset() {
        // Single small tool with large context
        val smallToolsJson = """[{"type":"function","function":{"name":"test","description":"A test tool"}}]"""
        val validation = McpToolMapper.validateContextUsage(
            toolsJson = smallToolsJson,
            contextSize = 4096,
            serverNames = listOf("Test Server")
        )
        
        assertTrue(validation.isValid)
        assertEquals(1, validation.toolCount)
    }
    
    @Test(expected = McpContextOverflowException::class)
    fun buildMappingThrowsOnContextOverflow() {
        val server = McpServer(
            id = "server-1",
            name = "Large Server",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE
        )
        
        // Create many tools to overflow context
        val tools = (1..100).map { 
            McpToolInfo(
                name = "tool-$it",
                description = "This is a long description for tool number $it that takes up tokens",
                inputSchema = """{"type":"object","properties":{"param$it":{"type":"string","description":"Parameter for tool $it"}}}"""
            )
        }
        
        // Should throw McpContextOverflowException
        McpToolMapper.buildMapping(mapOf(server to tools), contextSize = 256)
    }
    
    @Test
    fun buildMappingSucceedsWithoutContextValidation() {
        val server = McpServer(
            id = "server-1",
            name = "Large Server",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE
        )
        
        // Create many tools
        val tools = (1..50).map { 
            McpToolInfo(name = "tool-$it", description = "Tool $it", inputSchema = null)
        }
        
        // Should succeed when contextSize = 0 (no validation)
        val mapping = McpToolMapper.buildMapping(mapOf(server to tools), contextSize = 0)
        assertEquals(50, mapping.toolRegistry.size)
    }
    
    @Test
    fun sanitizeIdentifierHandlesEdgeCases() {
        // Empty string
        assertEquals("", McpToolMapper.sanitizeIdentifier(""))
        
        // All special characters
        assertEquals("", McpToolMapper.sanitizeIdentifier("!@#$%^&*()"))
        
        // Mixed case and special chars
        assertEquals("hello_world_123", McpToolMapper.sanitizeIdentifier("Hello World! 123"))
        
        // Leading/trailing special chars
        assertEquals("test", McpToolMapper.sanitizeIdentifier("___test___"))
        
        // Unicode characters
        assertEquals("caf", McpToolMapper.sanitizeIdentifier("café"))
    }
    
    @Test
    fun buildMappingHandlesNullInputSchema() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE
        )
        val tool = McpToolInfo(
            name = "no-schema-tool",
            description = "A tool without schema",
            inputSchema = null
        )
        
        val mapping = McpToolMapper.buildMapping(mapOf(server to listOf(tool)))
        val toolsArray = JSONArray(mapping.toolsJson)
        
        assertEquals(1, toolsArray.length())
        val function = toolsArray.getJSONObject(0).getJSONObject("function")
        val parameters = function.getJSONObject("parameters")
        
        // Should have default "object" type
        assertEquals("object", parameters.getString("type"))
    }
    
    @Test
    fun buildMappingHandlesInvalidInputSchema() {
        val server = McpServer(
            id = "server-1",
            name = "Test Server",
            url = "https://example.com/mcp",
            transportType = McpTransportType.SSE
        )
        val tool = McpToolInfo(
            name = "invalid-schema-tool",
            description = "A tool with invalid schema",
            inputSchema = "not valid json {{"
        )
        
        val mapping = McpToolMapper.buildMapping(mapOf(server to listOf(tool)))
        val toolsArray = JSONArray(mapping.toolsJson)
        
        assertEquals(1, toolsArray.length())
        val function = toolsArray.getJSONObject(0).getJSONObject("function")
        val parameters = function.getJSONObject("parameters")
        
        // Should fall back to default "object" type
        assertEquals("object", parameters.getString("type"))
    }
    
    @Test
    fun mcpContextOverflowExceptionContainsUsefulInfo() {
        val exception = McpContextOverflowException(
            toolCount = 50,
            estimatedTokens = 2000,
            availableContext = 500,
            serverNames = listOf("Server A", "Server B")
        )
        
        assertTrue(exception.message!!.contains("50 tools"))
        assertTrue(exception.message!!.contains("2000"))
        assertTrue(exception.message!!.contains("500"))
        assertTrue(exception.message!!.contains("Server A"))
        assertTrue(exception.message!!.contains("Server B"))
    }
    
    @Test
    fun mcpContextOverflowExceptionHandlesManyServers() {
        val exception = McpContextOverflowException(
            toolCount = 100,
            estimatedTokens = 5000,
            availableContext = 1000,
            serverNames = listOf("Server A", "Server B", "Server C", "Server D", "Server E")
        )
        
        // Should show first 2 servers and "3 more"
        assertTrue(exception.message!!.contains("Server A"))
        assertTrue(exception.message!!.contains("Server B"))
        assertTrue(exception.message!!.contains("3 more"))
    }
    
    // Helper function to build a large tools JSON for testing
    private fun buildLargeToolsJson(count: Int): String {
        val tools = (1..count).map {
            """{"type":"function","function":{"name":"tool_$it","description":"Tool number $it with description"}}"""
        }
        return "[${tools.joinToString(",")}]"
    }
}
