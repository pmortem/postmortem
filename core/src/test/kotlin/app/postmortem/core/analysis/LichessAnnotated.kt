package app.postmortem.core.analysis

/**
 * The analysis Lichess wrote into an exported PGN: per ply the `[%eval]` (White's point of view) and, when it judged the move,
 * the class from the comment ("Inaccuracy.", "Mistake.", "Blunder."). The main line only; variations are dropped.
 */
class LichessAnnotated(
    pgn: String,
) {
    class Ply(
        val san: String,
        /** `0.18` pawns, or null when the move has no `[%eval]`. */
        val eval: Eval?,
        val judgement: Judgement?,
    )

    val plies: List<Ply>

    init {
        val movetext = pgn.substringAfter("\n\n").replace(Regex("""\([^()]*\)"""), " ")
        val token =
            Regex(
                """(O-O-O|O-O|[KQRBN]?[a-h]?[1-8]?x?[a-h][1-8](?:=[QRBN])?[+#]?)([?!]{0,2})\s*((?:\{[^}]*\}\s*)*)""",
            )
        plies =
            token
                .findAll(movetext)
                .map { m ->
                    val comments = m.groupValues[3]
                    val eval =
                        Regex("""\[%eval (#?-?\d+(?:\.\d+)?)]""").find(comments)?.groupValues?.get(1)?.let {
                            if (it.startsWith("#")) Eval.Mate(it.drop(1).toInt()) else Eval.Cp(Math.round(it.toDouble() * 100).toInt())
                        }
                    val judgement =
                        when {
                            "Blunder." in comments -> Judgement.BLUNDER
                            "Mistake." in comments -> Judgement.MISTAKE
                            "Inaccuracy." in comments -> Judgement.INACCURACY
                            else -> null
                        }
                    Ply(m.groupValues[1], eval, judgement)
                }.toList()
    }

    /** The evals as [GameReport] wants them: one per position, with the start position at 15 cp (Lichess's own start value). */
    fun evals(): List<Eval> = listOf<Eval>(Eval.Cp(START_CP)) + plies.map { checkNotNull(it.eval) { "no eval for ${it.san}" } }

    private companion object {
        const val START_CP = 15
    }
}
