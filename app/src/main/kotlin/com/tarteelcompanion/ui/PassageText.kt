package com.tarteelcompanion.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.tarteelcompanion.data.model.MistakeType
import com.tarteelcompanion.quran.QuranWord
import com.tarteelcompanion.quran.WordRef

/** Tarteel's session-view semantics: yellow, red, brown (origin R3/R21). */
fun MistakeType.markColor(): Color = when (this) {
    MistakeType.PRONUNCIATION -> Color(0xFFB8860B)
    MistakeType.WRONG_WORD -> Color(0xFFC62828)
    MistakeType.PROMPT_NEEDED -> Color(0xFF6D4C41)
}

/**
 * Arabic passage rendered RTL with per-word mistake marks. Emphasis by type (R14):
 * red = bold color (the substituted word), brown = color + underline (the continuation
 * point), yellow = color only (pronunciation slip).
 */
@Composable
fun PassageText(
    words: List<QuranWord>,
    marks: Map<WordRef, MistakeType>,
    modifier: Modifier = Modifier,
    dimUnmarked: Boolean = false,
) {
    Text(
        text = annotate(words, marks, MaterialTheme.colorScheme.onSurface, dimUnmarked),
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Right,
        fontSize = 26.sp,
        lineHeight = 44.sp,
    )
}

private fun annotate(
    words: List<QuranWord>,
    marks: Map<WordRef, MistakeType>,
    baseColor: Color,
    dimUnmarked: Boolean,
): AnnotatedString = buildAnnotatedString {
    words.forEachIndexed { index, word ->
        if (index > 0) append(' ')
        val type = marks[word.ref]
        val style = when (type) {
            null -> SpanStyle(color = if (dimUnmarked) baseColor.copy(alpha = 0.55f) else baseColor)
            MistakeType.WRONG_WORD -> SpanStyle(color = type.markColor(), fontWeight = FontWeight.Bold)
            MistakeType.PROMPT_NEEDED -> SpanStyle(
                color = type.markColor(),
                textDecoration = TextDecoration.Underline,
            )
            MistakeType.PRONUNCIATION -> SpanStyle(color = type.markColor())
        }
        pushStyle(style)
        append(word.text)
        pop()
    }
}
