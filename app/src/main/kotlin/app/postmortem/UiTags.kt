package app.postmortem

/** Compose test tags. The smoke steps in scripts/lab/smoke.steps name these ids. */
object UiTags {
    const val PASTE_INPUT = "paste_input"
    const val ANALYSE_BUTTON = "analyse_button"
    const val RESULT_BOARD = "result_board"
    const val PREVIOUS_BUTTON = "prev_button"
    const val FLIP_BUTTON = "flip_button"
    const val NEXT_BUTTON = "next_button"
    const val SETTINGS_BUTTON = "settings_button"
    const val ENGINE_TOGGLE = "engine_toggle"
    const val SUMMARY = "summary"
    const val TAB_MOVES = "tab_moves"
    const val TAB_CHART = "tab_chart"
    const val TAB_SUMMARY = "tab_summary"
    const val EVAL_CHART = "eval_chart"
    const val SETTINGS_DB = "settings_db"
    const val TAB_ANALYSE = "tab_analyse"
    const val TAB_LIBRARY = "tab_library"
    const val TAB_REPORT = "tab_report"
    const val TAB_SETTINGS = "tab_settings"
    const val REPORT_PLAYER = "report_player"
    const val REPORT_WHITE = "report_white"
    const val REPORT_BLACK = "report_black"

    const val BG_GAMES_AT_ONCE = "bg_games_at_once"
    const val BG_ENGINES = "bg_engines"
    const val BG_FULL_SPEED = "bg_full_speed"
    const val BG_RESET = "bg_reset"
    const val BG_WARNING = "bg_warning"
    const val DRILL_BUTTON = "drill_button"
    const val DRILL_BOARD = "drill_board"
    const val DRILL_RESULT = "drill_result"
    const val DRILL_NEXT = "drill_next"
    const val DRILL_SHOW = "drill_show"

    fun openingRow(index: Int) = "opening_row_$index"

    const val LIBRARY = "library"

    /** The cell for a half-move; [ply] counts from 1. */
    fun move(ply: Int) = "move_$ply"

    /** The square [name] such as `e2`, whichever way the board is turned. */
    fun square(name: String) = "square_$name"

    /** The [index]-th database file in the list, counting from 0. */
    fun dbFile(index: Int) = "db_file_$index"

    /** The [index]-th saved game in the Library, counting from 0, newest first. */
    fun libraryItem(index: Int) = "library_item_$index"

    /** The row of line [line]. */
    fun lineRow(line: Int) = "line_row_$line"

    /** The [move]-th move of line [line], counting from 1. */
    fun lineMove(
        line: Int,
        move: Int,
    ) = "line_move_${line}_$move"
}
