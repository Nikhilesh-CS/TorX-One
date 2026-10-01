package com.torxone.app.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/** Display-only Markdown subset. No HTML, remote images, embedded actions or URL fetches. */
object SafeRichText {
    private val inline = Regex("\\*\\*([^*\\n]+)\\*\\*|(?<![\\w])_([^_\\n]+)_(?![\\w])|~~([^~\\n]+)~~|`([^`\\n]+)`")
    fun render(source: String): AnnotatedString = buildAnnotatedString {
        source.take(64 * 1024).split('\n').forEachIndexed { index, original ->
            if (index > 0) append('\n')
            val line = when {
                original.startsWith("> ") -> "│ ${original.drop(2)}"
                original.startsWith("- ") || original.startsWith("* ") -> "• ${original.drop(2)}"
                else -> original
            }
            var offset = 0
            for (match in inline.findAll(line)) {
                append(line.substring(offset, match.range.first))
                val group = (1..4).first { match.groups[it] != null }
                val style = when (group) {
                    1 -> SpanStyle(fontWeight = FontWeight.Bold)
                    2 -> SpanStyle(fontStyle = FontStyle.Italic)
                    3 -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    else -> SpanStyle(fontFamily = FontFamily.Monospace)
                }
                withStyle(style) { append(match.groupValues[group]) }
                offset = match.range.last + 1
            }
            append(line.substring(offset))
        }
    }
}
