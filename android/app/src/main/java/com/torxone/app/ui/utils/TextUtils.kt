package com.torxone.app.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

object TextUtils {

    // Regex to match a string containing ONLY emojis (up to 10 characters)
    // Matches emojis and optional whitespace
    private val EMOJI_REGEX = Regex("^[\\p{Punct}\\s]*[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}\\x{2300}-\\x{23FF}]+[\\p{Punct}\\s]*$")

    fun isEmojiOnly(text: String): Boolean {
        if (text.isBlank() || text.length > 20) return false
        // Remove spaces, newlines, and common punctuation for length check, but regex should match standard emojis
        // This is a naive regex that works for most emojis
        return EMOJI_REGEX.matches(text)
    }

    /**
     * Parses simple markdown: **bold**, *italic* or _italic_, `code`
     */
    fun parseMarkdown(text: String, codeColor: Color = Color.LightGray): AnnotatedString {
        return buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                when {
                    // Bold: **text**
                    text.startsWith("**", i) -> {
                        val end = text.indexOf("**", i + 2)
                        if (end != -1) {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append(text.substring(i + 2, end))
                            }
                            i = end + 2
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Italic: _text_
                    text.startsWith("_", i) -> {
                        val end = text.indexOf("_", i + 1)
                        if (end != -1 && end > i + 1 && !text[i + 1].isWhitespace()) {
                            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                                append(text.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    // Code: `code`
                    text.startsWith("`", i) -> {
                        val end = text.indexOf("`", i + 1)
                        if (end != -1 && end > i + 1) {
                            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeColor)) {
                                append(text.substring(i + 1, end))
                            }
                            i = end + 1
                        } else {
                            append(text[i])
                            i++
                        }
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}
