package com.dark.tool_neuron.viewmodel

import com.dark.tool_neuron.models.table_schema.McpServer
import com.dark.tool_neuron.repo.McpServerRepository
import com.dark.tool_neuron.service.McpClientService
import com.dark.tool_neuron.service.McpTestResult
import com.dark.tool_neuron.service.McpToolInfo
import com.dark.tool_neuron.worker.ChatManager
import com.dark.tool_neuron.worker.GenerationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelToolTest {

    private val chatManager: ChatManager = mockk(relaxed = true)
    private val generationManager: GenerationManager = mockk(relaxed = true)
    private val mcpServerRepository: McpServerRepository = mockk(relaxed = true)
    private val mcpClientService: McpClientService = mockk(relaxed = true)

    private lateinit var viewModel: ChatViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock GenerationManager properties
        every { generationManager.isTextModelLoaded() } returns true

        viewModel = ChatViewModel(
            chatManager,
            generationManager,
            mcpServerRepository,
            mcpClientService
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadEnabledTools fetches tools and sets tools json`() = runTest {
        // Given
        val server = McpServer(
            id = "1", name = "Test Server", url = "http://test.com",
            transportType = com.dark.tool_neuron.models.table_schema.McpTransportType.SSE,
            apiKey = null, isEnabled = true, createdAt = 0, updatedAt = 0, description = ""
        )
        val tool = McpToolInfo(name = "test_tool", description = "A test tool", inputSchema = "{}")

        coEvery { mcpServerRepository.getEnabledServers() } returns flowOf(listOf(server))
        coEvery { mcpClientService.testConnection(server) } returns McpTestResult(
            success = true,
            message = "Connected",
            tools = listOf(tool)
        )

        // When
        viewModel.startNewConversation()
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        // Verify setToolsJson was called
        // We need to capture arguments or just verify call
        coVerify { generationManager.setToolsJson(any()) }
        coVerify { generationManager.setSystemPrompt(any()) }
    }
}
