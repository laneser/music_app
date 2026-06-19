package com.cellocoach.ui

/**
 * Central registry of Compose `testTag` strings.
 *
 * Every element a UI test asserts on carries one of these tags via
 * `Modifier.testTag(...)`. Keeping them in one object (rather than scattering
 * string literals) means tests and production code can't drift apart — a renamed
 * tag is a compile error, not a silently broken assertion.
 *
 * The Robolectric Compose tests described in CONTRACTS.md inject a
 * [com.cellocoach.audio.FakePitchSource], feed scripted pitches, and assert on
 * these tags to verify the calibration flow, cursor advance, feedback colours,
 * and report numbers — the "mock recording" end-to-end screen verification.
 */
object TestTags {

    // ---- Navigation / shell ------------------------------------------------
    const val NAV_HOME = "nav_home"
    const val SCREEN_ROOT = "screen_root"

    // ---- Home --------------------------------------------------------------
    const val HOME_START = "home_start"
    const val HOME_SCORE_PICKER = "home_score_picker"
    const val HOME_SCORE_OPTION_PREFIX = "home_score_option_" // + score file name
    const val HOME_TUNING_STATUS = "home_tuning_status"
    const val HOME_CALIBRATE = "home_calibrate"
    const val HOME_IMPORT_FILE = "home_import_file"
    const val HOME_IMPORT_URL = "home_import_url"
    const val HOME_IMPORT_STATUS = "home_import_status"
    const val HOME_TEMPO = "home_tempo"
    const val HOME_METRONOME = "home_metronome"
    const val URL_INPUT = "url_input"
    const val URL_CONFIRM = "url_confirm"

    // ---- Tuning ------------------------------------------------------------
    const val TUNING_TARGET = "tuning_target"
    const val TUNING_CENTS = "tuning_cents"
    const val TUNING_PROGRESS = "tuning_progress"
    const val TUNING_SKIP = "tuning_skip"
    const val TUNING_DONE = "tuning_done"

    // ---- Practice ----------------------------------------------------------
    const val PRACTICE_START = "practice_start"
    const val PRACTICE_CURSOR = "practice_cursor"
    const val PRACTICE_EXPECTED = "practice_expected"
    const val PRACTICE_DETECTED = "practice_detected"
    const val PRACTICE_CENTS = "practice_cents"
    const val PRACTICE_STATUS = "practice_status"
    const val PRACTICE_COUNTDOWN = "practice_countdown"
    const val PRACTICE_SCORE_VIEW = "practice_score_view"
    const val PRACTICE_RESET = "practice_reset"

    // ---- Report ------------------------------------------------------------
    const val REPORT_SCORE = "report_score"
    const val REPORT_CORRECT = "report_correct"
    const val REPORT_MEAN_CENTS = "report_mean_cents"
    const val REPORT_NOTE_LIST = "report_note_list"
    const val REPORT_NOTE_ROW_PREFIX = "report_note_row_" // + note index
    const val REPORT_DIAGNOSTICS = "report_diagnostics"
    const val REPORT_DONE = "report_done"
    const val REPORT_EXPORT = "report_export"
}
