package eu.kanade.domain.items.chapter

import android.app.Application
import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Keeps the `memo` an extension attaches to a chapter (extensions-lib 1.6) between the moment the
 * chapter list is read and the moment its pages are asked for. The chapter table has no column
 * for it, and an extension that relies on it would otherwise get an empty memo and fail.
 *
 * Only non-empty memos are stored, so this stays small.
 */
object ChapterMemoStore {

    private const val PREFS = "shiro_chapter_memo"

    private val prefs by lazy {
        Injekt.get<Application>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun key(mangaId: Long, chapterUrl: String) = "$mangaId|$chapterUrl"

    fun put(mangaId: Long, chapterUrl: String, memo: JsonObject) {
        if (memo.isEmpty()) return
        prefs.edit().putString(key(mangaId, chapterUrl), memo.toString()).apply()
    }

    fun get(mangaId: Long, chapterUrl: String): JsonObject? {
        val raw = prefs.getString(key(mangaId, chapterUrl), null) ?: return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }
}
