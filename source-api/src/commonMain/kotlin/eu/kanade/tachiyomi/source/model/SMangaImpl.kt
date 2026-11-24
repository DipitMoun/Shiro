@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import mihon.core.common.extensions.EMPTY

class SMangaImpl : SManga {

    override lateinit var url: String

    override lateinit var title: String

    override var artist: String? = null

    override var author: String? = null

    override var description: String? = null

    override var genre: String? = null

    override var status: Int = 0

    override var thumbnail_url: String? = null

    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE

    override var initialized: Boolean = false

    // Not part of Java serialization: JsonObject is not Serializable, so it is rebuilt empty.
    @Transient
    private var memoValue: JsonObject? = null

    override var memo: JsonObject
        get() = memoValue ?: JsonObject.EMPTY
        set(value) {
            memoValue = value
        }
}
