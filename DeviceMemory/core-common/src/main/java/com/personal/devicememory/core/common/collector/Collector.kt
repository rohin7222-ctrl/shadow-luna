package com.personal.devicememory.core.common.collector

import com.personal.devicememory.core.common.model.CollectedRecord

/**
 * Interface implemented by every data-source collector module.
 */
interface Collector {

    /** Human-readable name shown in the UI. */
    val sourceName: String

    /**
     * Collect new records since [sinceTimestamp].
     * Returns the list of new records to insert.
     */
    suspend fun collect(sinceTimestamp: Long): List<CollectedRecord>
}
