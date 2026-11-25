package com.personal.devicememory.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.devicememory.R
import com.personal.devicememory.app.update.ShadowUpdateService
import com.personal.devicememory.app.update.ShadowUpdateState
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Floating translucent popup displayed whenever the user opens Shadow while an update is in progress.
 * - Shows exact live countdown until update completes.
 * - Pressing OK immediately closes the popup while the background update continues.
 * - If left open without pressing OK, automatically dismisses and launches Shadow when countdown reaches 0.
 */
class UpdateLockoutActivity : ComponentActivity() {

    private var userClickedOk = false
    private var receiverRegistered = false

    private val finishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!userClickedOk) {
                openMainAppAndFinish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ShadowUpdateState.isUpdating(this)) {
            openMainAppAndFinish()
            return
        }

        val filter = IntentFilter(ShadowUpdateService.ACTION_UPDATE_FINISHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(finishedReceiver, filter)
        }
        receiverRegistered = true

        setContent {
            val targetVersion = remember { ShadowUpdateState.getTargetVersion(this) }
            val targetTitle = remember { ShadowUpdateState.getTargetTitle(this) }
            val totalMs = remember { ShadowUpdateState.totalDurationMillis(this) }

            var remainingMs by remember { mutableLongStateOf(ShadowUpdateState.remainingMillis(this)) }
            var completed by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                while (!completed) {
                    val left = ShadowUpdateState.remainingMillis(this@UpdateLockoutActivity)
                    remainingMs = left
                    val stillUpdating = ShadowUpdateState.isUpdating(this@UpdateLockoutActivity)
                    if (left <= 0L || !stillUpdating) {
                        completed = true
                        if (!userClickedOk) {
                            openMainAppAndFinish()
                        }
                        break
                    }
                    delay(100L)
                }
            }

            UpdateLockoutPopupCard(
                version = targetVersion,
                title = targetTitle,
                remainingMs = remainingMs,
                totalMs = totalMs,
                onDismissOk = {
                    userClickedOk = true
                    finishAndRemoveTask()
                }
            )
        }
    }

    private fun openMainAppAndFinish() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(ShadowUpdateService.EXTRA_JUST_UPDATED, true)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(finishedReceiver)
            } catch (_: Throwable) {}
            receiverRegistered = false
        }
        super.onDestroy()
    }
}

@Composable
private fun UpdateLockoutPopupCard(
    version: String,
    title: String,
    remainingMs: Long,
    totalMs: Long,
    onDismissOk: () -> Unit
) {
    val secondsLeft = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)
    val progress = ((totalMs - remainingMs).toFloat() / totalMs.toFloat().coerceAtLeast(1f)).coerceIn(0.04f, 1f)

    val pulseTransition = rememberInfiniteTransition(label = "UpdateLogoPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xFF10101C),
        border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.36f)),
        shadowElevation = 20.dp
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.shadow_brand_mark),
                    contentDescription = "Shadow Logo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .scale(pulseScale)
                        .clip(RoundedCornerShape(13.dp))
                )
                Column {
                    Text(
                        "Updating Shadow",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF4F6FB)
                    )
                    if (version.isNotBlank()) {
                        Text(
                            "Version $version",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF38BDF8)
                        )
                    }
                }
            }

            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFCBD5E1)
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF080811),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.08f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (secondsLeft > 0) {
                            "App will open automatically in ${secondsLeft}s"
                        } else {
                            "Finalizing update... Opening app"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xFF1E1E32))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFF38BDF8), Color(0xFF7C3AED))
                                    )
                                )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = onDismissOk,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF7C3AED),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("OK", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
