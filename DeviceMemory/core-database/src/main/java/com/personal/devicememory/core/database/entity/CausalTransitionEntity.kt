package com.personal.devicememory.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Asymmetric Spike-Timing-Dependent Plasticity (STDP) Causal Sequence Edge.
 * Learns directional next-activity probabilities (`fromConcept -> toConcept`) from
 * chronological user behavior windows with biological temporal decay weighting.
 */
@Entity(
    tableName = "causal_transitions",
    indices = [
        Index(value = ["from_concept"]),
        Index(value = ["stdp_weight"])
    ]
)
data class CausalTransitionEntity(
    @PrimaryKey
    @ColumnInfo(name = "transition_key")
    val transitionKey: String,

    @ColumnInfo(name = "from_concept")
    val fromConcept: String,

    @ColumnInfo(name = "to_concept")
    val toConcept: String,

    @ColumnInfo(name = "stdp_weight")
    val stdpWeight: Double = 0.5,

    @ColumnInfo(name = "transition_count")
    val transitionCount: Int = 1,

    @ColumnInfo(name = "avg_delta_ms")
    val avgDeltaMs: Long = 0L,

    @ColumnInfo(name = "last_seen_ms")
    val lastSeenMs: Long = 0L
)
