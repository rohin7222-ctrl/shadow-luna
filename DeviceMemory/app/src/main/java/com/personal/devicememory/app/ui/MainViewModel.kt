package com.personal.devicememory.app.ui

import android.app.Application
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.devicememory.app.update.ShadowUpdateService
import com.personal.devicememory.app.update.ShadowUpdateState
import com.personal.devicememory.app.widget.ShadowMemoryWidgetProvider
import com.personal.devicememory.collector.audio.AcousticSpeakerClusterer
import com.personal.devicememory.core.common.model.SourceType
import com.personal.devicememory.core.database.DeviceMemoryDatabase
import com.personal.devicememory.core.database.entity.CollectorConfigEntity
import com.personal.devicememory.core.database.entity.EpisodeEntity
import com.personal.devicememory.pipeline.CollectionWorker
import com.personal.devicememory.pipeline.WorkScheduler
import com.personal.devicememory.pipeline.brain.EpisodeStitchingEngine
import com.personal.devicememory.pipeline.brain.LunaMemorySimulator
import com.personal.devicememory.pipeline.brain.OnDeviceLunaBrain
import com.personal.devicememory.pipeline.sync.LunaServerConfig
import com.personal.devicememory.pipeline.sync.LunaSyncManager
import com.personal.devicememory.pipeline.sync.LunaUpdateItemDto
import com.personal.devicememory.pipeline.sync.LunaUpdatesClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class ActiveScreen {
    MAIN_CHAT,
    MOMENTS,
    NEURAL_LAB,
    LEARNING_PERMISSION,
    UPDATES
}

enum class HandshakePhase {
    IDLE,
    WAITING_FOR_LAPTOP,
    UPLOADING,
    COMPLETED,
    ERROR
}

data class HandshakeUiState(
    val phase: HandshakePhase = HandshakePhase.IDLE,
    val statusText: String = "",
    val uploadedRecords: Int = 0,
    val totalRecords: Int = 0,
    val uploadedTokens: Int = 0,
    val laptopConnected: Boolean = false,
    val diagnosticError: String? = null
)

data class OfflineChatMessage(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val learned: Boolean = false,
    val anchorConcept: String? = null,
    val primaryStrategy: String = "EXACT",
    val feedback: Int = 0 // 0 = neutral, 1 = helpful, -1 = wrong
)

data class SourceUiState(
    val sourceType: SourceType,
    val displayName: String,
    val enabled: Boolean,
    val permissionGranted: Boolean,
    val lastRunAt: Long?,
    val lastRunStatus: String,
    val errorMessage: String?,
    val recordCount: Int
)

data class MainUiState(
    val activeScreen: ActiveScreen = ActiveScreen.MAIN_CHAT,
    val messages: List<OfflineChatMessage> = emptyList(),
    val sources: List<SourceUiState> = emptyList(),
    val totalRecords: Int = 0,
    val totalTokens: Int = 0,
    val unsyncedRecords: Int = 0,
    val serverUrl: String = LunaServerConfig.DEFAULT_SERVER_URL,
    val canDrawOverlays: Boolean = false,
    val statusMessage: String? = null,
    val installedVersion: String = ShadowUpdateState.INITIAL_CLIENT_VERSION,
    val latestVersion: String = ShadowUpdateState.INITIAL_CLIENT_VERSION,
    val hasPendingUpdate: Boolean = false,
    val latestUpdate: LunaUpdateItemDto? = null,
    val updates: List<LunaUpdateItemDto> = emptyList(),
    val isCheckingUpdates: Boolean = false,
    val updateCheckError: String? = null,
    val handshake: HandshakeUiState = HandshakeUiState(),
    val showDeleteConfirmation: Boolean = false,
    val simulationRunning: Boolean = false,
    val simulationPasses: Int = 0,
    val consolidatedLinks: Int = 0,
    val neuralVectorsCount: Int = 0,
    val selfPlayAccuracyPct: Int = 0,
    val topPolicyStrategy: String = "",
    val jepaAccuracyPct: Int = 0,
    val curiosityTarget: String = "",
    val topCausalChain: String = "",
    val episodesCount: Int = 0,
    val episodes: List<EpisodeEntity> = emptyList(),
    val voiceTamilMode: Boolean = false,
    val avgSurprise: Double = 0.0,
    val avgFreeEnergy: Double = 0.0,
    val avgFfGoodness: Double = 0.0,
    val causalTransitionsCount: Int = 0,
    val weightExact: Double = 1.0,
    val weightGraph: Double = 1.0,
    val weightVector: Double = 1.0,
    val weightTemporal: Double = 1.0,
    val dreamedChains: List<String> = emptyList(),
    val isBurstSimulating: Boolean = false,
    val topSimulatedTopics: List<String> = emptyList(),
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private var handshakeJob: Job? = null
    private var statusClearJob: Job? = null

    private val _uiState = MutableStateFlow(
        MainUiState(
            serverUrl = LunaServerConfig.getServerUrl(application),
            installedVersion = ShadowUpdateState.getInstalledVersion(application),
            canDrawOverlays = Settings.canDrawOverlays(application)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadState()
        viewModelScope.launch {
            LunaMemorySimulator.snapshot.collect { snap ->
                _uiState.update {
                    it.copy(
                        simulationRunning = snap.isSimulating,
                        simulationPasses = snap.totalPasses,
                        consolidatedLinks = snap.associationsCount,
                        neuralVectorsCount = snap.neuralVectorsCount,
                        selfPlayAccuracyPct = snap.selfPlayAccuracyPct,
                        topPolicyStrategy = snap.topPolicyStrategy,
                        jepaAccuracyPct = snap.jepaAccuracyPct,
                        curiosityTarget = snap.curiosityTarget,
                        topCausalChain = snap.topCausalChain,
                        episodesCount = snap.episodesCount,
                        avgSurprise = snap.avgSurprise,
                        avgFreeEnergy = snap.avgFreeEnergy,
                        avgFfGoodness = snap.avgFfGoodness,
                        causalTransitionsCount = snap.causalTransitionsCount,
                        weightExact = snap.weightExact,
                        weightGraph = snap.weightGraph,
                        weightVector = snap.weightVector,
                        weightTemporal = snap.weightTemporal,
                        dreamedChains = snap.dreamedChains,
                        topSimulatedTopics = snap.topSimulatedConcepts
                    )
                }
            }
        }
    }

    fun navigateTo(screen: ActiveScreen) {
        _uiState.update { it.copy(activeScreen = screen) }
        when (screen) {
            ActiveScreen.MOMENTS, ActiveScreen.NEURAL_LAB, ActiveScreen.LEARNING_PERMISSION, ActiveScreen.MAIN_CHAT -> loadState()
            ActiveScreen.UPDATES -> checkForUpdates()
        }
    }

    fun runSimulationBurst(passes: Int = 5) {
        val app = getApplication<Application>()
        if (_uiState.value.isBurstSimulating) return
        viewModelScope.launch {
            _uiState.update { it.copy(isBurstSimulating = true) }
            try {
                LunaMemorySimulator.runSimulationBurst(app, passes = passes)
                loadState()
                showStatus("Completed $passes dream consolidation passes!")
            } catch (e: Throwable) {
                Log.w(TAG, "Burst simulation warning", e)
            } finally {
                _uiState.update { it.copy(isBurstSimulating = false) }
            }
        }
    }

    fun toggleVoiceLanguage() {
        _uiState.update { it.copy(voiceTamilMode = !it.voiceTamilMode) }
    }

    fun showStatus(msg: String) {
        statusClearJob?.cancel()
        _uiState.update { it.copy(statusMessage = msg) }
        statusClearJob = viewModelScope.launch {
            delay(3500L)
            _uiState.update { st -> if (st.statusMessage == msg) st.copy(statusMessage = null) else st }
        }
    }

    fun clearStatus() {
        statusClearJob?.cancel()
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun stitchEpisodesNow() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val count = EpisodeStitchingEngine.stitchRecentFromDb(app, limit = 180)
                loadState()
                showStatus("Stitched $count life moments on phone.")
            } catch (e: Throwable) {
                Log.w(TAG, "Episode stitching failed", e)
            }
        }
    }

    fun askAboutMoment(prompt: String) {
        _uiState.update { it.copy(activeScreen = ActiveScreen.MAIN_CHAT) }
        sendOfflineMessage(prompt)
    }

    fun onAppForegrounded(justUpdated: Boolean) {
        val app = getApplication<Application>()
        _uiState.update {
            it.copy(
                activeScreen = ActiveScreen.MAIN_CHAT,
                installedVersion = ShadowUpdateState.getInstalledVersion(app),
            )
        }
        loadState()
    }

    /**
     * Runs 100% offline on-device against encrypted SQLCipher vault. Zero Wi-Fi required.
     */
    fun sendOfflineMessage(rawQuery: String) {
        val q = rawQuery.trim()
        if (q.isEmpty()) return
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        val sessionHistory = _uiState.value.messages
            .filter { it.isUser }
            .takeLast(3)
            .map { it.text }
        _uiState.update {
            it.copy(
                messages = it.messages + OfflineChatMessage(id = now, isUser = true, text = q)
            )
        }
        viewModelScope.launch {
            val reply = OnDeviceLunaBrain.askOffline(app, q, sessionHistory)
            _uiState.update {
                it.copy(
                    totalRecords = reply.totalMemories,
                    totalTokens = reply.totalTokens,
                    simulationPasses = reply.simulationPasses,
                    consolidatedLinks = reply.associationsCount,
                    neuralVectorsCount = reply.neuralVectorsCount,
                    selfPlayAccuracyPct = reply.selfPlayAccuracyPct,
                    jepaAccuracyPct = reply.jepaAccuracyPct,
                    curiosityTarget = reply.curiosityTarget,
                    messages = it.messages + OfflineChatMessage(
                        id = System.currentTimeMillis() + 1,
                        isUser = false,
                        text = reply.response,
                        learned = reply.learned,
                        anchorConcept = reply.anchorConcept,
                        primaryStrategy = reply.primaryStrategy
                    )
                )
            }
        }
    }

    fun submitChatFeedback(messageId: Long, isPositive: Boolean) {
        val target = _uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (target.isUser || target.feedback != 0) return
        val vote = if (isPositive) 1 else -1
        _uiState.update { st ->
            st.copy(
                messages = st.messages.map { m ->
                    if (m.id == messageId) m.copy(feedback = vote) else m
                }
            )
        }
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                OnDeviceLunaBrain.recordChatFeedback(app, target.anchorConcept, target.primaryStrategy, isPositive)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 3rd 3-dot option: Connect to Server 2-way handshake.
     * Waits for Laptop "Connect to App", then uploads learning progress with live progress display.
     */
    fun startConnectToServer() {
        val app = getApplication<Application>()
        handshakeJob?.cancel()
        _uiState.update {
            it.copy(
                handshake = HandshakeUiState(
                    phase = HandshakePhase.WAITING_FOR_LAPTOP,
                    statusText = "Searching for Laptop on Wi-Fi...",
                    laptopConnected = false,
                    diagnosticError = null
                )
            )
        }

        handshakeJob = viewModelScope.launch {
            while (isActive) {
                val probe = LunaSyncManager.discoverAndSignalMobileReady(app)
                val statusDto = probe.status
                if (probe.resolvedUrl.isNotBlank() && probe.resolvedUrl != _uiState.value.serverUrl) {
                    _uiState.update { it.copy(serverUrl = probe.resolvedUrl) }
                }

                if (statusDto != null && statusDto.phase == "TRANSFERRING") {
                    break
                }
                if (statusDto != null) {
                    _uiState.update {
                        it.copy(
                            handshake = it.handshake.copy(
                                phase = HandshakePhase.WAITING_FOR_LAPTOP,
                                laptopConnected = true,
                                diagnosticError = null,
                                statusText = "Connected to Laptop • Now click 'Connect to App' on Shadow Desktop."
                            )
                        )
                    }
                    delay(1000L)
                } else {
                    _uiState.update {
                        it.copy(
                            handshake = it.handshake.copy(
                                phase = HandshakePhase.WAITING_FOR_LAPTOP,
                                laptopConnected = false,
                                diagnosticError = probe.diagnosticError,
                                statusText = "Cannot reach Laptop on Wi-Fi"
                            )
                        )
                    }
                    delay(2000L)
                }
            }

            if (!isActive) return@launch

            // Both Mobile & Laptop pressed Connect -> Start Uploading Learning Progress!
            _uiState.update {
                it.copy(
                    handshake = HandshakeUiState(
                        phase = HandshakePhase.UPLOADING,
                        statusText = "Uploading learning progress..."
                    )
                )
            }

            val result = LunaSyncManager.uploadLearningProgressWithHandshake(app) { uploaded, total, tokens ->
                _uiState.update {
                    it.copy(
                        handshake = HandshakeUiState(
                            phase = HandshakePhase.UPLOADING,
                            statusText = "Uploading learning progress...",
                            uploadedRecords = uploaded,
                            totalRecords = total,
                            uploadedTokens = tokens
                        )
                    )
                }
            }

            if (result.success) {
                _uiState.update {
                    it.copy(
                        handshake = HandshakeUiState(
                            phase = HandshakePhase.COMPLETED,
                            statusText = result.message,
                            uploadedRecords = result.syncedCount,
                            totalRecords = result.syncedCount,
                            uploadedTokens = result.syncedTokens
                        )
                    )
                }
                loadState()
            } else {
                _uiState.update {
                    it.copy(
                        handshake = HandshakeUiState(
                            phase = HandshakePhase.ERROR,
                            statusText = result.message
                        )
                    )
                }
            }
        }
    }

    fun cancelHandshake() {
        handshakeJob?.cancel()
        val app = getApplication<Application>()
        viewModelScope.launch {
            LunaSyncManager.cancelHandshake(app)
        }
        _uiState.update { it.copy(handshake = HandshakeUiState(phase = HandshakePhase.IDLE)) }
    }

    fun dismissHandshake() {
        _uiState.update { it.copy(handshake = HandshakeUiState(phase = HandshakePhase.IDLE)) }
    }

    fun updateServerUrl(newUrl: String): Result<String> {
        val app = getApplication<Application>()
        val result = LunaServerConfig.setServerUrl(app, newUrl)
        result.onSuccess { normalized ->
            _uiState.update {
                it.copy(
                    serverUrl = normalized,
                    statusMessage = "Laptop URL saved: $normalized"
                )
            }
        }.onFailure { err ->
            _uiState.update {
                it.copy(statusMessage = err.message ?: "Invalid local server URL")
            }
        }
        return result
    }

    fun checkForUpdates() {
        val app = getApplication<Application>()
        val currentInstalled = ShadowUpdateState.getInstalledVersion(app)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isCheckingUpdates = true,
                    updateCheckError = null,
                    installedVersion = currentInstalled
                )
            }
            val result = LunaUpdatesClient.fetchUpdates(app, currentInstalled)
            result.onSuccess { resp ->
                _uiState.update {
                    it.copy(
                        isCheckingUpdates = false,
                        installedVersion = currentInstalled,
                        latestVersion = resp.latest_version,
                        hasPendingUpdate = resp.has_update,
                        latestUpdate = resp.latest_update,
                        updates = resp.updates,
                        updateCheckError = null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isCheckingUpdates = false,
                        updateCheckError = err.message ?: "Laptop server offline"
                    )
                }
            }
        }
    }

    fun installUpdate(update: LunaUpdateItemDto, onCloseAppForUpdate: () -> Unit) {
        val app = getApplication<Application>()
        ShadowUpdateService.start(
            context = app,
            version = update.version,
            title = update.title,
            durationSeconds = update.estimated_seconds
        )
        onCloseAppForUpdate()
    }

    fun loadState() {
        val app = getApplication<Application>()
        val db = DeviceMemoryDatabase.getInstance(app)
        viewModelScope.launch {
            try {
                val sources = SourceType.entries.map { st ->
                    val config = try {
                        db.collectorConfigDao().getConfig(st.name)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Error fetching config for ${st.name}", e)
                        null
                    } ?: CollectorConfigEntity(sourceType = st.name)

                    val count = try {
                        db.collectedRecordDao().countBySource(st.name)
                    } catch (_: Throwable) {
                        0
                    }

                    SourceUiState(
                        sourceType = st,
                        displayName = CollectorStatusHelper.displayName(st),
                        enabled = config.enabled == 1,
                        permissionGranted = CollectorStatusHelper.hasPermission(app, st),
                        lastRunAt = config.lastRunAt,
                        lastRunStatus = config.lastRunStatus,
                        errorMessage = config.errorMessage,
                        recordCount = count
                    )
                }

                val unsynced = try { db.collectedRecordDao().countUnsynced() } catch (_: Throwable) { 0 }
                val tokenCount = try { db.learnedTokenDao().countTokens() } catch (_: Throwable) { 0 }
                val recentEpisodes = try { db.brainSimulationDao().getRecentEpisodes(40) } catch (_: Throwable) { emptyList() }
                val episodesCount = try { db.brainSimulationDao().countEpisodes() } catch (_: Throwable) { recentEpisodes.size }

                _uiState.update {
                    it.copy(
                        sources = sources,
                        totalRecords = sources.sumOf { s -> s.recordCount },
                        totalTokens = tokenCount,
                        unsyncedRecords = unsynced,
                        episodesCount = episodesCount,
                        episodes = recentEpisodes,
                        serverUrl = LunaServerConfig.getServerUrl(app),
                        canDrawOverlays = Settings.canDrawOverlays(app),
                        installedVersion = ShadowUpdateState.getInstalledVersion(app)
                    )
                }
                ShadowMemoryWidgetProvider.refreshAllWidgets(app)
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error in loadState", e)
            }
        }
    }

    fun toggleSource(sourceType: SourceType, enabled: Boolean) {
        val db = DeviceMemoryDatabase.getInstance(getApplication())
        viewModelScope.launch {
            try {
                db.collectorConfigDao().setEnabled(sourceType.name, if (enabled) 1 else 0)
                loadState()
            } catch (e: Throwable) {
                Log.e(TAG, "Error toggling source $sourceType", e)
            }
        }
    }

    /**
     * Runs collectors strictly offline on device and indexes tokens locally.
     */
    fun collectOfflineNow() {
        val app = getApplication<Application>()
        try {
            WorkScheduler.runNow(app)
            _uiState.update { it.copy(statusMessage = "Collecting & analyzing offline on phone...") }
        } catch (e: Throwable) {
            Log.e(TAG, "Error running WorkScheduler.runNow", e)
            return
        }
        viewModelScope.launch {
            val wm = androidx.work.WorkManager.getInstance(app)
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                delay(1200)
                val infos = try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        wm.getWorkInfosForUniqueWork("${CollectionWorker.WORK_NAME}_manual").get()
                    }
                } catch (_: Throwable) {
                    emptyList()
                }
                loadState()
                if (infos.isNotEmpty() && infos.all { it.state.isFinished }) {
                    val succeeded = infos.any { it.state == androidx.work.WorkInfo.State.SUCCEEDED }
                    _uiState.update {
                        it.copy(
                            statusMessage = if (succeeded) "Learned locally in encrypted vault." else "Collection finished with retries."
                        )
                    }
                    return@launch
                }
            }
            loadState()
            _uiState.update { it.copy(statusMessage = "Learned locally in encrypted vault.") }
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteAllData() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                LunaMemorySimulator.stopSimulationLoopAndWait()
                DeviceMemoryDatabase.destroyAll(app)
                AcousticSpeakerClusterer.clear(app)
                val newDb = DeviceMemoryDatabase.getInstance(app)
                val defaults = SourceType.entries.map { st ->
                    CollectorConfigEntity(sourceType = st.name, enabled = 0, lastRunStatus = "NEVER")
                }
                newDb.collectorConfigDao().insertIfMissing(defaults)
                _uiState.update {
                    it.copy(
                        messages = emptyList(),
                        totalRecords = 0,
                        totalTokens = 0,
                        unsyncedRecords = 0,
                        simulationPasses = 0,
                        consolidatedLinks = 0,
                        neuralVectorsCount = 0,
                        selfPlayAccuracyPct = 0,
                        topPolicyStrategy = "",
                        jepaAccuracyPct = 0,
                        curiosityTarget = "",
                        topCausalChain = "",
                        episodesCount = 0,
                        episodes = emptyList(),
                        avgSurprise = 0.0,
                        avgFreeEnergy = 0.0,
                        avgFfGoodness = 0.0,
                        causalTransitionsCount = 0,
                        weightExact = 1.0,
                        weightGraph = 1.0,
                        weightVector = 1.0,
                        weightTemporal = 1.0,
                        dreamedChains = emptyList(),
                        isBurstSimulating = false,
                        topSimulatedTopics = emptyList(),
                        statusMessage = "Phone vault & offline AI wiped to 0"
                    )
                }
                ShadowMemoryWidgetProvider.refreshAllWidgets(app)
                LunaMemorySimulator.startSimulationLoop(app)
                loadState()
            } catch (e: Throwable) {
                Log.e(TAG, "Error deleting all data", e)
            }
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
