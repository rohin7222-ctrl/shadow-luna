package com.personal.devicememory.pipeline

import com.personal.devicememory.core.common.collector.Collector
import com.personal.devicememory.core.common.model.SourceType

/**
 * Registry of all available collectors.
 * New collectors register here as they are implemented.
 */
class CollectorRegistry {

    private val collectors = mutableMapOf<SourceType, Collector>()

    fun register(sourceType: SourceType, collector: Collector) {
        collectors[sourceType] = collector
    }

    fun get(sourceType: SourceType): Collector? = collectors[sourceType]

    fun getAll(): Map<SourceType, Collector> = collectors.toMap()
}
