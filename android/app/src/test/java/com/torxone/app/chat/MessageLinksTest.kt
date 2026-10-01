package com.torxone.app.chat

import org.junit.Assert.*
import org.junit.Test

class MessageLinksTest {
    @Test fun extractsDistinctHttpLinksAndSearchableHostsWithoutNetworkRequests() {
        assertEquals(listOf("https://example.com/docs" to "example.com", "HTTP://Example.org/page" to "example.org"),
            MessageLinks.extract("See (https://example.com/docs). HTTP://Example.org/page and https://example.com/docs"))
    }
    @Test fun rejectsNonHttpSchemesCredentialsAndInvalidAuthorities() {
        assertTrue(MessageLinks.extract("javascript:alert(1) file:///secret https://user:pass@example.com/ https://").isEmpty())
        assertEquals(listOf("https://safe.example/path" to "safe.example"), MessageLinks.extract("<https://safe.example/path>"))
    }
    @Test fun boundsStoredUrlsAndWorkPerMessage() {
        assertTrue(MessageLinks.extract("https://example.com/" + "a".repeat(2048)).isEmpty())
        assertEquals(64, MessageLinks.extract((1..100).joinToString(" ") { "https://example.com/$it" }).size)
    }
}
