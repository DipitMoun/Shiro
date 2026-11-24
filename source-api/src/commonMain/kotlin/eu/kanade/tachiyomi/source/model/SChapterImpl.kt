@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY

class SChapterImpl : SChapter {

    override lateinit var url: String

    override lateinit var name: String

    override var date_upload: Long = 0

    override var chapter_number: Float = -1f

    override var scanlator: String? = null

    // Not part of Java serialization: JsonObject is not Serializable, so it is rebuilt empty.
    @Transient
    private var memoValue: JsonObject? = null

    override var memo: JsonObject
        get() = memoValue ?: JsonObject.EMPTY
        set(value) {
            memoValue = value
        }
}
