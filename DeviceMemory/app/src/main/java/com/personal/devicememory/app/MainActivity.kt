package com.personal.devicememory.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.personal.devicememory.app.service.ShadowTabWatcherService
import com.personal.devicememory.app.ui.MainScreen
import com.personal.devicememory.app.ui.MainViewModel
import com.personal.devicememory.app.update.ShadowUpdateService
import com.personal.devicememory.app.update.ShadowUpdateState
import com.personal.devicememory.pipeline.brain.LunaMemorySimulator

private val ShadowDarkColors = darkColorScheme(
    primary = Color(0xFF7C3AED),
    onPrimary = Color.White,
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF06050B),
    background = Color(0xFF06050B),
    onBackground = Color(0xFFF4F6FB),
    surface = Color(0xFF10101C),
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = Color(0xFF171728),
    onSurfaceVariant = Color(0xFF94A3B8),
    error = Color(0xFFFF5252)
)

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (::viewModel.isInitialized) {
            viewModel.loadState()
        }
    }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && ::viewModel.isInitialized) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrEmpty()) {
                viewModel.sendOfflineMessage(spoken)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block entry into Main Page while an OTA update is actively running
        if (redirectIfUpdating()) return

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val justUpdated = intent?.getBooleanExtra(ShadowUpdateService.EXTRA_JUST_UPDATED, false) == true
        if (justUpdated) {
            viewModel.onAppForegrounded(justUpdated = true)
        }

        setContent {
            MaterialTheme(colorScheme = ShadowDarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsState()

                    MainScreen(
                        uiState = uiState,
                        onNavigate = { viewModel.navigateTo(it) },
                        onSendOfflineMessage = { viewModel.sendOfflineMessage(it) },
                        onConnectToServer = { viewModel.startConnectToServer() },
                        onCancelHandshake = { viewModel.cancelHandshake() },
                        onDismissHandshake = { viewModel.dismissHandshake() },
                        onSaveServerUrl = { viewModel.updateServerUrl(it) },
                        onCheckUpdates = { viewModel.checkForUpdates() },
                        onInstallUpdate = { update ->
                            viewModel.installUpdate(update) {
                                finishAndRemoveTask()
                            }
                        },
                        onToggleSource = { sourceType, enabled ->
                            viewModel.toggleSource(sourceType, enabled)
                        },
                        onCollectOfflineNow = { viewModel.collectOfflineNow() },
                        onChatFeedback = { msgId, positive -> viewModel.submitChatFeedback(msgId, positive) },
                        onStartVoiceInput = { startVoiceInput(uiState.voiceTamilMode) },
                        onToggleVoiceLanguage = { viewModel.toggleVoiceLanguage() },
                        onStitchEpisodesNow = { viewModel.stitchEpisodesNow() },
                        onAskAboutMoment = { viewModel.askAboutMoment(it) },
                        onDismissStatus = { viewModel.clearStatus() },
                        onDeleteAll = { viewModel.deleteAllData() },
                        onShowDeleteConfirmation = { viewModel.showDeleteConfirmation() },
                        onDismissDeleteConfirmation = { viewModel.dismissDeleteConfirmation() },
                        onRequestPermissionsAndRefresh = {
                            requestAllPermissions()
                            viewModel.loadState()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (redirectIfUpdating()) return
        if (::viewModel.isInitialized) {
            val justUpdated = intent.getBooleanExtra(ShadowUpdateService.EXTRA_JUST_UPDATED, false)
            viewModel.onAppForegrounded(justUpdated)
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            startService(Intent(this, ShadowTabWatcherService::class.java))
        } catch (_: Throwable) {}
        LunaMemorySimulator.setForegroundActive(true)
        LunaMemorySimulator.startSimulationLoop(applicationContext)
    }

    override fun onStop() {
        LunaMemorySimulator.setForegroundActive(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (redirectIfUpdating()) return
        if (::viewModel.isInitialized) {
            viewModel.loadState()
        }
    }

    private fun startVoiceInput(tamilMode: Boolean) {
        val langTag = if (tamilMode) "ta-IN" else "en-IN"
        val prompt = if (tamilMode) {
            "தமிழில் அல்லது ஆங்கிலத்தில் கேளுங்கள்..."
        } else {
            "Ask Luna anything by voice..."
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            if (::viewModel.isInitialized) {
                viewModel.showStatus("Speech recognizer unavailable on this device")
            }
        }
    }

    private fun redirectIfUpdating(): Boolean {
        if (ShadowUpdateState.isUpdating(this)) {
            startActivity(
                Intent(this, UpdateLockoutActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
            finish()
            return true
        }
        return false
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.addAll(
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.addAll(
                listOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            )
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissions.addAll(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
