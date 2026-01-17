package com.dark.tool_neuron.service

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for MCP exception classes
 */
class McpExceptionTest {

    @Test
    fun mcpContextOverflowExceptionCreatesProperMessage() {
        val exception = McpContextOverflowException(
            toolCount = 25,
            estimatedTokens = 1500,
            availableContext = 600,
            serverNames = listOf("Zapier MCP")
        )
        
        assertTrue(exception.message!!.contains("25 tools"))
        assertTrue(exception.message!!.contains("1500"))
        assertTrue(exception.message!!.contains("600"))
        assertTrue(exception.message!!.contains("Zapier MCP"))
        assertEquals(25, exception.toolCount)
        assertEquals(1500, exception.estimatedTokens)
        assertEquals(600, exception.availableContext)
    }
    
    @Test
    fun mcpContextOverflowExceptionHandlesEmptyServerNames() {
        val exception = McpContextOverflowException(
            toolCount = 10,
            estimatedTokens = 500,
            availableContext = 200,
            serverNames = emptyList()
        )
        
        assertTrue(exception.message!!.contains("unknown servers"))
    }
    
    @Test
    fun mcpContextOverflowExceptionHandlesSingleServer() {
        val exception = McpContextOverflowException(
            toolCount = 10,
            estimatedTokens = 500,
            availableContext = 200,
            serverNames = listOf("My Server")
        )
        
        assertTrue(exception.message!!.contains("'My Server'"))
    }
    
    @Test
    fun mcpContextOverflowExceptionHandlesTwoServers() {
        val exception = McpContextOverflowException(
            toolCount = 10,
            estimatedTokens = 500,
            availableContext = 200,
            serverNames = listOf("Server A", "Server B")
        )
        
        assertTrue(exception.message!!.contains("'Server A'"))
        assertTrue(exception.message!!.contains("'Server B'"))
    }
    
    @Test
    fun mcpContextOverflowExceptionHandlesThreeServers() {
        val exception = McpContextOverflowException(
            toolCount = 10,
            estimatedTokens = 500,
            availableContext = 200,
            serverNames = listOf("Server A", "Server B", "Server C")
        )
        
        assertTrue(exception.message!!.contains("'Server A'"))
        assertTrue(exception.message!!.contains("'Server B'"))
        assertTrue(exception.message!!.contains("'Server C'"))
    }
    
    @Test
    fun mcpContextOverflowExceptionTruncatesMoreThanThreeServers() {
        val exception = McpContextOverflowException(
            toolCount = 50,
            estimatedTokens = 3000,
            availableContext = 1000,
            serverNames = listOf("Server A", "Server B", "Server C", "Server D", "Server E")
        )
        
        assertTrue(exception.message!!.contains("'Server A'"))
        assertTrue(exception.message!!.contains("'Server B'"))
        assertTrue(exception.message!!.contains("3 more"))
        assertFalse(exception.message!!.contains("Server C"))
    }
    
    @Test
    fun mcpToolNotFoundExceptionCreatesProperMessage() {
        val exception = McpToolNotFoundException("my_custom_tool")
        
        assertTrue(exception.message!!.contains("my_custom_tool"))
        assertTrue(exception.message!!.contains("not found"))
        assertEquals("my_custom_tool", exception.toolName)
    }
    
    @Test
    fun mcpInvalidResponseExceptionCreatesProperMessage() {
        val exception = McpInvalidResponseException(
            serverName = "Zapier MCP",
            details = "Missing 'result' field in response"
        )
        
        assertTrue(exception.message!!.contains("Zapier MCP"))
        assertTrue(exception.message!!.contains("Missing 'result' field"))
        assertEquals("Zapier MCP", exception.serverName)
        assertEquals("Missing 'result' field in response", exception.details)
    }
    
    @Test
    fun mcpConnectionTimeoutExceptionCreatesProperMessage() {
        val exception = McpConnectionTimeoutException(
            serverName = "Slow Server",
            timeoutMs = 30000
        )
        
        assertTrue(exception.message!!.contains("Slow Server"))
        assertTrue(exception.message!!.contains("30000ms"))
        assertEquals("Slow Server", exception.serverName)
        assertEquals(30000L, exception.timeoutMs)
    }
    
    @Test
    fun mcpServerDisabledExceptionCreatesProperMessage() {
        val exception = McpServerDisabledException("Disabled Server")
        
        assertTrue(exception.message!!.contains("Disabled Server"))
        assertTrue(exception.message!!.contains("disabled"))
        assertEquals("Disabled Server", exception.serverName)
    }
    
    @Test
    fun mcpToolDisabledExceptionCreatesProperMessage() {
        val exception = McpToolDisabledException(
            toolName = "send_email",
            serverName = "Email Server"
        )
        
        assertTrue(exception.message!!.contains("send_email"))
        assertTrue(exception.message!!.contains("Email Server"))
        assertTrue(exception.message!!.contains("disabled"))
        assertEquals("send_email", exception.toolName)
        assertEquals("Email Server", exception.serverName)
    }
    
    @Test
    fun mcpExceptionsAreInstanceOfException() {
        val exceptions: List<Exception> = listOf(
            McpContextOverflowException(10, 500, 200, listOf("Server")),
            McpToolNotFoundException("tool"),
            McpInvalidResponseException("server", "details"),
            McpConnectionTimeoutException("server", 5000),
            McpServerDisabledException("server"),
            McpToolDisabledException("tool", "server")
        )
        
        exceptions.forEach { exception ->
            assertTrue(exception is McpException)
            assertTrue(exception is Exception)
        }
    }
}
