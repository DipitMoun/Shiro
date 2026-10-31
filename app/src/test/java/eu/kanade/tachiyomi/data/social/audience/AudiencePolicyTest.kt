package eu.kanade.tachiyomi.data.social.audience

import eu.kanade.tachiyomi.data.social.identity.WorkId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AudiencePolicyTest {

    private val workId = WorkId.parse("anilist:154587")!!

    @Test
    fun `self is excluded from invitations and missing work`() {
        val plan = AudiencePolicy.plan(
            selfUserId = "@alice:example.org",
            workId = workId,
            friends = listOf(
                SocialFriend("@alice:example.org", hasWork = false),
                SocialFriend("@bob:example.org", hasWork = true),
            ),
        )

        assertEquals(listOf("@bob:example.org"), plan.invited)
        assertEquals(emptyList<String>(), plan.missingWork)
    }

    @Test
    fun `every supplied direct friend is invited and nobody else`() {
        val plan = AudiencePolicy.plan(
            selfUserId = "@alice:example.org",
            workId = workId,
            friends = listOf(
                SocialFriend("@chloe:example.org", displayName = "Chloé", hasWork = true),
                SocialFriend("@bob:example.org", displayName = "Bob", hasWork = true),
            ),
        )

        assertEquals(listOf("@bob:example.org", "@chloe:example.org"), plan.invited)
    }

    @Test
    fun `friends without the work are invited and reported for the banner`() {
        val plan = AudiencePolicy.plan(
            selfUserId = "@alice:example.org",
            workId = workId,
            friends = listOf(
                SocialFriend("@bob:example.org", hasWork = true),
                SocialFriend("@chloe:example.org", hasWork = false),
                SocialFriend("@dave:example.org", hasWork = false),
            ),
        )

        assertEquals(
            listOf("@bob:example.org", "@chloe:example.org", "@dave:example.org"),
            plan.invited,
        )
        assertEquals(listOf("@chloe:example.org", "@dave:example.org"), plan.missingWork)
    }

    @Test
    fun `duplicates use their first entry and results are ordered by user id`() {
        val plan = AudiencePolicy.plan(
            selfUserId = "@alice:example.org",
            workId = workId,
            friends = listOf(
                SocialFriend("@dave:example.org", hasWork = true),
                SocialFriend("@bob:example.org", hasWork = false),
                SocialFriend("@dave:example.org", hasWork = false),
                SocialFriend("@chloe:example.org", hasWork = true),
                SocialFriend("@bob:example.org", hasWork = true),
            ),
        )

        assertEquals(
            listOf("@bob:example.org", "@chloe:example.org", "@dave:example.org"),
            plan.invited,
        )
        assertEquals(listOf("@bob:example.org"), plan.missingWork)
    }

    @Test
    fun `empty friends produce a valid empty plan`() {
        val plan = AudiencePolicy.plan(
            selfUserId = "@alice:example.org",
            workId = workId,
            friends = emptyList(),
        )

        assertEquals(AudiencePlan(emptyList(), emptyList(), "Shiro — anilist:154587"), plan)
    }

    @Test
    fun `invite reason contains only shiro and the canonical work id`() {
        val plan = AudiencePolicy.plan(
            selfUserId = "@alice:example.org",
            workId = workId,
            friends = listOf(SocialFriend("@bob:example.org", hasWork = true)),
        )

        assertEquals("Shiro — anilist:154587", plan.inviteReason)
    }
}
