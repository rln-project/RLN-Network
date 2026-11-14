package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdaterTest {
    @Test
    fun onlyOffersRlnNetworkReleases() {
        val response = """
            {
              "tag_name": "v1.2.3",
              "name": "RLN Network 1.2.3",
              "body": null,
              "published_at": "2026-11-14T12:00:00Z",
              "assets": [
                {
                  "name": "RLN-Network.apk",
                  "browser_download_url": "https://github.com/sinisgallof-pixel/RLN-Network/releases/download/v1.2.3/RLN-Network.apk",
                  "size": 42000
                },
                {
                  "name": "RLN-Network-no-cast.apk",
                  "browser_download_url": "https://github.com/sinisgallof-pixel/RLN-Network/releases/download/v1.2.3/RLN-Network-no-cast.apk",
                  "size": 42000
                },
                {
                  "name": "Metrolist.apk",
                  "browser_download_url": "https://github.com/MetrolistGroup/Metrolist/releases/download/v14.0.0/Metrolist.apk",
                  "size": 42000
                }
              ]
            }
        """.trimIndent()
        val release = Updater.parseRelease(response)
        assertEquals("1.2.3", release.versionName)
        assertEquals("", release.description)
        assertEquals(2, release.assets.size)
        assertTrue(release.assets.any { it.name == "RLN-Network.apk" })
        assertTrue(release.assets.any { it.name == "RLN-Network-no-cast.apk" })
        assertTrue(Updater.isUpdateAvailable("1.0", release.versionName))
        assertFalse(Updater.isUpdateAvailable("1.2.3", release.versionName))
    }

    @Test
    fun acceptsOnlyOfficialGithubReleaseApks() {
        assertTrue(
            RlnUpdateInstaller.isTrustedAsset(
                "https://github.com/sinisgallof-pixel/RLN-Network/releases/download/v1.2.3/RLN-Network.apk"
            )
        )
        assertFalse(
            RlnUpdateInstaller.isTrustedAsset(
                "https://github.com/MetrolistGroup/Metrolist/releases/download/v14.0.0/Metrolist.apk"
            )
        )
        assertFalse(RlnUpdateInstaller.isTrustedAsset("https://example.org/malicious.apk"))
    }
}
