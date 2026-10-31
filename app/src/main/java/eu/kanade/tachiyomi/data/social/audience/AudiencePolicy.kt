package eu.kanade.tachiyomi.data.social.audience

import eu.kanade.tachiyomi.data.social.identity.WorkId

data class SocialFriend(
    val userId: String,
    val displayName: String? = null,
    val hasWork: Boolean,
)

data class AudiencePlan(
    val invited: List<String>,
    val missingWork: List<String>,
    val inviteReason: String,
)

/**
 * Builds an invitation plan from the supplied first-degree contacts only.
 * Duplicate user ids keep their first entry; all output is ordered by user id.
 */
object AudiencePolicy {
    fun plan(
        selfUserId: String,
        workId: WorkId,
        friends: List<SocialFriend>,
    ): AudiencePlan {
        val directFriends = friends
            .filter { it.userId != selfUserId }
            .distinctBy { it.userId }
            .sortedBy { it.userId }

        return AudiencePlan(
            invited = directFriends.map { it.userId },
            missingWork = directFriends.filterNot { it.hasWork }.map { it.userId },
            inviteReason = "Shiro — ${workId.value}",
        )
    }
}
