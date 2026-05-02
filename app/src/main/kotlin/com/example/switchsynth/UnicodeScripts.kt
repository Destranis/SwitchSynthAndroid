package com.example.switchsynth

/**
 * Maps Unicode script names to their primary languages, and provides
 * reverse lookup from language tags to scripts.
 */
object UnicodeScripts {

    // Script name -> list of language tags that use this script
    val SCRIPT_TO_LANGUAGES: Map<String, List<String>> = mapOf(
        "Latin" to listOf(
            "af", "az", "ca", "cs", "cy", "da", "de", "en", "es", "et",
            "eu", "fi", "fr", "gl", "ha", "hr", "hu", "id", "is", "it",
            "la", "lt", "lv", "nb", "nl", "no", "pl", "pt", "ro", "sk",
            "sl", "so", "sq", "st", "sv", "sw", "tl", "tr", "uz", "vi",
            "xh", "zu"
        ),
        "Cyrillic" to listOf("ru", "uk", "be", "bg", "kk", "ky", "mk", "mn", "sr", "tt"),
        "Greek" to listOf("el"),
        "Arabic" to listOf("ar", "fa", "ps", "ur"),
        "Devanagari" to listOf("hi", "mr", "ne", "sa"),
        "Bengali" to listOf("bn"),
        "Gurmukhi" to listOf("pa"),
        "Gujarati" to listOf("gu"),
        "Oriya" to listOf("or"),
        "Tamil" to listOf("ta"),
        "Telugu" to listOf("te"),
        "Kannada" to listOf("kn"),
        "Malayalam" to listOf("ml"),
        "Sinhala" to listOf("si"),
        "Thai" to listOf("th"),
        "Lao" to listOf("lo"),
        "Tibetan" to listOf("bo"),
        "Georgian" to listOf("ka"),
        "Armenian" to listOf("hy"),
        "Hebrew" to listOf("he", "yi"),
        "Han" to listOf("zh", "ja"),
        "Hiragana" to listOf("ja"),
        "Katakana" to listOf("ja"),
        "Hangul" to listOf("ko"),
        "Khmer" to listOf("km"),
        "Myanmar" to listOf("my"),
        "Ethiopic" to listOf("am", "ti")
    )

    // Reverse: language tag -> script name
    val LANGUAGE_TO_SCRIPT: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        for ((script, languages) in SCRIPT_TO_LANGUAGES) {
            for (lang in languages) {
                // First script wins for a language (e.g. "ja" -> "Han" not "Hiragana")
                if (lang !in map) {
                    map[lang] = script
                }
            }
        }
        map
    }

    // Default language for each script (first in the list)
    val SCRIPT_DEFAULT_LANGUAGE: Map<String, String> by lazy {
        SCRIPT_TO_LANGUAGES.mapValues { (_, langs) -> langs.first() }
    }

    // Human-readable display names for scripts
    val SCRIPT_DISPLAY_NAMES: Map<String, String> = mapOf(
        "Latin" to "Latin",
        "Cyrillic" to "Cyrillic",
        "Greek" to "Greek",
        "Arabic" to "Arabic",
        "Devanagari" to "Devanagari",
        "Bengali" to "Bengali",
        "Gurmukhi" to "Gurmukhi",
        "Gujarati" to "Gujarati",
        "Oriya" to "Oriya",
        "Tamil" to "Tamil",
        "Telugu" to "Telugu",
        "Kannada" to "Kannada",
        "Malayalam" to "Malayalam",
        "Sinhala" to "Sinhala",
        "Thai" to "Thai",
        "Lao" to "Lao",
        "Tibetan" to "Tibetan",
        "Georgian" to "Georgian",
        "Armenian" to "Armenian",
        "Hebrew" to "Hebrew",
        "Han" to "CJK (Chinese/Japanese)",
        "Hiragana" to "Hiragana (Japanese)",
        "Katakana" to "Katakana (Japanese)",
        "Hangul" to "Hangul (Korean)",
        "Khmer" to "Khmer",
        "Myanmar" to "Myanmar",
        "Ethiopic" to "Ethiopic"
    )

    /**
     * Given a Unicode code point, return its script name or "Common" for
     * shared characters (punctuation, digits, whitespace, emoji).
     */
    fun getScriptName(codePoint: Int): String {
        val script = Character.UnicodeScript.of(codePoint)
        return when (script) {
            Character.UnicodeScript.COMMON -> "Common"
            Character.UnicodeScript.INHERITED -> "Common"
            Character.UnicodeScript.UNKNOWN -> "Common"
            else -> script.name.lowercase().replaceFirstChar { it.uppercase() }
                // Character.UnicodeScript.name returns e.g. "LATIN", "CYRILLIC"
                // We normalize to "Latin", "Cyrillic" etc.
        }
    }

    /**
     * Get the set of active scripts based on selected language tags.
     * Returns script names that have at least one selected language.
     */
    fun getActiveScripts(selectedLanguageTags: Set<String>): List<String> {
        val scripts = mutableSetOf<String>()
        for (tag in selectedLanguageTags) {
            val baseLang = tag.substringBefore("-")
            val script = LANGUAGE_TO_SCRIPT[baseLang]
            if (script != null) {
                scripts.add(script)
            }
        }
        // Return in a stable order matching SCRIPT_TO_LANGUAGES key order
        return SCRIPT_TO_LANGUAGES.keys.filter { it in scripts }
    }

    /**
     * Get languages from the selected set that belong to a specific script.
     */
    fun getLanguagesForScript(script: String, selectedLanguageTags: Set<String>): List<String> {
        val scriptLangs = SCRIPT_TO_LANGUAGES[script] ?: return emptyList()
        return selectedLanguageTags.filter { tag ->
            val baseLang = tag.substringBefore("-")
            baseLang in scriptLangs
        }.sorted()
    }
}
