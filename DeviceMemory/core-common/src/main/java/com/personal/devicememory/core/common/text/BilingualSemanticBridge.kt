package com.personal.devicememory.core.common.text

/**
 * Pure linguistic Bilingual (Tamil <-> English) Conceptual Synonym Bridge.
 *
 * Maps common Tamil and English root words/stems across everyday life domains so queries in either
 * language softly surface user memories recorded in the other language, WITHOUT containing any
 * pre-built personal knowledge or conversational facts.
 */
object BilingualSemanticBridge {

    private val CONCEPT_GROUPS: List<Set<String>> = listOf(
        setOf("photo", "photos", "camera", "picture", "image", "படம்", "புகைப்படம்", "கேமரா"),
        setOf("video", "clip", "recording", "வீடியோ", "காணொளி"),
        setOf("audio", "voice", "music", "song", "sound", "ஆடியோ", "குரல்", "பாடல்", "இசை", "சத்தம்"),
        setOf("food", "lunch", "dinner", "breakfast", "eat", "restaurant", "swiggy", "zomato", "உணவு", "சாப்பாடு", "மதியம்", "டிபன்"),
        setOf("payment", "paid", "money", "bill", "receipt", "bank", "upi", "gpay", "paytm", "phonepe", "பணம்", "கட்டணம்", "ரசீது", "வங்கி"),
        setOf("hospital", "doctor", "clinic", "medicine", "pharmacy", "health", "மருத்துவமனை", "டாக்டர்", "மருந்து", "ஆஸ்பத்திரி"),
        setOf("travel", "trip", "flight", "train", "bus", "cab", "uber", "ola", "rapido", "பயணம்", "ரயில்", "பஸ்", "விமானம்"),
        setOf("meeting", "call", "office", "work", "zoom", "teams", "மீட்டிங்", "சந்திப்பு", "வேலை", "அலுவலகம்"),
        setOf("college", "school", "class", "exam", "study", "notes", "கல்லூரி", "பள்ளி", "வகுப்பு", "தேர்வு", "படிப்பு"),
        setOf("mom", "mother", "amma", "அம்மா"),
        setOf("dad", "father", "appa", "அப்பா"),
        setOf("friend", "friends", "buddy", "நண்பர்", "நண்பர்கள்", "தோழன்"),
        setOf("home", "house", "வீடு"),
        setOf("shopping", "order", "amazon", "flipkart", "delivery", "பொருள்", "வாங்கியது", "ஆர்டர்"),
        setOf("message", "chat", "whatsapp", "telegram", "sms", "செய்தி", "மெசேஜ்"),
        setOf("morning", "காலை"),
        setOf("evening", "மாலை"),
        setOf("night", "இரவு"),
        setOf("outdoor", "bright", "park", "beach", "வெளியே", "பூங்கா", "கடற்கரை"),
        setOf("speech", "talk", "பேச்சு"),
        setOf("quiet", "silence", "அமைதி")
    )

    private val WORD_TO_GROUP: Map<String, Set<String>> = buildMap {
        for (group in CONCEPT_GROUPS) {
            for (word in group) {
                put(word.lowercase(), group)
            }
        }
    }

    private fun containsTamil(text: String): Boolean =
        text.any { it in '\u0B80'..'\u0BFF' }

    /**
     * Expands [tokens] with bilingual Tamil <-> English concept equivalents, excluding words already present.
     * Prioritizes cross-script translations (Tamil -> English and English -> Tamil) deterministically.
     */
    fun expandBilingualSynonyms(tokens: Collection<String>, maxPerToken: Int = 10): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val inputSet = tokens.map { it.lowercase() }.toSet()
        val expanded = LinkedHashSet<String>()
        for (tok in inputSet) {
            val group = WORD_TO_GROUP[tok] ?: continue
            val tokIsTamil = containsTamil(tok)
            val ordered = group.sortedByDescending { alt -> containsTamil(alt) != tokIsTamil }
            var added = 0
            for (alt in ordered) {
                if (alt !in inputSet && expanded.add(alt)) {
                    added++
                    if (added >= maxPerToken) break
                }
            }
        }
        return expanded.toList()
    }
}
