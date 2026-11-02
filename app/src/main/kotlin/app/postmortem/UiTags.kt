package app.postmortem

/** Compose test tags. The smoke steps in scripts/lab/smoke.steps name these ids. */
object UiTags {
    const val PASTE_INPUT = "paste_input"
    const val ANALYSE_BUTTON = "analyse_button"
    const val SPLASH_SCREEN = "splash_screen"
    const val VERSION_LABEL = "version_label"
    const val PASTE_STATUS = "paste_status"
    const val RESULT_BOARD = "result_board"
    const val PREVIOUS_BUTTON = "prev_button"
    const val NEXT_BUTTON = "next_button"
    const val SUMMARY = "summary"
    const val OPENING = "opening"
    const val TAB_MOVES = "tab_moves"
    const val TAB_CHART = "tab_chart"
    const val TAB_SUMMARY = "tab_summary"
    const val CHART_LABEL = "chart_label"
    const val EVAL_CHART = "eval_chart"
    const val MOVE_LIST = "move_list"
    const val EVAL_BAR = "eval_bar"
    const val ANALYSING_PROGRESS = "analysing_progress"

    /** The cell for a half-move; [ply] counts from 1. */
    fun move(ply: Int) = "move_$ply"
}
