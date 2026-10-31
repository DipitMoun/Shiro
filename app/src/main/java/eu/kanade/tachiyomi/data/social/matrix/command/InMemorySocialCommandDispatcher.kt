package eu.kanade.tachiyomi.data.social.matrix.command

import eu.kanade.tachiyomi.data.social.matrix.SocialCommand
import eu.kanade.tachiyomi.data.social.matrix.SocialCommandDispatcher
import eu.kanade.tachiyomi.data.social.matrix.SocialResult

/**
 * SDK-free [SocialCommandDispatcher] for UI development (P2-10) and tests.
 *
 * Records every command in [executed] and returns whatever [nextResult] holds.
 * A new [nextResult] can be set before each execute to script scenarios.
 */
class InMemorySocialCommandDispatcher : SocialCommandDispatcher {

    val executed: MutableList<SocialCommand> = mutableListOf()
    var nextResult: SocialResult<String> = SocialResult.Success("event-id")
    var nextResults: MutableList<SocialResult<String>> = mutableListOf()

    override suspend fun execute(command: SocialCommand): SocialResult<String> {
        executed += command
        val result = nextResults.removeFirstOrNull() ?: nextResult
        return result
    }
}