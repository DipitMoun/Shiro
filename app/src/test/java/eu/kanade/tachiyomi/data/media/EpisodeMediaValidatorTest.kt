package eu.kanade.tachiyomi.data.media

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpisodeMediaValidatorTest {

    @Test
    fun `images and gifs are rejected before download`() {
        assertTrue(EpisodeMediaValidator.isImageOrGif("https://cdn.example/banner.jpg"))
        assertTrue(EpisodeMediaValidator.isImageOrGif("https://cdn.example/episode.gif?token=1"))
        assertTrue(EpisodeMediaValidator.isImageOrGif("https://cdn.example/cover.webp"))
        assertFalse(EpisodeMediaValidator.isImageOrGif("https://cdn.example/episode.m3u8"))
        assertFalse(EpisodeMediaValidator.isImageOrGif("https://cdn.example/video.mp4?token=1"))
    }

    @Test
    fun `episode below 50 mebibytes is rejected`() {
        assertTrue(EpisodeMediaValidator.isTooSmall(EpisodeMediaValidator.MIN_EPISODE_BYTES - 1))
        assertFalse(EpisodeMediaValidator.isTooSmall(EpisodeMediaValidator.MIN_EPISODE_BYTES))
        assertFalse(EpisodeMediaValidator.isTooSmall(null))
    }
}
