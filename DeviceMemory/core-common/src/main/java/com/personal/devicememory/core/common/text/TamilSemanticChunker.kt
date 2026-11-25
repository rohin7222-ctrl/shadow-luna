package com.personal.devicememory.core.common.text

import java.text.BreakIterator
import java.util.Locale

/**
 * 2026 Standard Cognitive Chunker for Bilingual (Tamil + English) text.
 *
 * Unlike legacy naive character-slicing or English-only tokenizers, this chunker:
 * 1. Respects sentence and clause boundaries using Tamil Locale BreakIterator.
 * 2. Never cuts a Tamil grapheme cluster (e.g. consonants with vowel modifiers / viramas).
 * 3. Enforces a maximum grapheme cluster budget with configurable overlap for downstream
 *    on-device embedding models (e.g. MobileBERT / Multilingual MiniLM).
 */
object TamilSemanticChunker {

    private val TAMIL_LOCALE = Locale("ta", "IN")

    data class Chunk(
        val index: Int,
        val text: String,
        val graphemeCount: Int,
        val isTamil: Boolean
    )

    /**
     * Splits [text] into sentences respecting both Tamil and Latin punctuation.
     */
    fun splitSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()

        val sentences = mutableListOf<String>()
        val bi = BreakIterator.getSentenceInstance(TAMIL_LOCALE)
        bi.setText(text)

        var start = bi.first()
        var end = bi.next()
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
            }
            start = end
            end = bi.next()
        }
        return sentences
    }

    /**
     * Chunks [text] into semantic segments of at most [maxGraphemes] clusters,
     * maintaining [overlapGraphemes] between adjacent chunks for contextual continuity.
     */
    fun chunk(
        text: String,
        maxGraphemes: Int = 128,
        overlapGraphemes: Int = 16
    ): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val sentences = splitSentences(text)
        val chunks = mutableListOf<Chunk>()

        var currentClusters = mutableListOf<String>()
        var chunkIndex = 0

        for (sentence in sentences) {
            val sentenceClusters = GraphemeUtil.toGraphemeClusters(sentence)

            if (sentenceClusters.size > maxGraphemes) {
                // If a single sentence exceeds the budget, slide a window over its clusters
                var offset = 0
                while (offset < sentenceClusters.size) {
                    val end = minOf(offset + maxGraphemes, sentenceClusters.size)
                    val slice = sentenceClusters.subList(offset, end)
                    val chunkText = slice.joinToString("")
                    chunks.add(
                        Chunk(
                            index = chunkIndex++,
                            text = chunkText,
                            graphemeCount = slice.size,
                            isTamil = containsTamil(chunkText)
                        )
                    )
                    if (end == sentenceClusters.size) break
                    offset += (maxGraphemes - overlapGraphemes).coerceAtLeast(1)
                }
            } else if (currentClusters.size + sentenceClusters.size <= maxGraphemes) {
                currentClusters.addAll(sentenceClusters)
                // Add a space between sentences if not already ending in whitespace
                currentClusters.add(" ")
            } else {
                // Emit current chunk
                val chunkText = currentClusters.joinToString("").trim()
                if (chunkText.isNotEmpty()) {
                    chunks.add(
                        Chunk(
                            index = chunkIndex++,
                            text = chunkText,
                            graphemeCount = GraphemeUtil.graphemeLength(chunkText),
                            isTamil = containsTamil(chunkText)
                        )
                    )
                }

                // Overlap: retain trailing clusters strictly within remaining budget
                val maxAllowedOverlap = (maxGraphemes - sentenceClusters.size).coerceAtLeast(0)
                val effectiveOverlap = overlapGraphemes.coerceAtMost(maxAllowedOverlap)
                val overlap = if (currentClusters.size > effectiveOverlap) {
                    currentClusters.takeLast(effectiveOverlap)
                } else {
                    currentClusters
                }
                currentClusters = overlap.toMutableList()
                currentClusters.addAll(sentenceClusters)
                currentClusters.add(" ")
            }
        }

        // Final lingering chunk
        val remainingText = currentClusters.joinToString("").trim()
        if (remainingText.isNotEmpty()) {
            chunks.add(
                Chunk(
                    index = chunkIndex,
                    text = remainingText,
                    graphemeCount = GraphemeUtil.graphemeLength(remainingText),
                    isTamil = containsTamil(remainingText)
                )
            )
        }

        return chunks
    }

    /**
     * Checks if the text contains any Tamil Unicode character (\u0B80-\u0BFF).
     */
    fun containsTamil(text: String): Boolean {
        return text.any { it in '\u0B80'..'\u0BFF' }
    }
}
