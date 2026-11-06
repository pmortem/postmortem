package app.postmortem.core.store

/** The PGN tags as a JSON object of strings, which is what the `headers_json` column holds, so any SQLite tool can read it. */
internal object HeadersJson {
    private const val UNICODE_ESCAPE_DIGITS = 4
    private const val HEX_RADIX = 16

    fun encode(headers: Map<String, String>): String =
        headers.entries.joinToString(",", "{", "}") { (name, value) -> "${quote(name)}:${quote(value)}" }

    fun decode(json: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var at = skipSpace(json, 0)
        require(json.getOrNull(at) == '{') { "not a JSON object" }
        at = skipSpace(json, at + 1)
        if (json.getOrNull(at) == '}') return result
        while (true) {
            val (name, afterName) = readString(json, skipSpace(json, at))
            at = skipSpace(json, afterName)
            require(json.getOrNull(at) == ':') { "expected ':' at $at" }
            val (value, afterValue) = readString(json, skipSpace(json, at + 1))
            result[name] = value
            at = skipSpace(json, afterValue)
            when (json.getOrNull(at)) {
                ',' -> at++
                '}' -> return result
                else -> error("expected ',' or '}' at $at")
            }
        }
    }

    private fun quote(text: String): String =
        buildString {
            append('"')
            for (c in text) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    private fun skipSpace(
        json: String,
        from: Int,
    ): Int {
        var at = from
        while (at < json.length && json[at].isWhitespace()) at++
        return at
    }

    private fun readString(
        json: String,
        from: Int,
    ): Pair<String, Int> {
        require(json.getOrNull(from) == '"') { "expected a string at $from" }
        val out = StringBuilder()
        var at = from + 1
        while (true) {
            val c = json.getOrNull(at) ?: error("unterminated string")
            at++
            when (c) {
                '"' -> {
                    return out.toString() to at
                }

                '\\' -> {
                    val escaped = json.getOrNull(at) ?: error("unterminated escape")
                    at++
                    when (escaped) {
                        'n' -> {
                            out.append('\n')
                        }

                        'r' -> {
                            out.append('\r')
                        }

                        't' -> {
                            out.append('\t')
                        }

                        'b' -> {
                            out.append('\b')
                        }

                        'f' -> {
                            out.append('\u000c')
                        }

                        'u' -> {
                            out.append(json.substring(at, at + UNICODE_ESCAPE_DIGITS).toInt(HEX_RADIX).toChar())
                            at += UNICODE_ESCAPE_DIGITS
                        }

                        else -> {
                            out.append(escaped)
                        }
                    }
                }

                else -> {
                    out.append(c)
                }
            }
        }
    }
}
