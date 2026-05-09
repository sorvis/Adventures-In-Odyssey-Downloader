package com.odyssey.nas

import com.odyssey.app.Settings
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the CF-Access header decoration. Verifies
 * the exact header names + that the headers stay off the request
 * when the user hasn't filled the fields in (LAN/Tailscale case).
 */
class CfAccessHeadersTest {

    private fun settings(
        cfId: String = "",
        cfSecret: String = "",
    ) = Settings(
        nasUrl = "http://x:8088",
        nasToken = "tok",
        retentionCount = 7,
        lastSeenEpisodeId = 0L,
        lastRunAtMs = 0L,
        allowMeteredDownloads = false,
        cfAccessClientId = cfId,
        cfAccessClientSecret = cfSecret,
    )

    @Test
    fun `cfAccessConfigured is true only when both id and secret are non-blank`() {
        assertTrue(settings(cfId = "id", cfSecret = "sec").cfAccessConfigured)
        assertTrue(!settings(cfId = "", cfSecret = "sec").cfAccessConfigured)
        assertTrue(!settings(cfId = "id", cfSecret = "").cfAccessConfigured)
        assertTrue(!settings().cfAccessConfigured)
    }

    @Test
    fun `applyCfAccess adds both headers when configured`() {
        val req = Request.Builder()
            .url("http://x")
            .applyCfAccess(settings(cfId = "client-abc", cfSecret = "secret-xyz"))
            .build()
        assertEquals("client-abc", req.header("CF-Access-Client-Id"))
        assertEquals("secret-xyz", req.header("CF-Access-Client-Secret"))
    }

    @Test
    fun `applyCfAccess is a no-op when fields are blank — LAN deployments stay clean`() {
        val req = Request.Builder()
            .url("http://x")
            .applyCfAccess(settings())
            .build()
        assertNull(req.header("CF-Access-Client-Id"))
        assertNull(req.header("CF-Access-Client-Secret"))
    }
}
