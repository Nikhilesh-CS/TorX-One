package com.torxone.app.chat

import com.torxone.app.ui.components.SafeRichText
import org.junit.Assert.*
import org.junit.Test

class ProductivityTest {
    @Test fun appearanceAllowsLocalOptionsAndRejectsUnknownValues() {
        AppearanceOptions.validate(com.torxone.app.data.entity.ConversationAppearanceEntity("chat"))
        AppearanceOptions.validate(com.torxone.app.data.entity.ConversationAppearanceEntity("chat", "FOREST", "WARM", "SQUARE"))
        assertThrows(IllegalArgumentException::class.java) {
            AppearanceOptions.validate(com.torxone.app.data.entity.ConversationAppearanceEntity("chat", "remote-theme"))
        }
    }
    @Test fun forwardingRetryUsesStableFreshIdWithoutReusingOriginalId() {
        val id = ForwardingPlan.messageId("operation", "original", "destination")
        assertNotEquals("original", id)
        assertEquals(id, ForwardingPlan.messageId("operation", "original", "destination"))
        assertNotEquals(id, ForwardingPlan.messageId("operation", "original", "other-peer"))
        assertNotEquals(id, ForwardingPlan.messageId("new-operation", "original", "destination"))
        assertEquals("[Forwarded]\nMessage body", ForwardingPlan.text("Message body"))
    }
    @Test fun ftsInputCannotInjectOperators() {
        assertEquals("\"hello*\" AND \"OR*\" AND \"world*\"", SearchQuery.fts("hello OR world"))
        assertEquals("\"a*\" AND \"b*\"", SearchQuery.fts("a\"b"))
        assertNull(SearchQuery.fts("\"*()"))
        assertNull(SearchQuery.fts("  "))
    }
    @Test fun markdownSubsetRendersSpansAndKeepsOriginalInput() {
        val source = "**bold** _italic_ ~~strike~~ `code`\n> quote\n- list"
        val rendered = SafeRichText.render(source)
        assertEquals("bold italic strike code\n│ quote\n• list", rendered.text)
        assertEquals(4, rendered.spanStyles.size)
        assertTrue(source.contains("**bold**"))
    }
    @Test fun htmlAndBrokenMarkupStayLiteral() {
        assertEquals("<script>alert(1)</script> **unfinished", SafeRichText.render("<script>alert(1)</script> **unfinished").text)
        assertEquals("file_name_here", SafeRichText.render("file_name_here").text)
    }
}
