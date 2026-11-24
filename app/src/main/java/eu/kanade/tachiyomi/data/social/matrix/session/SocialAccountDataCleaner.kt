package eu.kanade.tachiyomi.data.social.matrix.session

import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.room.RoomBindingStore

/**
 * Clears account-scoped social data that is not owned by the Rust SDK store.
 *
 * The Matrix SDK store itself is wiped by [SessionDelegate.wipeLocalData]; this
 * cleaner only resets the app-level friends and room bindings.
 */
interface SocialAccountDataCleaner {
    suspend fun clear()
}

object NoOpSocialAccountDataCleaner : SocialAccountDataCleaner {
    override suspend fun clear() = Unit
}

class LocalSocialAccountDataCleaner(
    private val friendStore: SocialFriendStore,
    private val roomBindingStore: RoomBindingStore,
) : SocialAccountDataCleaner {

    override suspend fun clear() {
        friendStore.clearAll()
        roomBindingStore.clear()
    }
}
