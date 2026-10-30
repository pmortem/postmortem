package app.postmortem.core.pgn

private val tagPair = Regex("""(?m)^\s*\[\w+\s+".*"\s*]\s*$""")
private val firstMove = Regex("""(^|\s)1\.{1,3}\s*(O-O|0-0|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8])""")

/** True when the text is worth offering as a game: it has a tag pair or starts like numbered moves. */
fun looksLikePgn(text: String): Boolean = tagPair.containsMatchIn(text) || firstMove.containsMatchIn(text)
