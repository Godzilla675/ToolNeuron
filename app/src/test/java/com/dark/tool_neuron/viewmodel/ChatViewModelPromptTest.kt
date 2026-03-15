package com.dark.tool_neuron.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelPromptTest {

    @Test
    fun taskTokenGuidanceIsOnlyIncludedWhenMcpToolsAreAvailable() {
        assertTrue(ChatViewModel.buildTaskTokenGuidance(hasMcpTools = true).contains("task-scoped tokens"))
        assertTrue(ChatViewModel.buildTaskTokenGuidance(hasMcpTools = true).contains("user marks the task complete"))
        assertTrue(ChatViewModel.buildTaskTokenGuidance(hasMcpTools = true).contains("minimum permissions"))
        assertTrue(ChatViewModel.buildTaskTokenGuidance(hasMcpTools = true).contains("revoke yourself"))
        assertTrue(ChatViewModel.buildTaskTokenGuidance(hasMcpTools = false).isEmpty())
    }

    @Test
    fun planningPromptMentionsTaskTokensForMcpSessions() {
        val prompt = ChatViewModel.buildAgentPlanningPrompt(
            toolDescriptions = "zapier_mcp_google_docs_create_document_from_text",
            hasMcpTools = true
        )

        assertTrue(prompt.contains("Available tools:"))
        assertTrue(prompt.contains("zapier_mcp_google_docs_create_document_from_text"))
        assertTrue(prompt.contains("Task tokens:"))
        assertTrue(prompt.contains("Write a 1-2 sentence plan"))
    }

    @Test
    fun planningPromptStaysCompactWithoutMcpTools() {
        val prompt = ChatViewModel.buildAgentPlanningPrompt(
            toolDescriptions = "web_search",
            hasMcpTools = false
        )

        assertTrue(prompt.contains("web_search"))
        assertFalse(prompt.contains("Task tokens:"))
    }
}
