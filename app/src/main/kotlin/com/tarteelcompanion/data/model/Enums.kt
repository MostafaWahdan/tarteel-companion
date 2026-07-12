package com.tarteelcompanion.data.model

/**
 * The three Tarteel mistake types, by their session-view text colors (origin R3/R21):
 * yellow = pronunciation slip, red = wrong word substituted, brown = needed prompting.
 */
enum class MistakeType {
    PRONUNCIATION,
    WRONG_WORD,
    PROMPT_NEEDED,
}

enum class SpotState {
    ACTIVE,
    GRADUATED,
    SUSPENDED,
}

enum class ReviewKind {
    STUDY,
    QUIZ,
}

enum class ReviewGrade {
    AGAIN,
    HARD,
    GOOD,
    EASY,
}
