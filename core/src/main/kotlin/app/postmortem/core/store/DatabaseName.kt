package app.postmortem.core.store

/** The name of a database file the user typed, checked and written the way the file is called. */
sealed interface DatabaseName {
    /** [fileName] is what the file is called on disk, always ending in `.sqlite`. */
    data class Valid(
        val fileName: String,
    ) : DatabaseName

    data class Invalid(
        val reason: Reason,
    ) : DatabaseName

    enum class Reason { EMPTY, BAD_CHARACTERS, TOO_LONG }

    companion object {
        const val DEFAULT = "postmortem.sqlite"
        const val EXTENSION = ".sqlite"
        const val MAX_LENGTH = 64

        /**
         * `club` and `club.sqlite` both give `club.sqlite`. Only letters, digits, `-`, `_` and spaces are allowed, so a name cannot
         * point outside the database folder; spaces at the ends are dropped.
         */
        fun parse(input: String): DatabaseName {
            val stem = input.trim().removeSuffixIgnoreCase(EXTENSION).trim()
            return when {
                stem.isEmpty() -> Invalid(Reason.EMPTY)
                !stem.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' } -> Invalid(Reason.BAD_CHARACTERS)
                stem.length > MAX_LENGTH -> Invalid(Reason.TOO_LONG)
                else -> Valid(stem + EXTENSION)
            }
        }

        private fun String.removeSuffixIgnoreCase(suffix: String) =
            if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
    }
}
