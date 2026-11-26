package com.personal.devicememory.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.devicememory.core.common.util.TimestampUtil
import com.personal.devicememory.core.database.entity.EpisodeEntity

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MomentsTimelineSlide(
    uiState: MainUiState,
    onAskAboutMoment: (String) -> Unit,
    onStitchNow: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = GlassSurface,
                border = BorderStroke(0.9.dp, CyanAccent.copy(alpha = 0.28f))
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Cross-Modal Life Moments",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                "${uiState.episodesCount} episodes stitched from ${uiState.totalRecords} memories",
                                style = MaterialTheme.typography.bodySmall,
                                color = CyanAccent
                            )
                        }
                        OutlinedButton(
                            onClick = onStitchNow,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(0.9.dp, CyanAccent.copy(alpha = 0.45f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Re-stitch",
                                tint = CyanAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                " Stitch",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyanAccent,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (uiState.topCausalChain.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = VioletAccent.copy(alpha = 0.14f),
                            border = BorderStroke(0.7.dp, VioletAccent.copy(alpha = 0.32f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Strongest STDP Habit Chain: ${uiState.topCausalChain}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFD8B4FE),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (uiState.episodes.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = GlassSurface,
                    border = BorderStroke(0.8.dp, HairlineBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoStories,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            "No Life Moments Yet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            "Luna stitches Photos, Videos, Audio, Notifications, and Location within ±20 min & 500m purely on-device. Enable permissions or tap Stitch above once 2+ memories exist.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onOpenPermissions,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VioletAccent)
                        ) {
                            Text("Configure Sensors", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            items(uiState.episodes, key = { it.episodeId }) { ep ->
                EpisodeTimelineCard(
                    episode = ep,
                    onAskAboutMoment = onAskAboutMoment
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpisodeTimelineCard(
    episode: EpisodeEntity,
    onAskAboutMoment: (String) -> Unit
) {
    val concepts = episode.keyConcepts
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    val sources = episode.dominantSources
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = episode.titleLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = CyanAccent.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = "${episode.recordCount} events",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyanAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = TimestampUtil.formatForDisplay(episode.startMillis),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )

            if (episode.primaryPlace.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MintSuccess,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = episode.primaryPlace,
                        style = MaterialTheme.typography.labelSmall,
                        color = MintSuccess
                    )
                }
            }

            Text(
                text = episode.narrativeSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )

            if (sources.isNotEmpty() || concepts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    sources.forEach { src ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = GlassElevated,
                            border = BorderStroke(0.6.dp, HairlineBorder)
                        ) {
                            Text(
                                text = src,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    concepts.forEach { concept ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = VioletAccent.copy(alpha = 0.18f),
                            border = BorderStroke(0.7.dp, VioletAccent.copy(alpha = 0.40f)),
                            modifier = Modifier.clickable { onAskAboutMoment(concept) }
                        ) {
                            Text(
                                text = "#$concept",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE9D5FF),
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            val askPrompt = concepts.firstOrNull() ?: episode.titleLabel.substringBefore("(").trim()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    onClick = { onAskAboutMoment(askPrompt) },
                    shape = RoundedCornerShape(999.dp),
                    color = CyanAccent.copy(alpha = 0.14f),
                    border = BorderStroke(0.7.dp, CyanAccent.copy(alpha = 0.38f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Ask Luna",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
