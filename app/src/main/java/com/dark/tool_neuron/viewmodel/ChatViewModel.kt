package com.dark.tool_neuron.viewmodel

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dark.tool_neuron.di.AppContainer
import com.dark.tool_neuron.engine.GenerationEvent
import com.dark.tool_neuron.models.table_schema.McpServer
import com.dark.tool_neuron.repo.McpServerRepository
import com.dark.tool_neuron.service.McpClientService
import com.dark.tool_neuron.service.McpToolInfo
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import com.dark.tool_neuron.models.messages.ContentType
import com.dark.tool_neuron.models.messages.ImageGenerationMetrics
import com.dark.tool_neuron.models.messages.MessageContent
import com.dark.tool_neuron.models.messages.Messages
import com.dark.tool_neuron.models.messages.RagResultItem
import com.dark.tool_neuron.models.messages.Role
import com.dark.tool_neuron.models.table_schema.ModelConfig
import com.dark.tool_neuron.state.AppStateManager
import com.dark.tool_neuron.worker.ChatManager
import com.dark.tool_neuron.worker.DiffusionConfig
import com.dark.tool_neuron.worker.DiffusionInferenceParams
import com.dark.tool_neuron.worker.GenerationManager
import com.dark.tool_neuron.worker.LlmModelWorker
import com.mp.ai_gguf.models.DecodingMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatManager: ChatManager,
    private val generationManager: GenerationManager,
    private val mcpServerRepository: McpServerRepository,
    private val mcpClientService: McpClientService
) : ViewModel() {

    private val _messages = mutableStateListOf<Messages>()
    val messages: SnapshotStateList<Messages> = _messages

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId

    private var isNewConversation = true

    // Streaming state
    private val _streamingUserMessage = MutableStateFlow<String?>(null)
    val streamingUserMessage: StateFlow<String?> = _streamingUserMessage

    private val _streamingAssistantMessage = MutableStateFlow("")
    val streamingAssistantMessage: StateFlow<String> = _streamingAssistantMessage

    // Image generation state
    private val _streamingImage = MutableStateFlow<Bitmap?>(null)
    val streamingImage: StateFlow<Bitmap?> = _streamingImage

    private val _imageGenerationProgress = MutableStateFlow(0f)
    val imageGenerationProgress: StateFlow<Float> = _imageGenerationProgress

    private val _imageGenerationStep = MutableStateFlow("")
    val imageGenerationStep: StateFlow<String> = _imageGenerationStep

    // Track generation job for proper cancellation
    private var generationJob: Job? = null

    // Track current generation state
    private var currentUserMessage: Messages? = null
    private var currentGeneratedContent: String = ""
    private var currentMetrics: DecodingMetrics? = null
    private var currentImageMetrics: ImageGenerationMetrics? = null
    private var currentGeneratedImage: Bitmap? = null
    private var imageGenerationStartTime: Long = 0

    // Track if user message was already added to prevent duplicates
    private var userMessageAdded = false

    // UI state
    private val _showDynamicWindow = MutableStateFlow(false)
    val showDynamicWindow: StateFlow<Boolean> = _showDynamicWindow

    private val _showModelList = MutableStateFlow(false)
    val showModelList: StateFlow<Boolean> = _showModelList

    private val _currentGenerationType = MutableStateFlow(GenerationManager.ModelType.TEXT_GENERATION)
    val currentGenerationType: StateFlow<GenerationManager.ModelType> = _currentGenerationType

    // Model state
    val isTextModelLoaded = LlmModelWorker.isGgufModelLoaded
    val isImageModelLoaded = LlmModelWorker.isDiffusionModelLoaded

    // RAG state
    private val _isRagEnabled = MutableStateFlow(false)
    val isRagEnabled: StateFlow<Boolean> = _isRagEnabled

    private val _currentRagContext = MutableStateFlow<String?>(null)
    val currentRagContext: StateFlow<String?> = _currentRagContext

    private val _currentRagResults = MutableStateFlow<List<RagQueryDisplayResult>>(emptyList())
    val currentRagResults: StateFlow<List<RagQueryDisplayResult>> = _currentRagResults

    // MCP Tools state
    private var enabledTools: List<Pair<McpServer, McpToolInfo>> = emptyList()

    // ==================== RAG Controls ====================

    fun setRagEnabled(enabled: Boolean) {
        _isRagEnabled.value = enabled
    }

    fun setRagContext(context: String?, results: List<RagQueryDisplayResult> = emptyList()) {
        _currentRagContext.value = context
        _currentRagResults.value = results
    }

    fun clearRagContext() {
        _currentRagContext.value = null
        _currentRagResults.value = emptyList()
    }

    // ==================== Chat Management ====================

    fun startNewConversation() {
        _currentChatId.value = null
        _messages.clear()
        _streamingUserMessage.value = null
        _streamingAssistantMessage.value = ""
        _streamingImage.value = null
        _imageGenerationProgress.value = 0f
        _imageGenerationStep.value = ""
        currentUserMessage = null
        currentGeneratedContent = ""
        currentGeneratedImage = null
        currentMetrics = null
        currentImageMetrics = null
        userMessageAdded = false
        _error.value = null
        isNewConversation = true
        AppStateManager.setHasMessages(false)

        viewModelScope.launch {
            loadEnabledTools()
        }
    }

    fun loadChat(chatId: String) {
        viewModelScope.launch {
            _currentChatId.value = chatId
            chatManager.getChatMessages(chatId).onSuccess { loadedMessages ->
                _messages.clear()
                _messages.addAll(loadedMessages)
                AppStateManager.setHasMessages(loadedMessages.isNotEmpty())
            }.onFailure { e ->
                _error.value = "Failed to load chat: ${e.message}"
                AppStateManager.setError("Failed to load chat: ${e.message}")
            }
            loadEnabledTools()
        }
    }

    private suspend fun loadEnabledTools() {
        var servers: List<McpServer> = emptyList()
        try {
            mcpServerRepository.getEnabledServers().collect { list ->
                servers = list
                throw CancellationException("Got servers")
            }
        } catch (e: CancellationException) {
            // Expected
        } catch (e: Exception) {
            _error.value = "Failed to load MCP servers: ${e.message}"
            return
        }

        val toolsList = mutableListOf<Pair<McpServer, McpToolInfo>>()

        // Connect to each server and get tools
        // We do this sequentially or simply parallel could be better but let's keep it simple
        servers.forEach { server ->
            val result = mcpClientService.testConnection(server)
            if (result.success) {
                result.tools.forEach { tool ->
                    toolsList.add(server to tool)
                }
            }
        }

        enabledTools = toolsList
    }

    // ==================== Model Selection ====================

    fun switchToTextGeneration() {
        if (!generationManager.isTextModelLoaded()) {
            _error.value = "Text generation model not loaded"
            return
        }
        _currentGenerationType.value = GenerationManager.ModelType.TEXT_GENERATION
        generationManager.setCurrentModelType(GenerationManager.ModelType.TEXT_GENERATION)
    }

    fun switchToImageGeneration() {
        if (!generationManager.isImageModelLoaded()) {
            _error.value = "Image generation model not loaded"
            return
        }
        _currentGenerationType.value = GenerationManager.ModelType.IMAGE_GENERATION
        generationManager.setCurrentModelType(GenerationManager.ModelType.IMAGE_GENERATION)
    }

    // ==================== Text Generation ====================

    fun sendTextMessage(prompt: String, maxTokens: Int = 512) {
        if (!generationManager.isTextModelLoaded()) {
            _error.value = "Please load a text generation model first"
            AppStateManager.setError("Please load a text generation model first")
            return
        }

        if (_isGenerating.value) return

        _streamingUserMessage.value = prompt
        userMessageAdded = false

        viewModelScope.launch {
            if (isNewConversation) {
                currentUserMessage = Messages(
                    msgId = "",
                    role = Role.User,
                    content = MessageContent(
                        contentType = ContentType.Text,
                        content = prompt
                    ),
                    decodingMetrics = null
                )
                AppStateManager.setHasMessages(true)
                generateTextForNewChat(prompt, maxTokens)
            } else {
                val chatId = _currentChatId.value
                if (chatId == null) {
                    _error.value = "No chat selected"
                    AppStateManager.setError("No chat selected")
                    return@launch
                }

                chatManager.addUserMessage(chatId, prompt).onSuccess { userMessage ->
                    currentUserMessage = userMessage
                    AppStateManager.setHasMessages(true)
                    generateText(chatId, userMessage, maxTokens)
                }.onFailure { e ->
                    _error.value = "Failed to save message: ${e.message}"
                    _streamingUserMessage.value = null
                    currentUserMessage = null
                    AppStateManager.setError("Failed to save message: ${e.message}")
                }
            }
        }
    }

    private fun generateTextForNewChat(prompt: String, maxTokens: Int) {
        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _streamingAssistantMessage.value = ""
            currentGeneratedContent = ""
            currentMetrics = null

            AppStateManager.setGeneratingText()

            // Prepare Tools and System Prompt
            prepareToolsAndSystemPrompt()

            var tokenBuffer = StringBuilder()
            var tokenCount = 0
            var lastUpdateTime = System.currentTimeMillis()
            val updateIntervalMs = 50L
            val tokenBatchSize = 3

            try {
                // Prepend RAG context if available
                val finalPrompt = _currentRagContext.value?.let { ragContext ->
                    "$ragContext\n\n### User Query:\n$prompt"
                } ?: prompt

                generationManager.generateTextStreaming(finalPrompt, maxTokens).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            currentGeneratedContent += event.text
                            tokenBuffer.append(event.text)
                            tokenCount++

                            val currentTime = System.currentTimeMillis()
                            val shouldUpdate = tokenCount >= tokenBatchSize ||
                                    (currentTime - lastUpdateTime) >= updateIntervalMs

                            if (shouldUpdate) {
                                _streamingAssistantMessage.value = currentGeneratedContent
                                tokenBuffer.clear()
                                tokenCount = 0
                                lastUpdateTime = currentTime
                            }
                        }

                        is GenerationEvent.Done -> {
                            _streamingAssistantMessage.value = currentGeneratedContent
                            // Don't set _isGenerating.value = false here
                            // It will be set in resetStreamingState() after messages are added
                            createChatWithMessages(prompt, currentGeneratedContent, currentMetrics)
                        }

                        is GenerationEvent.Error -> {
                            handleTextGenerationError(prompt, event.message)
                        }

                        is GenerationEvent.Metrics -> {
                            currentMetrics = event.metrics
                        }

                        is GenerationEvent.ToolCall -> {
                            handleToolCall(prompt, event.name, event.args)
                        }
                    }
                }
            } catch (e: Exception) {
                handleTextGenerationException(prompt, e)
            }
        }
    }

    private fun generateText(chatId: String, userMessage: Messages, maxTokens: Int) {
        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _streamingAssistantMessage.value = ""
            currentGeneratedContent = ""
            currentMetrics = null

            AppStateManager.setGeneratingText()

            // Prepare Tools and System Prompt
            prepareToolsAndSystemPrompt()

            val tokenBuffer = StringBuilder()
            var tokenCount = 0
            var lastUpdateTime = System.currentTimeMillis()
            val updateIntervalMs = 50L
            val tokenBatchSize = 3

            try {
                var conversationPrompt = generationManager.buildConversationPrompt(
                    _messages, userMessage.content.content
                )

                // Prepend RAG context if available
                _currentRagContext.value?.let { ragContext ->
                    conversationPrompt = "$ragContext\n\n$conversationPrompt"
                }

                generationManager.generateTextStreaming(conversationPrompt, maxTokens)
                    .collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                currentGeneratedContent += event.text
                                tokenBuffer.append(event.text)
                                tokenCount++

                                val currentTime = System.currentTimeMillis()
                                val shouldUpdate = tokenCount >= tokenBatchSize ||
                                        (currentTime - lastUpdateTime) >= updateIntervalMs

                                if (shouldUpdate) {
                                    _streamingAssistantMessage.value = currentGeneratedContent
                                    tokenBuffer.clear()
                                    tokenCount = 0
                                    lastUpdateTime = currentTime
                                }
                            }

                            is GenerationEvent.Done -> {
                                _streamingAssistantMessage.value = currentGeneratedContent

                                // Add user message first if not already added
                                if (!userMessageAdded) {
                                    _messages.add(userMessage)
                                    userMessageAdded = true
                                }

                                // Convert current RAG results to serializable format
                                val ragResultItems = _currentRagResults.value.takeIf { it.isNotEmpty() }?.map { result ->
                                    RagResultItem(
                                        ragName = result.ragName,
                                        content = result.content,
                                        score = result.score,
                                        nodeId = result.nodeId
                                    )
                                }

                                // Add assistant message
                                val assistantMessage = Messages(
                                    role = Role.Assistant,
                                    content = MessageContent(
                                        contentType = ContentType.Text,
                                        content = currentGeneratedContent
                                    ),
                                    decodingMetrics = currentMetrics,
                                    ragResults = ragResultItems
                                )
                                _messages.add(assistantMessage)

                                // Save to vault
                                chatManager.addAssistantMessage(
                                    chatId, currentGeneratedContent, currentMetrics, ragResultItems
                                )

                                AppStateManager.setGenerationComplete()
                                // resetStreamingState will set _isGenerating = false
                                resetStreamingState()
                            }

                            is GenerationEvent.Error -> {
                                handleTextGenerationErrorExisting(chatId, userMessage, event.message)
                            }

                            is GenerationEvent.Metrics -> {
                                currentMetrics = event.metrics
                            }

                            is GenerationEvent.ToolCall -> {
                                handleToolCallExisting(chatId, userMessage, event.name, event.args)
                            }
                        }
                    }
            } catch (e: Exception) {
                handleTextGenerationExceptionExisting(chatId, userMessage, e)
            }
        }
    }

    // ==================== Image Generation ====================

    fun sendImageRequest(
        prompt: String,
        negativePrompt: String? = null,
        steps: Int? = null,
        cfgScale: Float? = null,
        seed: Long = -1L,
        width: Int? = null,
        height: Int? = null,
        scheduler: String? = null
    ) {
        if (!generationManager.isImageModelLoaded()) {
            _error.value = "Please load an image generation model first"
            AppStateManager.setError("Please load an image generation model first")
            return
        }

        if (_isGenerating.value) return

        viewModelScope.launch {
            // Get current diffusion model ID
            val modelId = LlmModelWorker.currentDiffusionModelId.value
            if (modelId == null) {
                _error.value = "Model configuration not found"
                return@launch
            }

            // Get config from repository
            val config = getModelConfig(modelId) // Assuming chatManager has access to repository

            val inferenceParams = if (config != null) {
                DiffusionInferenceParams.fromJson(config.modelInferenceParams)
            } else {
                DiffusionInferenceParams()
            }

            val diffusionConfig = if (config != null) {
                parseDiffusionConfig(config)
            } else {
                DiffusionConfig()
            }

            // Use provided values or fall back to stored config
            val finalNegativePrompt = negativePrompt ?: inferenceParams.negativePrompt
            val finalSteps = steps ?: inferenceParams.steps
            val finalCfgScale = cfgScale ?: inferenceParams.cfgScale
            val finalWidth = width ?: diffusionConfig.width
            val finalHeight = height ?: diffusionConfig.height
            val finalScheduler = scheduler ?: inferenceParams.scheduler

            _streamingUserMessage.value = prompt
            imageGenerationStartTime = System.currentTimeMillis()
            userMessageAdded = false

            if (isNewConversation) {
                currentUserMessage = Messages(
                    msgId = "",
                    role = Role.User,
                    content = MessageContent(
                        contentType = ContentType.Text,
                        content = "Generate image: $prompt"
                    )
                )
                AppStateManager.setHasMessages(true)
                generateImageForNewChat(
                    prompt,
                    finalNegativePrompt,
                    finalSteps,
                    finalCfgScale,
                    seed,
                    finalWidth,
                    finalHeight,
                    finalScheduler
                )
            } else {
                val chatId = _currentChatId.value
                if (chatId == null) {
                    _error.value = "No chat selected"
                    return@launch
                }

                chatManager.addUserMessage(chatId, "Generate image: $prompt").onSuccess { userMessage ->
                    currentUserMessage = userMessage
                    AppStateManager.setHasMessages(true)
                    generateImage(
                        chatId,
                        userMessage,
                        prompt,
                        finalNegativePrompt,
                        finalSteps,
                        finalCfgScale,
                        seed,
                        finalWidth,
                        finalHeight,
                        finalScheduler
                    )
                }.onFailure { e ->
                    _error.value = "Failed to save message: ${e.message}"
                    resetStreamingState()
                }
            }
        }
    }

    // Also add this helper if you don't have it
    suspend fun getModelConfig(modelId: String): ModelConfig? {
        return AppContainer.getModelRepository().getConfigByModelId(modelId)
    }

    private fun parseDiffusionConfig(config: ModelConfig): DiffusionConfig {
        if (config.modelLoadingParams == null) {
            return DiffusionConfig()
        }

        return try {
            val json = org.json.JSONObject(config.modelLoadingParams)
            DiffusionConfig(
                textEmbeddingSize = json.optInt("text_embedding_size", 768),
                runOnCpu = json.optBoolean("run_on_cpu", false),
                useCpuClip = json.optBoolean("use_cpu_clip", true),
                isPony = json.optBoolean("is_pony", false),
                httpPort = json.optInt("http_port", 8081),
                safetyMode = json.optBoolean("safety_mode", false),
                width = json.optInt("width", 512),
                height = json.optInt("height", 512)
            )
        } catch (e: Exception) {
            DiffusionConfig()
        }
    }

    private fun generateImageForNewChat(
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        width: Int,
        height: Int,
        scheduler: String
    ) {
        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _streamingImage.value = null
            _imageGenerationProgress.value = 0f
            currentGeneratedImage = null

            AppStateManager.setGeneratingImage()

            try {
                generationManager.generateImageStreaming(
                    prompt, negativePrompt, steps, cfgScale, seed, width, height, scheduler
                ).collect { event ->
                    when (event) {
                        is LlmModelWorker.DiffusionGenerationEvent.Progress -> {
                            _imageGenerationProgress.value = event.progress
                            _imageGenerationStep.value = "Step ${event.currentStep}/${event.totalSteps}"
                            event.intermediateImage?.let {
                                _streamingImage.value = it
                            }
                        }

                        is LlmModelWorker.DiffusionGenerationEvent.Complete -> {
                            _imageGenerationProgress.value = 1f
                            _streamingImage.value = event.image
                            currentGeneratedImage = event.image

                            val generationTime = System.currentTimeMillis() - imageGenerationStartTime
                            currentImageMetrics = ImageGenerationMetrics(
                                steps = steps,
                                cfgScale = cfgScale,
                                seed = event.seed,
                                width = event.width,
                                height = event.height,
                                scheduler = scheduler,
                                generationTimeMs = generationTime
                            )

                            _isGenerating.value = false

                            // Convert image to base64
                            val imageBase64 = generationManager.bitmapToBase64(event.image)
                            createChatWithImageMessage(
                                "Generate image: $prompt",
                                imageBase64,
                                prompt,
                                event.seed
                            )
                        }

                        is LlmModelWorker.DiffusionGenerationEvent.Error -> {
                            handleImageGenerationError(prompt, event.message)
                        }
                    }
                }
            } catch (e: Exception) {
                handleImageGenerationException(prompt, e)
            }
        }
    }

    private fun generateImage(
        chatId: String,
        userMessage: Messages,
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfgScale: Float,
        seed: Long,
        width: Int,
        height: Int,
        scheduler: String
    ) {
        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _streamingImage.value = null
            _imageGenerationProgress.value = 0f

            AppStateManager.setGeneratingImage()

            try {
                generationManager.generateImageStreaming(
                    prompt, negativePrompt, steps, cfgScale, seed, width, height, scheduler
                ).collect { event ->
                    when (event) {
                        is LlmModelWorker.DiffusionGenerationEvent.Progress -> {
                            _imageGenerationProgress.value = event.progress
                            _imageGenerationStep.value = "Step ${event.currentStep}/${event.totalSteps}"
                            event.intermediateImage?.let {
                                _streamingImage.value = it
                            }
                        }

                        is LlmModelWorker.DiffusionGenerationEvent.Complete -> {
                            _imageGenerationProgress.value = 1f
                            _streamingImage.value = event.image
                            _isGenerating.value = false

                            val generationTime = System.currentTimeMillis() - imageGenerationStartTime
                            currentImageMetrics = ImageGenerationMetrics(
                                steps = steps,
                                cfgScale = cfgScale,
                                seed = event.seed,
                                width = event.width,
                                height = event.height,
                                scheduler = scheduler,
                                generationTimeMs = generationTime
                            )

                            if (!userMessageAdded) {
                                _messages.add(userMessage)
                                userMessageAdded = true
                            }

                            val imageBase64 = generationManager.bitmapToBase64(event.image)
                            val imageMessage = Messages(
                                role = Role.Assistant,
                                content = MessageContent(
                                    contentType = ContentType.Image,
                                    content = "Generated image for: $prompt",
                                    imageData = imageBase64,
                                    imagePrompt = prompt,
                                    imageSeed = event.seed
                                ),
                                imageMetrics = currentImageMetrics
                            )
                            _messages.add(imageMessage)

                            // Save to vault
                            chatManager.addImageMessage(chatId, imageBase64, prompt, event.seed, currentImageMetrics)

                            AppStateManager.setGenerationComplete()
                            resetStreamingState()
                        }

                        is LlmModelWorker.DiffusionGenerationEvent.Error -> {
                            handleImageGenerationErrorExisting(chatId, userMessage, prompt, event.message)
                        }
                    }
                }
            } catch (e: Exception) {
                handleImageGenerationExceptionExisting(chatId, userMessage, prompt, e)
            }
        }
    }

    // ==================== Helper Functions ====================

    private suspend fun createChatWithMessages(
        userPrompt: String,
        assistantResponse: String,
        metrics: DecodingMetrics?
    ) {
        // Convert current RAG results to serializable format before clearing
        val ragResultItems = _currentRagResults.value.takeIf { it.isNotEmpty() }?.map { result ->
            RagResultItem(
                ragName = result.ragName,
                content = result.content,
                score = result.score,
                nodeId = result.nodeId
            )
        }

        chatManager.createNewChat().onSuccess { newChatId ->
            _currentChatId.value = newChatId
            isNewConversation = false

            chatManager.addUserMessage(newChatId, userPrompt).onSuccess { userMessage ->
                _messages.add(userMessage)
                userMessageAdded = true

                chatManager.addAssistantMessage(
                    newChatId, assistantResponse, metrics, ragResultItems
                ).onSuccess { assistantMessage ->
                    _messages.add(assistantMessage)
                    AppStateManager.setGenerationComplete()
                    resetStreamingState()
                }
            }.onFailure { e ->
                _error.value = "Failed to save chat: ${e.message}"
                AppStateManager.setError("Failed to save chat: ${e.message}")
            }
        }.onFailure { e ->
            _error.value = "Failed to create chat: ${e.message}"
            AppStateManager.setError("Failed to create chat: ${e.message}")
        }
    }

    private suspend fun createChatWithImageMessage(
        userPrompt: String,
        imageBase64: String,
        imagePrompt: String,
        seed: Long
    ) {
        chatManager.createNewChat().onSuccess { newChatId ->
            _currentChatId.value = newChatId
            isNewConversation = false

            chatManager.addUserMessage(newChatId, userPrompt).onSuccess { userMessage ->
                _messages.add(userMessage)
                userMessageAdded = true

                chatManager.addImageMessage(
                    newChatId, imageBase64, imagePrompt, seed, currentImageMetrics
                ).onSuccess { imageMessage ->
                    _messages.add(imageMessage)
                    AppStateManager.setGenerationComplete()
                    resetStreamingState()
                }
            }.onFailure { e ->
                _error.value = "Failed to save chat: ${e.message}"
            }
        }.onFailure { e ->
            _error.value = "Failed to create chat: ${e.message}"
        }
    }

    private fun handleTextGenerationError(prompt: String, errorMessage: String) {
        _streamingAssistantMessage.value = currentGeneratedContent
        _isGenerating.value = false
        _error.value = errorMessage
        AppStateManager.setError(errorMessage)

        if (currentGeneratedContent.isNotEmpty()) {
            viewModelScope.launch {
                createChatWithMessages(prompt, "Error: $errorMessage", null)
            }
        }
        resetStreamingState()
    }

    private fun handleTextGenerationException(prompt: String, exception: Exception) {
        _isGenerating.value = false
        _error.value = exception.message
        AppStateManager.setError(exception.message ?: "Unknown error")

        if (currentGeneratedContent.isNotEmpty()) {
            viewModelScope.launch {
                createChatWithMessages(prompt, "$currentGeneratedContent [incomplete]", currentMetrics)
            }
        }
        resetStreamingState()
    }

    private fun handleTextGenerationErrorExisting(chatId: String, userMessage: Messages, errorMessage: String) {
        _streamingAssistantMessage.value = currentGeneratedContent
        _isGenerating.value = false
        _error.value = errorMessage
        AppStateManager.setError(errorMessage)

        if (!userMessageAdded) {
            _messages.add(userMessage)
            userMessageAdded = true
        }

        val errorMsg = Messages(
            role = Role.Assistant,
            content = MessageContent(
                contentType = ContentType.Text,
                content = "Error: $errorMessage"
            )
        )
        _messages.add(errorMsg)
        resetStreamingState()
    }

    private fun handleTextGenerationExceptionExisting(chatId: String, userMessage: Messages, exception: Exception) {
        _isGenerating.value = false
        _error.value = exception.message
        AppStateManager.setError(exception.message ?: "Unknown error")

        if (currentGeneratedContent.isNotEmpty() && currentUserMessage != null) {
            if (!userMessageAdded) {
                _messages.add(currentUserMessage!!)
                userMessageAdded = true
            }

            val partialMessage = Messages(
                role = Role.Assistant,
                content = MessageContent(
                    contentType = ContentType.Text,
                    content = "$currentGeneratedContent [incomplete]"
                )
            )
            _messages.add(partialMessage)

            viewModelScope.launch {
                chatManager.addAssistantMessage(chatId, "$currentGeneratedContent [incomplete]", null)
            }
        }
        resetStreamingState()
    }

    private fun handleImageGenerationError(prompt: String, errorMessage: String) {
        _isGenerating.value = false
        _error.value = errorMessage
        AppStateManager.setError(errorMessage)
        resetStreamingState()
    }

    private fun handleImageGenerationException(prompt: String, exception: Exception) {
        _isGenerating.value = false
        _error.value = exception.message
        AppStateManager.setError(exception.message ?: "Unknown error")
        resetStreamingState()
    }

    private fun handleImageGenerationErrorExisting(chatId: String, userMessage: Messages, prompt: String, errorMessage: String) {
        _isGenerating.value = false
        _error.value = errorMessage
        AppStateManager.setError(errorMessage)

        if (!userMessageAdded) {
            _messages.add(userMessage)
            userMessageAdded = true
        }

        val errorMsg = Messages(
            role = Role.Assistant,
            content = MessageContent(
                contentType = ContentType.Text,
                content = "Error generating image: $errorMessage"
            )
        )
        _messages.add(errorMsg)
        resetStreamingState()
    }

    private fun handleImageGenerationExceptionExisting(chatId: String, userMessage: Messages, prompt: String, exception: Exception) {
        _isGenerating.value = false
        _error.value = exception.message
        AppStateManager.setError(exception.message ?: "Unknown error")

        if (!userMessageAdded) {
            _messages.add(userMessage)
            userMessageAdded = true
        }

        resetStreamingState()
    }

    private fun resetStreamingState() {
        _isGenerating.value = false
        _streamingUserMessage.value = null
        _streamingAssistantMessage.value = ""
        _streamingImage.value = null
        _imageGenerationProgress.value = 0f
        _imageGenerationStep.value = ""
        currentUserMessage = null
        currentGeneratedContent = ""
        currentGeneratedImage = null
        currentMetrics = null
        currentImageMetrics = null
        userMessageAdded = false
        // Clear RAG context and results after message is saved
        _currentRagContext.value = null
        _currentRagResults.value = emptyList()
    }

    // ==================== Generation Control ====================

    fun stop() {
        when (_currentGenerationType.value) {
            GenerationManager.ModelType.TEXT_GENERATION -> {
                generationManager.stopTextGeneration()
                handleTextStop()
            }
            GenerationManager.ModelType.IMAGE_GENERATION -> {
                generationManager.stopImageGeneration()
                handleImageStop()
            }
        }

        generationJob?.cancel()
        generationJob = null
        _isGenerating.value = false
        AppStateManager.setGenerationComplete()
    }

    private fun handleTextStop() {
        val chatId = _currentChatId.value

        if (chatId != null && currentUserMessage != null && currentGeneratedContent.isNotEmpty()) {
            viewModelScope.launch {
                if (!userMessageAdded) {
                    _messages.add(currentUserMessage!!)
                    userMessageAdded = true
                }

                val assistantMessage = Messages(
                    role = Role.Assistant,
                    content = MessageContent(
                        contentType = ContentType.Text,
                        content = "$currentGeneratedContent [stopped]"
                    ),
                    decodingMetrics = currentMetrics
                )
                _messages.add(assistantMessage)

                chatManager.addAssistantMessage(
                    chatId, "$currentGeneratedContent [stopped]", currentMetrics
                )
            }
        } else if (currentUserMessage != null && !userMessageAdded) {
            _messages.add(currentUserMessage!!)
            userMessageAdded = true
        }

        resetStreamingState()
    }

    private fun handleImageStop() {
        val chatId = _currentChatId.value

        if (chatId != null && currentUserMessage != null && currentGeneratedImage != null) {
            viewModelScope.launch {
                // Only add user message if it hasn't been added yet
                if (!userMessageAdded) {
                    _messages.add(currentUserMessage!!)
                    userMessageAdded = true
                }

                val imageBase64 = generationManager.bitmapToBase64(currentGeneratedImage!!)
                val imageMessage = Messages(
                    role = Role.Assistant,
                    content = MessageContent(
                        contentType = ContentType.Image,
                        content = "Image generation stopped",
                        imageData = imageBase64
                    ),
                    imageMetrics = currentImageMetrics
                )
                _messages.add(imageMessage)

                chatManager.addImageMessage(chatId, imageBase64, "", -1L, currentImageMetrics)
            }
        } else if (currentUserMessage != null && !userMessageAdded) {
            _messages.add(currentUserMessage!!)
            userMessageAdded = true
        }

        resetStreamingState()
    }

    private suspend fun prepareToolsAndSystemPrompt() {
        // Refresh tools before generation
        loadEnabledTools()

        if (enabledTools.isEmpty()) {
            generationManager.setToolsJson("")
            return
        }

        // 1. Set Tools JSON for grammar
        val toolsJsonArray = JSONArray()
        enabledTools.forEach { (_, tool) ->
            val toolJson = JSONObject()
            toolJson.put("type", "function")
            val functionJson = JSONObject()
            functionJson.put("name", tool.name)
            functionJson.put("description", tool.description ?: "")
            if (tool.inputSchema != null) {
                try {
                    functionJson.put("parameters", JSONObject(tool.inputSchema))
                } catch (e: Exception) {
                    // If schema is not JSON, put empty object
                     functionJson.put("parameters", JSONObject())
                }
            } else {
                 functionJson.put("parameters", JSONObject())
            }
            toolJson.put("function", functionJson)
            toolsJsonArray.put(toolJson)
        }
        generationManager.setToolsJson(toolsJsonArray.toString())

        // 2. Inject System Prompt
        // We append tool instructions to the system prompt
        val toolDescriptions = StringBuilder("You have access to the following tools:\n\n")
        enabledTools.forEach { (_, tool) ->
            toolDescriptions.append("- ${tool.name}: ${tool.description}\n")
            if (tool.inputSchema != null) {
                toolDescriptions.append("  Schema: ${tool.inputSchema}\n")
            }
            toolDescriptions.append("\n")
        }
        toolDescriptions.append("To use a tool, output a JSON object with 'name' and 'arguments'.\n")

        generationManager.setSystemPrompt(toolDescriptions.toString())
    }

    private suspend fun handleToolCall(prompt: String, toolName: String, args: String) {
        val tool = enabledTools.find { it.second.name == toolName }
        if (tool == null) {
            _streamingAssistantMessage.value = "Error: Tool '$toolName' not found."
            return
        }

        val (server, _) = tool

        // Add Assistant Message with Tool Call
        createChatWithMessages(prompt, "Calling tool: $toolName with $args", currentMetrics)

        // Call Tool
        try {
            val argsMap = try {
                 val json = JSONObject(args)
                 val map = mutableMapOf<String, Any>()
                 json.keys().forEach { key -> map[key] = json.get(key) }
                 map
            } catch (e: Exception) {
                emptyMap<String, Any>()
            }

            val result = mcpClientService.callTool(server, toolName, argsMap)
            val resultString = result.getOrElse { "Error calling tool: ${it.message}" }

            // Add Tool Message
            // We need to add a message with Role.Tool
            // Since createChatWithMessages creates a new chat, we need to add to that chat.
            // But handleToolCall is called inside generateTextForNewChat loop.
            // If we are here, createChatWithMessages was just called.
            // So _currentChatId should be set.
            val chatId = _currentChatId.value
            if (chatId != null) {
                 val toolMessage = Messages(
                    role = Role.Tool,
                    content = MessageContent(
                        contentType = ContentType.Text,
                        content = resultString
                    )
                )
                _messages.add(toolMessage)
                chatManager.addToolMessage(chatId, "Tool Output: $resultString")

                // Continue Generation (Recurse)
                generateText(chatId, _messages.last(), 512) // Re-trigger generation with updated history
            }
        } catch (e: Exception) {
             _error.value = "Tool execution failed: ${e.message}"
        }
    }

    private suspend fun handleToolCallExisting(chatId: String, userMessage: Messages, toolName: String, args: String) {
        val tool = enabledTools.find { it.second.name == toolName }
        if (tool == null) {
             // Handle error
             return
        }

        val (server, _) = tool

        // 1. Add Assistant Message (Tool Call)
        if (!userMessageAdded) {
            _messages.add(userMessage)
            userMessageAdded = true
        }

        val assistantMessage = Messages(
             role = Role.Assistant,
             content = MessageContent(
                 contentType = ContentType.Text,
                 content = "Calling tool: $toolName with $args"
             ),
             decodingMetrics = currentMetrics
        )
        _messages.add(assistantMessage)
        chatManager.addAssistantMessage(chatId, assistantMessage.content.content, currentMetrics)

        // 2. Call Tool
         try {
            val argsMap = try {
                 val json = JSONObject(args)
                 val map = mutableMapOf<String, Any>()
                 json.keys().forEach { key -> map[key] = json.get(key) }
                 map
            } catch (e: Exception) {
                emptyMap<String, Any>()
            }

            val result = mcpClientService.callTool(server, toolName, argsMap)
            val resultString = result.getOrElse { "Error calling tool: ${it.message}" }

            // 3. Add Tool Message
            val toolMessage = Messages(
                    role = Role.Tool,
                    content = MessageContent(
                        contentType = ContentType.Text,
                        content = resultString
                    )
                )
             _messages.add(toolMessage)
             chatManager.addToolMessage(chatId, "Tool Output: $resultString")

             // 4. Continue Generation
             continueGeneration(chatId, toolMessage)

        } catch (e: Exception) {
             _error.value = "Tool execution failed: ${e.message}"
        }
    }

    private fun continueGeneration(chatId: String, lastMessage: Messages) {
          generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _error.value = null
            _streamingAssistantMessage.value = ""
            currentGeneratedContent = ""
            currentMetrics = null

             try {
                // Manually build prompt for continuation
                val conversationPrompt = generationManager.buildPromptFromHistory(_messages) + "Assistant:"

                 generationManager.generateTextStreaming(conversationPrompt, 512)
                    .collect { event ->
                        when (event) {
                             is GenerationEvent.Token -> {
                                currentGeneratedContent += event.text
                                _streamingAssistantMessage.value = currentGeneratedContent
                            }
                            is GenerationEvent.Done -> {
                                val assistantMessage = Messages(
                                    role = Role.Assistant,
                                    content = MessageContent(
                                        contentType = ContentType.Text,
                                        content = currentGeneratedContent
                                    ),
                                    decodingMetrics = currentMetrics
                                )
                                _messages.add(assistantMessage)
                                chatManager.addAssistantMessage(
                                    chatId, currentGeneratedContent, currentMetrics, null
                                )
                                AppStateManager.setGenerationComplete()
                                resetStreamingState()
                            }
                            is GenerationEvent.Error -> {
                                handleTextGenerationErrorExisting(chatId, lastMessage, event.message)
                            }
                             is GenerationEvent.ToolCall -> {
                                handleToolCallExisting(chatId, lastMessage, event.name, event.args)
                            }
                            is GenerationEvent.Metrics -> {
                                currentMetrics = event.metrics
                            }
                        }
                    }
             } catch (e: Exception) {
                 resetStreamingState()
             }
          }
    }

    // ==================== UI Controls ====================

    fun clearMessages() {
        _messages.clear()
        resetStreamingState()
        _error.value = null
        AppStateManager.setHasMessages(false)
    }

    fun clearError() {
        _error.value = null
        AppStateManager.clearError()
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            chatManager.deleteMessage(messageId).onSuccess {
                _messages.removeIf { it.msgId == messageId }
            }.onFailure { e ->
                _error.value = "Failed to delete message: ${e.message}"
            }
        }
    }

    fun showDynamicWindow() {
        _showDynamicWindow.value = _showDynamicWindow.value.not()
    }

    fun hideDynamicWindow() {
        _showDynamicWindow.value = false
    }

    fun showModelList() {
        _showModelList.value = true
    }

    fun hideModelList() {
        _showModelList.value = false
    }
}