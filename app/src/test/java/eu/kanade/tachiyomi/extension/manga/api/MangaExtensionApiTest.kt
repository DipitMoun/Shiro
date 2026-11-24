package eu.kanade.tachiyomi.extension.manga.api

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Uses three real entries of the Keiyoushi extensions-lib 1.6 index. */
class MangaExtensionApiTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo"

    @Test
    fun `reads the extensions of a new format index`() {
        val extensions = parseNewIndex(json, INDEX, repoUrl, fallbackSigningKey = "")

        assertNotNull(extensions)
        assertEquals(listOf("Punk Records", "Ahri Gallery", "Airibox"), extensions!!.map { it.name })
    }

    @Test
    fun `a single language extension keeps its language and its direct download link`() {
        val punk = parseNewIndex(json, INDEX, repoUrl, "")!!.first { it.name == "Punk Records" }

        assertEquals("fr", punk.lang)
        assertEquals(1.6, punk.libVersion, 0.0)
        assertEquals(106004L, punk.versionCode)
        assertTrue(punk.apkUrl!!.startsWith("https://"))
        assertEquals(punk.apkUrl!!.substringAfterLast('/'), punk.apkName)
        assertFalse(punk.isNsfw)
        assertEquals(4646674279369583651L, punk.sources.single().id)
    }

    @Test
    fun `an nsfw content warning is flagged`() {
        val extensions = parseNewIndex(json, INDEX, repoUrl, "")!!

        assertTrue(extensions.first { it.name == "Airibox" }.isNsfw)
        assertTrue(extensions.first { it.name == "Ahri Gallery" }.isNsfw)
    }

    @Test
    fun `an extension whose sources do not share a language is listed as all`() {
        val ahri = parseNewIndex(json, INDEX, repoUrl, "")!!.first { it.name == "Ahri Gallery" }

        assertEquals("all", ahri.lang)
        assertEquals(3, ahri.sources.size)
    }

    @Test
    fun `the repository key wins and the index key is the fallback`() {
        assertEquals("repo-key", parseNewIndex(json, INDEX, repoUrl, "repo-key")!!.first().signingKey)
        assertEquals(64, parseNewIndex(json, INDEX, repoUrl, "")!!.first().signingKey.length)
    }

    @Test
    fun `a legacy array index is not a new format index`() {
        assertNull(parseNewIndex(json, """[{"name":"x","pkg":"p"}]""", repoUrl, ""))
    }

    @Test
    fun `extensions built for an unsupported library version are left out`() {
        val future = INDEX.replace(""""extensionLib": "1.6"""", """"extensionLib": "2.0"""")

        assertEquals(emptyList<Any>(), parseNewIndex(json, future, repoUrl, ""))
    }

    private companion object {
        val INDEX = """
{
  "name": "Keiyoushi",
  "badgeLabel": "KEI",
  "signingKey": "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
  "contact": { "website": "https://keiyoushi.github.io", "discord": "https://discord.gg/3FbCpdKbdY" },
  "extensionList": {
    "extensions": [
      {
        "name": "Punk Records",
        "packageName": "eu.kanade.tachiyomi.extension.all.littlegarden",
        "resources": {
          "apkUrl": "https://github.com/keiyoushi/extensions/releases/download/b4f9106-0/tachiyomi-all.littlegarden-v1.6.4.apk",
          "iconUrl": "https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/fr/punkrecords/res/mipmap-xhdpi/ic_launcher.png",
          "jarUrl": "https://github.com/keiyoushi/extensions/releases/download/b4f9106-0/tachiyomi-all.littlegarden-v1.6.4.jar"
        },
        "extensionLib": "1.6",
        "versionCode": "106004",
        "versionName": "1.6.4",
        "contentWarning": "CONTENT_WARNING_SAFE",
        "sources": [
          { "id": "4646674279369583651", "name": "Punk Records", "language": "fr", "homeUrl": "https://punkrecordz.com" }
        ]
      },
      {
        "name": "Ahri Gallery",
        "packageName": "eu.kanade.tachiyomi.extension.all.ahrigallery",
        "resources": {
          "apkUrl": "https://github.com/keiyoushi/extensions/releases/download/b4f9106-0/tachiyomi-all.ahrigallery-v1.6.1.apk",
          "iconUrl": "https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/src/all/ahrigallery/res/mipmap-xhdpi/ic_launcher.png"
        },
        "extensionLib": "1.6",
        "versionCode": "106001",
        "versionName": "1.6.1",
        "contentWarning": "CONTENT_WARNING_NSFW",
        "sources": [
          { "id": "6328598225352606131", "name": "Ahri Gallery", "language": "all", "homeUrl": "https://ahri-gallery.com" },
          { "id": "1960348568282192357", "name": "Ahri Gallery", "language": "ja", "homeUrl": "https://ahri-gallery.com" },
          { "id": "4258304358617590905", "name": "Ahri Gallery", "language": "en", "homeUrl": "https://ahri-gallery.com" }
        ]
      },
      {
        "name": "Airibox",
        "packageName": "eu.kanade.tachiyomi.extension.all.airibox",
        "resources": {
          "apkUrl": "https://github.com/keiyoushi/extensions/releases/download/b4f9106-0/tachiyomi-all.airibox-v1.6.1.apk"
        },
        "extensionLib": "1.6",
        "versionCode": "106001",
        "versionName": "1.6.1",
        "contentWarning": "CONTENT_WARNING_NSFW",
        "sources": [
          { "id": "8745980356692752458", "name": "Airibox", "language": "all", "homeUrl": "https://airibox.top" }
        ]
      }
    ]
  }
}
        """.trimIndent()
    }
}
