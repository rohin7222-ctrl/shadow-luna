package com.personal.devicememory.core.common.model

/**
 * Domain model for a collected record before it is persisted.
 * Each collector maps its raw data into this form.
 */
data class CollectedRecord(
    val sourceType: SourceType,
    val timestamp: Long,            // epoch millis UTC
    val textContent: String? = null,
    val metadataJson: String,       // JSON blob
)
