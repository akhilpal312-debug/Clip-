package com.aiclipcreator.core.ai

import com.aiclipcreator.core.common.Logx
import com.aiclipcreator.core.models.ClipText

/**
 * Produces the clip title, description and hashtags.
 *
 * With a local GGUF model installed it asks the model, strictly constrained to the transcript.
 * Without one it builds an extractive description from the spoken words, so nothing is invented.
 */
class ClipTextGenerator(
    private val llm: LlmRuntime,
    private val languageHint: () -> String = { "auto" }
) {

    suspend fun generate(transcript: String, sourceName: String, categoryLabel: String): ClipText {
        val clean = transcript.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) {
            return ClipText(
                title = "Clip from $sourceName",
                description = "Clip taken from $sourceName at a detected highlight.",
                hashtags = listOf("#shorts", "#highlights"),
                source = "fallback"
            )
        }
        if (llm.isReady) {
            val prompt = llm.chatPrompt(
                system = "You write short social captions. Use ONLY information present in the transcript. " +
                    "Never invent names, numbers or events. Answer in the language of the transcript. " +
                    "Return exactly three lines: TITLE: <max 60 chars>\\nDESCRIPTION: <max 180 chars>\\nTAGS: <3 hashtags>",
                user = "Transcript:\n\"\"\"$clean\"\"\"\n\nWrite the three lines now."
            )
            val output = runCatching { llm.generate(prompt, 120) }.getOrDefault("")
            val parsed = parseModelOutput(output, clean)
            if (parsed != null) return parsed
            Logx.w("LLM output unusable, using extractive caption")
        }
        return extractive(clean, sourceName, categoryLabel)
    }

    private fun parseModelOutput(raw: String, transcript: String): ClipText? {
        if (raw.isBlank()) return null
        var title = ""
        var description = ""
        var tags = emptyList<String>()
        for (line in raw.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("TITLE:", ignoreCase = true) ->
                    title = trimmed.substringAfter(':').trim()
                trimmed.startsWith("DESCRIPTION:", ignoreCase = true) ->
                    description = trimmed.substringAfter(':').trim()
                trimmed.startsWith("TAGS:", ignoreCase = true) -> {
                    tags = trimmed.substringAfter(':')
                        .split(Regex("[,\\s]+"))
                        .filter { it.startsWith("#") && it.length > 1 }
                        .take(4)
                }
            }
        }
        if (title.isBlank() && description.isBlank()) return null
        if (title.isBlank()) title = transcript.take(58)
        if (title.length > 80) title = title.take(77).trimEnd() + "…"
        if (description.isBlank()) description = transcript.take(180)
        if (tags.isEmpty()) tags = hashtagsFor(transcript)
        return ClipText(title = title, description = description, hashtags = tags, source = "local-llm")
    }

    private fun extractive(transcript: String, sourceName: String, category: String): ClipText {
        val sentences = transcript.split(Regex("(?<=[.!?।])\\s+")).filter { it.isNotBlank() }
        val lead = sentences.firstOrNull()?.trim().orEmpty().ifBlank { transcript }
        var title = lead.take(64).trim()
        if (lead.length > 64) title = title.substringBeforeLast(' ', title).trimEnd() + "…"
        var description = lead.take(150).trim()
        if (!description.endsWithPunctuation()) description += "."
        description += " ($category moment from $sourceName)"
        return ClipText(
            title = title.ifBlank { "Highlight from $sourceName" },
            description = description,
            hashtags = hashtagsFor(transcript),
            source = "extractive"
        )
    }

    private fun String.endsWithPunctuation(): Boolean =
        trimmedEnd().lastOrNull() in setOf('.', '!', '?', '।', '…')

    private fun String.trimmedEnd(): String = trimEnd()

    /** Hashtags are derived from the strongest words actually used in the clip. */
    fun hashtagsFor(transcript: String): List<String> {
        val stop = setOf(
            "the", "and", "this", "that", "with", "have", "from", "your", "about", "there", "what",
            "when", "just", "like", "they", "were", "will", "would", "could", "should", "because",
            "hain", "hai", "kya", "aur", "nahi", "yeh", "woh", "bhi", "mein", "aap", "hum", "kar"
        )
        val words = transcript.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 4 && it !in stop }
        val freq = words.groupingBy { it }.eachCount()
        val top = freq.entries.sortedByDescending { it.value }.take(4).map { it.key }
        val base = top.map { "#" + it.replaceFirstChar { c -> c.uppercase() } }
        val fixed = listOf("#shorts", "#highlights")
        return (base + fixed).distinct().take(4)
    }
}
