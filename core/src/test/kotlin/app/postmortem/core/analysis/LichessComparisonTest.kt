package app.postmortem.core.analysis

import app.postmortem.core.pgn.Fixtures
import app.postmortem.core.pgn.plyLabel
import app.postmortem.core.uci.EngineBinary
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slow pass: our engine on the game Lichess annotated in #64, as a table next to Lichess's evals (written to build/reports). */
class LichessComparisonTest {
    private val name = "lichess-64-annotated"
    private val clearCp = 60
    private val maxGapCp = 100
    private val maxMeanGapCp = 30

    private fun pawns(eval: Eval) =
        if (eval is Eval.Cp) {
            String.format("%+.2f", eval.centipawns / 100.0)
        } else if (eval is Eval.Mate) {
            "#${eval.moves}"
        } else {
            eval.format()
        }

    private fun judgement(j: Judgement?) = j?.name?.lowercase().orEmpty()

    @Test
    fun theTableOfDifferences() {
        val game = Fixtures.game(name)
        val lichess = LichessAnnotated(Fixtures.text(name))
        assertEquals(game.plies.size, lichess.plies.size)
        val config = AnalysisConfig.forCores(Runtime.getRuntime().availableProcessors())
        val ours = runBlocking { Analyser(config, EngineBinary.requireSlow()).analyse(game).last().evals }
        val theirsEvals = lichess.evals()
        val start = Eval.Cp(GameReport.LICHESS_START_CP)
        val oursReport = GameReport.of(ours, startEval = start)
        val theirsReport = GameReport.of(theirsEvals)
        val rows = StringBuilder()
        rows.append(
            "| ply | SAN | Lichess eval | our eval | difference | Lichess class | our class (from our evals) | class from Lichess's evals |\n|---|---|---|---|---|---|---|---|\n",
        )
        val gaps = ArrayList<Int>()
        game.plies.forEachIndexed { index, ply ->
            val l = lichess.plies[index]
            val mine = ours[index + 1]
            val theirs = theirsEvals[index + 1]
            val cp = { e: Eval ->
                if (e is Eval.Cp) {
                    e.centipawns
                } else if (e is Eval.Mate) {
                    (if (e.moves > 0) 1000 else -1000)
                } else {
                    0
                }
            }
            val gap = cp(mine) - cp(theirs)
            if (theirs is Eval.Cp && mine is Eval.Cp) gaps += gap
            rows.append(
                "| ${index + 1} | ${plyLabel(
                    index + 1,
                    ply.san,
                )} | ${pawns(theirs)} | ${pawns(mine)} | ${if (theirs is Eval.Cp && mine is Eval.Cp) {
                    String.format(
                        "%+.2f",
                        gap / 100.0,
                    )
                } else {
                    ""
                }} | ${judgement(
                    l.judgement,
                )} | ${judgement(oursReport.judgements[index])} | ${judgement(theirsReport.judgements[index])} |\n",
            )
        }

        fun side(
            label: String,
            r: PlayerReport,
        ) = "| $label | ${r.accuracy?.let {
            String.format(
                "%.1f",
                it,
            )
        }} | ${r.acpl} | ${r.inaccuracies} | ${r.mistakes} | ${r.blunders} |\n"
        val lichessCounts = { color: Int ->
            val judged =
                lichess.plies
                    .withIndex()
                    .filter {
                        (it.index % 2 == 0) == (color == 0) && it.value.judgement != null
                    }.map { it.value.judgement }
            "| Lichess's own annotations, ${if (color == 0) "White" else "Black"} | n/a | n/a | ${judged.count {
                it == Judgement.INACCURACY
            }} | ${judged.count { it == Judgement.MISTAKE }} | ${judged.count { it == Judgement.BLUNDER }} |\n"
        }
        val totals =
            "| side | accuracy % | ACPL | inaccuracies | mistakes | blunders |\n|---|---|---|---|---|---|\n" +
                side("our engine's evals, White", oursReport.white) + side("our engine's evals, Black", oursReport.black) +
                side("Lichess's evals through our code, White", theirsReport.white) +
                side("Lichess's evals through our code, Black", theirsReport.black) +
                lichessCounts(0) + lichessCounts(1)
        val noise = "mean |gap| ${String.format(
            "%.1f",
            gaps.map { abs(it) }.average(),
        )} cp, max |gap| ${gaps.maxOf {
            abs(
                it,
            )
        }} cp, mean signed gap ${String.format("%+.1f", gaps.average())} cp over ${gaps.size} plies; engine: $config"
        val out = File("build/reports/lichess-64.md").apply { parentFile.mkdirs() }
        out.writeText(rows.toString() + "\n" + totals + "\n" + noise + "\n")
        println(out.readText())
        // Engine noise, not a bug: no sign flips on clear positions, no shift of a ply, and small gaps. (Searches are time-limited
        // and threaded, so the numbers differ a little from run to run.)
        game.plies.indices.forEach { index ->
            val l = (theirsEvals[index + 1] as? Eval.Cp)?.centipawns ?: return@forEach
            val o = (ours[index + 1] as? Eval.Cp)?.centipawns ?: return@forEach
            if (abs(l) >= clearCp) assertEquals(l > 0, o > 0, "sign flip at ply ${index + 1}: Lichess $l, ours $o")
            assertTrue(abs(o - l) <= maxGapCp, "gap of ${o - l} cp at ply ${index + 1}")
        }
        assertTrue(gaps.map { abs(it) }.average() <= maxMeanGapCp, "mean gap too large")
    }
}
