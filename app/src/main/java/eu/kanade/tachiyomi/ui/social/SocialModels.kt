package eu.kanade.tachiyomi.ui.social

data class SocialAuthor(
    val id: String,
    val name: String,
    val initials: String,
    val colorArgb: Long,
    val unread: Int = 0,
)

data class SocialMoment(
    val id: String,
    val positionMs: Long,
    val label: String? = null,
)

data class SocialMessage(
    val id: String,
    val authorId: String,
    val text: String,
    val positionMs: Long? = null,
    val reactions: Map<String, Int> = emptyMap(),
    val pinned: Boolean = false,
)

data class SocialThread(
    val id: String,
    val authorId: String,
    val audienceLabel: String,
    val messages: List<SocialMessage>,
    val moments: List<SocialMoment> = emptyList(),
)

data class SocialMomentContext(
    val workTitle: String,
    val episodeLabel: String,
    val positionMs: Long,
    val durationMs: Long,
)

fun formatTimecode(positionMs: Long): String {
    val totalSeconds = positionMs.coerceAtLeast(0L) / 1000
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val minutes = ((totalSeconds / 60) % 60).toString().padStart(2, '0')
    return if (totalSeconds >= 3600) {
        "${totalSeconds / 3600}:$minutes:$seconds"
    } else {
        "${(totalSeconds / 60).toString().padStart(2, '0')}:$seconds"
    }
}

fun loopWindow(
    anchorMs: Long,
    durationMs: Long,
    beforeMs: Long = 15_000L,
    afterMs: Long = 15_000L,
): LongRange {
    if (durationMs <= 0L) return anchorMs..anchorMs
    if (durationMs < 30_000L || durationMs < beforeMs + afterMs) return 0L..durationMs

    val start = (anchorMs - beforeMs).coerceIn(0L, durationMs)
    val end = (anchorMs + afterMs).coerceIn(start, durationMs)
    return start..end
}

object SocialDemoData {

    const val ME = "me"

    fun authors(): List<SocialAuthor> = listOf(
        SocialAuthor(id = "kevin", name = "Kevin", initials = "KV", colorArgb = 0xFF2F6FED, unread = 2),
        SocialAuthor(id = "allan", name = "Allan", initials = "AL", colorArgb = 0xFFE08A2E, unread = 0),
        SocialAuthor(id = "david", name = "David", initials = "DV", colorArgb = 0xFF8C5BE8, unread = 1),
        SocialAuthor(id = ME, name = "Toi", initials = "TO", colorArgb = 0xFF1F9D67),
    )

    fun threads(context: SocialMomentContext): List<SocialThread> = listOf(
        SocialThread(
            id = "thread-kevin",
            authorId = "kevin",
            audienceLabel = "Amis de Kevin",
            messages = listOf(
                SocialMessage(
                    id = "kevin-1",
                    authorId = "kevin",
                    text = "Le plan sur la ville au début est incroyable.",
                    positionMs = positionAround(context, -12_000L),
                ),
                SocialMessage(
                    id = "kevin-2",
                    authorId = "kevin",
                    text = "Attends, il vient vraiment de faire ça ?",
                    positionMs = positionAround(context, -4_000L),
                    reactions = mapOf("😱" to 3),
                ),
                SocialMessage(
                    id = "kevin-3",
                    authorId = "kevin",
                    text = "Même moi je n'avais pas vu venir le coup.",
                    positionMs = positionAround(context, 0L),
                    reactions = mapOf("😂" to 2, "🔥" to 1),
                ),
            ),
            moments = listOf(
                SocialMoment(
                    id = "kevin-moment-1",
                    positionMs = positionAround(context, -4_000L),
                    label = "Le retournement",
                ),
                SocialMoment(
                    id = "kevin-moment-2",
                    positionMs = positionAround(context, 18_000L),
                    label = "Fin du combat",
                ),
            ).sortedBy { it.positionMs },
        ),
        SocialThread(
            id = "thread-allan",
            authorId = "allan",
            audienceLabel = "Amis d'Allan",
            messages = listOf(
                SocialMessage(
                    id = "allan-1",
                    authorId = "allan",
                    text = "La bande-son porte toute la scène.",
                    positionMs = positionAround(context, -8_000L),
                ),
                SocialMessage(
                    id = "allan-2",
                    authorId = "allan",
                    text = "Ce silence juste avant, frissons garantis.",
                    positionMs = positionAround(context, 2_000L),
                    reactions = mapOf("🔥" to 2),
                ),
            ),
            moments = listOf(
                SocialMoment(
                    id = "allan-moment-1",
                    positionMs = positionAround(context, 2_000L),
                    label = "Le silence",
                ),
            ),
        ),
        SocialThread(
            id = "thread-david",
            authorId = "david",
            audienceLabel = "Amis de David",
            messages = listOf(
                SocialMessage(
                    id = "david-1",
                    authorId = "david",
                    text = "On en parle après l'épisode ?",
                    positionMs = positionAround(context, -15_000L),
                    reactions = mapOf("👍" to 1),
                ),
                SocialMessage(
                    id = "david-2",
                    authorId = "david",
                    text = "Je parie qu'il revient au prochain épisode.",
                    positionMs = positionAround(context, -2_000L),
                ),
            ),
            moments = listOf(
                SocialMoment(
                    id = "david-moment-1",
                    positionMs = positionAround(context, -2_000L),
                    label = "L'indice",
                ),
            ),
        ),
    )

    fun myThread(context: SocialMomentContext): SocialThread = SocialThread(
        id = "thread-me",
        authorId = ME,
        audienceLabel = "Tes amis",
        messages = emptyList(),
        moments = emptyList(),
    )

    private fun positionAround(context: SocialMomentContext, deltaMs: Long): Long {
        val target = context.positionMs + deltaMs
        return if (context.durationMs > 0L) {
            target.coerceIn(0L, context.durationMs)
        } else {
            target.coerceAtLeast(0L)
        }
    }
}
