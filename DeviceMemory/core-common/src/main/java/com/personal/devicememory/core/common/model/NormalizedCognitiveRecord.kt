package com.personal.devicememory.core.common.model

import com.personal.devicememory.core.common.filter.SensitiveContentFilter
import com.personal.devicememory.core.common.text.TamilSemanticChunker
import com.personal.devicememory.core.common.text.TemporalAnchorExtractor

/**
 * 2026 Unified Cognitive Representation of a collected data item.
 *
 * Prepared specifically for the next-phase Temporal Knowledge Graph and Vector Index.
 */
data class NormalizedCognitiveRecord(
    val originalId: String? = null,
    val sourceType: SourceType,
    val collectedTimestamp: Long,
    val sanitizedText: String?,
    val hasSensitiveRedaction: Boolean,
    val isTamil: Boolean,
    val semanticChunks: List<TamilSemanticChunker.Chunk>,
    val temporalAnchors: List<TemporalAnchorExtractor.TemporalAnchor>,
    val metadataMap: Map<String, Any?>
)

/**
 * Normalizer pipeline that prepares raw collected items into AI-ready cognitive structures.
 */
object CognitiveRecordNormalizer {

    fun normalize(
        record: CollectedRecord,
        id: String? = null,
        metadataMap: Map<String, Any?> = emptyMap()
    ): NormalizedCognitiveRecord {
        val rawText = record.textContent
        val sanitized = SensitiveContentFilter.redact(rawText)
        val wasRedacted = rawText != null && rawText != sanitized

        val isTamil = sanitized?.let { TamilSemanticChunker.containsTamil(it) } ?: false
        val chunks = sanitized?.let { TamilSemanticChunker.chunk(it) } ?: emptyList()
        val anchors = sanitized?.let {
            TemporalAnchorExtractor.extractAnchors(it, record.timestamp)
        } ?: emptyList()

        return NormalizedCognitiveRecord(
            originalId = id,
            sourceType = record.sourceType,
            collectedTimestamp = record.timestamp,
            sanitizedText = sanitized,
            hasSensitiveRedaction = wasRedacted,
            isTamil = isTamil,
            semanticChunks = chunks,
            temporalAnchors = anchors,
            metadataMap = metadataMap
        )
    }
}
