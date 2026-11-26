package com.personal.devicememory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NeuralLabSlide(
    uiState: MainUiState,
    onRunBurst: () -> Unit,
    onAskTopic: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header + Dream Consolidation Burst Trigger
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = GlassSurface,
                border = BorderStroke(0.9.dp, CyanAccent.copy(alpha = 0.34f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Psychology,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    "On-Device Neural Lab",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    if (uiState.simulationRunning) "Background dream loop active • Pass #${uiState.simulationPasses}"
                                    else "Standby • Pass #${uiState.simulationPasses}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.simulationRunning) MintSuccess else TextMuted
                                )
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = VioletAccent.copy(alpha = 0.18f),
                            border = BorderStroke(0.7.dp, VioletAccent.copy(alpha = 0.40f))
                        ) {
                            Text(
                                "${uiState.neuralVectorsCount} vecs",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE9D5FF),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (uiState.isBurstSimulating) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(999.dp)),
                            color = CyanAccent,
                            trackColor = GlassElevated
                        )
                    }

                    Button(
                        onClick = onRunBurst,
                        enabled = !uiState.isBurstSimulating && uiState.totalRecords > 0,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = VioletAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (uiState.isBurstSimulating) " Running Dream Burst..." else " Run Dream Consolidation Burst (×5)",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Core Subsystem Metrics Grid
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = GlassSurface,
                border = BorderStroke(0.8.dp, HairlineBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "2026 Continual Learning Telemetry",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    MetricBarRow("Contrastive JEPA World Model Accuracy", uiState.jepaAccuracyPct / 100f, "${uiState.jepaAccuracyPct}%", CyanAccent)
                    MetricBarRow("GRPO Self-Play Recall Accuracy", uiState.selfPlayAccuracyPct / 100f, "${uiState.selfPlayAccuracyPct}%", MintSuccess)
                    MetricBarRow("Forward-Forward Local Goodness", uiState.avgFfGoodness.toFloat().coerceIn(0f, 1f), String.format(Locale.US, "%.2f", uiState.avgFfGoodness), VioletAccent)
                    MetricBarRow("Titans Epistemic Surprise", uiState.avgSurprise.toFloat().coerceIn(0f, 1f), String.format(Locale.US, "%.2f", uiState.avgSurprise), Color(0xFFF59E0B))
                    MetricBarRow("Active Inference Free Energy", uiState.avgFreeEnergy.toFloat().coerceIn(0f, 1f), String.format(Locale.US, "%.2f", uiState.avgFreeEnergy), CoralDanger)
                }
            }
        }

        // GRPO Policy Strategy Weights
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = GlassSurface,
                border = BorderStroke(0.8.dp, HairlineBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "GRPO Policy Weights (Self-Play Balanced)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    MetricBarRow("Exact Match", (uiState.weightExact / 2.5).toFloat(), String.format(Locale.US, "×%.2f", uiState.weightExact), TextPrimary)
                    MetricBarRow("Graph Co-occurrence", (uiState.weightGraph / 2.5).toFloat(), String.format(Locale.US, "×%.2f", uiState.weightGraph), CyanAccent)
                    MetricBarRow("Matryoshka 32D Vector", (uiState.weightVector / 2.5).toFloat(), String.format(Locale.US, "×%.2f", uiState.weightVector), VioletAccent)
                    MetricBarRow("Temporal Window", (uiState.weightTemporal / 2.5).toFloat(), String.format(Locale.US, "×%.2f", uiState.weightTemporal), MintSuccess)
                }
            }
        }

        // Curiosity, STDP & Hippocampal Dream Chains
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = GlassSurface,
                border = BorderStroke(0.8.dp, HairlineBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Curiosity, STDP & Dreamed Rollouts",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        "Epistemic Curiosity Target: ${uiState.curiosityTarget.ifBlank { "Waiting for memories" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanAccent
                    )
                    Text(
                        "Strongest STDP Causal Link (${uiState.causalTransitionsCount} total): ${uiState.topCausalChain.ifBlank { "None yet" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                    if (uiState.dreamedChains.isNotEmpty()) {
                        Text(
                            "Dreamed Multi-Hop Trajectories:",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        uiState.dreamedChains.forEach { chain ->
                            Text(
                                "• $chain",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE9D5FF)
                            )
                        }
                    }
                    if (uiState.topSimulatedTopics.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uiState.topSimulatedTopics.forEach { topic ->
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = CyanAccent.copy(alpha = 0.14f),
                                    border = BorderStroke(0.7.dp, CyanAccent.copy(alpha = 0.35f)),
                                    modifier = Modifier.clickable { onAskTopic(topic) }
                                ) {
                                    Text(
                                        text = "#$topic",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanAccent,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBarRow(
    label: String,
    fraction: Float,
    valueText: String,
    barColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Text(valueText, style = MaterialTheme.typography.labelSmall, color = barColor, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(GlassElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(barColor)
            )
        }
    }
}
