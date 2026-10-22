package eu.kanade.tachiyomi.data.telegram

import android.content.Context
import androidx.core.content.edit

class TelegramPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("telegram_prefs", Context.MODE_PRIVATE)

    var videoChats: Set<Long>
        get() = prefs.getStringSet("video_chats", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet("video_chats", value.map { it.toString() }.toSet()) }

    var docChats: Set<Long>
        get() = prefs.getStringSet("doc_chats", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
        set(value) = prefs.edit { putStringSet("doc_chats", value.map { it.toString() }.toSet()) }
}
