package com.example.navisense

import java.util.Locale
import java.util.regex.Pattern

/**
 * Transforms raw detection and navigation messages into warm, short, natural guide phrasing:
 * - Natural number words (e.g. "about two meters", "about half a meter")
 * - Warm conversational prefixes with variation ("Careful, ", "Heads up, ") without back-to-back repetition
 * - Crisp, short urgent alerts with zero filler ("Stop. Person ahead.")
 * - Rate-limited reassurance ("You're on the right path.", max once per 60s)
 * - "All clear ahead." only after an obstacle clears.
 */
class VoicePersona(
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    private var lastFillerIndex = -1
    private val fillers = listOf("Careful, ", "Heads up, ")
    private var hadObstacleInPath = false
    private var lastReassuranceTimestamp = 0L

    /**
     * Transforms any incoming raw message into natural voice phrasing.
     */
    fun transform(raw: String, isUrgent: Boolean = false): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        // 1. Arrival detection
        if (trimmed.contains("reached your destination", ignoreCase = true) ||
            trimmed.contains("arrived at destination", ignoreCase = true)
        ) {
            return "You have arrived."
        }

        // 2. Urgent / Stop alert (must be short, no filler, e.g. "Stop. Person ahead.")
        val containsStop = trimmed.endsWith("Stop.", ignoreCase = true) ||
            trimmed.startsWith("Stop", ignoreCase = true)

        if (isUrgent || containsStop) {
            hadObstacleInPath = true
            return formatUrgentAlert(trimmed)
        }

        // 3. Navigation instructions
        if (isNavigationInstruction(trimmed)) {
            return formatNavigation(trimmed)
        }

        // 4. Obstacle alerts (CENTER, LEFT, RIGHT)
        hadObstacleInPath = true
        return formatObstacleAlert(trimmed)
    }

    /**
     * Turns raw obstacle messages into warm phrasing with natural numbers and varied prefix.
     */
    fun formatObstacleAlert(raw: String): String {
        var text = raw.trim()
        val nextFiller = getNextFiller()

        // Match patterns like: "<Object> ahead, <distance>. <Advice>"
        // or "<Object> on your left/right, <distance>"
        val directionAdviceMatch = Regex("""^(.*?)\s+ahead,\s+(.*?)\.\s+(Step left\.|Step right\.|Stop\.)$""", RegexOption.IGNORE_CASE).find(text)
        if (directionAdviceMatch != null) {
            val obj = directionAdviceMatch.groupValues[1].lowercase(Locale.US)
            val distRaw = directionAdviceMatch.groupValues[2]
            val advice = directionAdviceMatch.groupValues[3]
            val naturalDist = naturalizeDistance(distRaw)
            return "$nextFiller$obj ahead, $naturalDist. $advice"
        }

        val centerMatch = Regex("""^(.*?)\s+ahead,\s+(.*?)$""", RegexOption.IGNORE_CASE).find(text)
        if (centerMatch != null) {
            val obj = centerMatch.groupValues[1].lowercase(Locale.US)
            val distRaw = centerMatch.groupValues[2]
            val naturalDist = naturalizeDistance(distRaw)
            return "$nextFiller$obj ahead, $naturalDist."
        }

        val sideMatch = Regex("""^(.*?)\s+on your (left|right),\s+(.*?)$""", RegexOption.IGNORE_CASE).find(text)
        if (sideMatch != null) {
            val obj = withArticle(sideMatch.groupValues[1].lowercase(Locale.US))
            val side = sideMatch.groupValues[2].lowercase(Locale.US)
            val distRaw = sideMatch.groupValues[3]
            val naturalDist = naturalizeDistance(distRaw)
            return "$nextFiller$obj on your $side, $naturalDist."
        }

        // Fallback natural distance replacement
        return replaceNumbersWithWords(text)
    }

    /**
     * Urgent alerts stay very short without fillers ("Stop. Person ahead.").
     */
    fun formatUrgentAlert(raw: String): String {
        val trimmed = raw.trim()

        // "Person ahead, 1 meter. Stop." -> "Stop. Person ahead."
        val objMatch = Regex("""^(.*?)\s+ahead.*""", RegexOption.IGNORE_CASE).find(trimmed)
        if (objMatch != null) {
            val rawObj = objMatch.groupValues[1].trim()
            val cleanObj = if (rawObj.equals("Large obstacle", ignoreCase = true)) {
                "Large obstacle ahead."
            } else {
                "${rawObj.replaceFirstChar { it.titlecase(Locale.US) }} ahead."
            }
            return "Stop. $cleanObj"
        }

        if (trimmed.startsWith("Large obstacle", ignoreCase = true)) {
            return "Stop. Large obstacle ahead."
        }

        if (!trimmed.startsWith("Stop", ignoreCase = true)) {
            return "Stop. $trimmed"
        }

        return trimmed
    }

    /**
     * Converts navigation instructions to natural speech numbers and punctuation.
     */
    fun formatNavigation(raw: String): String {
        var text = raw.trim()

        // "In 30 meters, turn left onto Market Street" -> "In thirty meters, turn left onto Market Street."
        text = text.replace(Regex("""\bIn (\d+)\s*meters\b""", RegexOption.IGNORE_CASE)) { match ->
            val num = match.groupValues[1].toIntOrNull()
            val word = when (num) {
                30 -> "thirty"
                10 -> "ten"
                15 -> "fifteen"
                else -> num?.toString() ?: "thirty"
            }
            "In $word meters"
        }

        // "Turn left now" -> "Turn left now."
        if (!text.endsWith(".") && !text.endsWith("!") && !text.endsWith("?")) {
            text += "."
        }

        return text
    }

    /**
     * Returns "All clear ahead." only after an obstacle has previously cleared.
     */
    fun onObstacleCleared(): String? {
        return if (hadObstacleInPath) {
            hadObstacleInPath = false
            "All clear ahead."
        } else {
            null
        }
    }

    /**
     * Reassurance occasionally ("You're on the right path.") at most once per 60 seconds.
     */
    fun maybeGetReassurance(currentTimeMs: Long = timeProvider()): String? {
        if (lastReassuranceTimestamp == 0L || currentTimeMs - lastReassuranceTimestamp >= REASSURANCE_INTERVAL_MS) {
            lastReassuranceTimestamp = currentTimeMs
            return "You're on the right path."
        }
        return null
    }

    fun resetObstacleState() {
        hadObstacleInPath = false
    }

    private fun getNextFiller(): String {
        lastFillerIndex = (lastFillerIndex + 1) % fillers.size
        return fillers[lastFillerIndex]
    }

    private fun isNavigationInstruction(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return lower.startsWith("in ") ||
            lower.contains("turn left") ||
            lower.contains("turn right") ||
            lower.contains("turn sharp") ||
            lower.contains("turn slight") ||
            lower.contains("u-turn") ||
            lower.contains("continue straight") ||
            lower.contains("starting navigation") ||
            lower.contains("arrived") ||
            lower.contains("recalculating")
    }

    private fun withArticle(obj: String): String {
        val trimmed = obj.trim()
        if (trimmed.startsWith("a ") || trimmed.startsWith("an ") || trimmed.startsWith("the ")) {
            return trimmed
        }
        val firstChar = trimmed.firstOrNull()?.lowercaseChar()
        val article = if (firstChar in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
        return "$article $trimmed"
    }

    fun naturalizeDistance(rawDist: String): String {
        val clean = rawDist.trim().trimEnd('.')
        val parts = clean.split(" ")
        val num = parts.firstOrNull()?.toFloatOrNull() ?: return clean

        val naturalNumber = when {
            num == 0.5f -> "about half a meter"
            num == 1.0f -> "about one meter"
            num == 1.5f -> "about one and a half meters"
            num == 2.0f -> "about two meters"
            num == 2.5f -> "about two and a half meters"
            num == 3.0f -> "about three meters"
            num == 3.5f -> "about three and a half meters"
            num == 4.0f -> "about four meters"
            num == 4.5f -> "about four and a half meters"
            num == 5.0f -> "about five meters"
            else -> "about ${num.toInt()} meters"
        }
        return naturalNumber
    }

    private fun replaceNumbersWithWords(text: String): String {
        return text
            .replace(Regex("""\b0\.5\s*meters?\b""", RegexOption.IGNORE_CASE), "half a meter")
            .replace(Regex("""\b1(\.0)?\s*meters?\b""", RegexOption.IGNORE_CASE), "one meter")
            .replace(Regex("""\b1\.5\s*meters?\b""", RegexOption.IGNORE_CASE), "one and a half meters")
            .replace(Regex("""\b2(\.0)?\s*meters?\b""", RegexOption.IGNORE_CASE), "two meters")
            .replace(Regex("""\b2\.5\s*meters?\b""", RegexOption.IGNORE_CASE), "two and a half meters")
            .replace(Regex("""\b3(\.0)?\s*meters?\b""", RegexOption.IGNORE_CASE), "three meters")
            .replace(Regex("""\b4(\.0)?\s*meters?\b""", RegexOption.IGNORE_CASE), "four meters")
            .replace(Regex("""\b5(\.0)?\s*meters?\b""", RegexOption.IGNORE_CASE), "five meters")
            .replace(Regex("""\b30\s*meters?\b""", RegexOption.IGNORE_CASE), "thirty meters")
            .replace(Regex("""\b10\s*meters?\b""", RegexOption.IGNORE_CASE), "ten meters")
    }

    companion object {
        const val REASSURANCE_INTERVAL_MS = 60_000L // At most once per minute
    }
}
