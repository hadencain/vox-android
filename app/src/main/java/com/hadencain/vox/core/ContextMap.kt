package com.hadencain.vox.core

/** Foreground package -> human phrase for the cleanup prompt (Android analog of desktop
 *  context.py). Null for unknown apps so we don't over-constrain the model. */
object ContextMap {
    private val CATEGORIES = mapOf(
        "com.google.android.apps.messaging" to "a chat/messaging app",
        "com.whatsapp" to "a chat/messaging app",
        "org.telegram.messenger" to "a chat/messaging app",
        "com.discord" to "a chat/messaging app",
        "com.Slack" to "a chat/messaging app",
        "org.thoughtcrime.securesms" to "a chat/messaging app",
        "com.google.android.gm" to "an email client",
        "com.microsoft.office.outlook" to "an email client",
        "com.google.android.keep" to "a notes app",
        "md.obsidian" to "markdown notes",
        "com.notion.id" to "a notes app",
        "com.android.chrome" to "a web browser",
        "org.mozilla.firefox" to "a web browser",
        "com.brave.browser" to "a web browser",
        "com.google.android.apps.docs.editors.docs" to "a document",
        "com.microsoft.office.word" to "a document",
    )
    fun category(packageName: String?): String? {
        if (packageName == null) return null
        val cat = CATEGORIES[packageName] ?: return null
        return "$cat ($packageName)"
    }
}
