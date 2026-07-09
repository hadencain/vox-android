package com.hadencain.vox.core

import org.junit.Assert.*
import org.junit.Test

class ContextMapTest {
    @Test fun `known packages map to categories`() {
        assertEquals("a chat/messaging app (com.google.android.apps.messaging)", ContextMap.category("com.google.android.apps.messaging"))
        assertEquals("an email client (com.google.android.gm)", ContextMap.category("com.google.android.gm"))
        assertEquals("a notes app (com.google.android.keep)", ContextMap.category("com.google.android.keep"))
    }
    @Test fun `unknown or null package returns null`() {
        assertNull(ContextMap.category("com.example.unknown"))
        assertNull(ContextMap.category(null))
    }
}
