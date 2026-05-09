package com.odyssey.app

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("odyssey-settings")

private object Keys {
    val NAS_URL = stringPreferencesKey("nas_url")
    val NAS_TOKEN = stringPreferencesKey("nas_token")
    val RETENTION = intPreferencesKey("retention_count")
    val LAST_SEEN_EID = longPreferencesKey("last_seen_episode_id")
    val LAST_RUN_AT = longPreferencesKey("last_run_at_ms")
    val ALLOW_METERED = booleanPreferencesKey("allow_metered_downloads")
    val CF_CLIENT_ID = stringPreferencesKey("cf_access_client_id")
    val CF_CLIENT_SECRET = stringPreferencesKey("cf_access_client_secret")
}

data class Settings(
    val nasUrl: String,
    val nasToken: String,
    val retentionCount: Int,
    val lastSeenEpisodeId: Long,
    val lastRunAtMs: Long,
    val allowMeteredDownloads: Boolean,
    /**
     * Cloudflare Access service-token credentials. Required when the
     * backup server is exposed via a Cloudflare Tunnel + Access app —
     * the tunnel rejects every request that doesn't carry both
     * headers (or a logged-in user cookie, which the app doesn't
     * have). Empty for LAN / Tailscale-only deployments.
     */
    val cfAccessClientId: String,
    val cfAccessClientSecret: String,
) {
    val nasConfigured: Boolean get() = nasUrl.isNotBlank() && nasToken.isNotBlank()
    val cfAccessConfigured: Boolean
        get() = cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()
}

@Singleton
class SettingsRepo @Inject constructor(@ApplicationContext private val ctx: Context) {

    val flow: Flow<Settings> = ctx.dataStore.data.map { p ->
        Settings(
            nasUrl = p[Keys.NAS_URL].orEmpty(),
            nasToken = p[Keys.NAS_TOKEN].orEmpty(),
            retentionCount = p[Keys.RETENTION] ?: 7,
            lastSeenEpisodeId = p[Keys.LAST_SEEN_EID] ?: 0L,
            lastRunAtMs = p[Keys.LAST_RUN_AT] ?: 0L,
            allowMeteredDownloads = p[Keys.ALLOW_METERED] ?: false,
            cfAccessClientId = p[Keys.CF_CLIENT_ID].orEmpty(),
            cfAccessClientSecret = p[Keys.CF_CLIENT_SECRET].orEmpty(),
        )
    }

    suspend fun setNas(url: String, token: String) = ctx.dataStore.edit {
        it[Keys.NAS_URL] = url.trim().trimEnd('/')
        it[Keys.NAS_TOKEN] = token.trim()
    }

    /**
     * Persist the optional Cloudflare Access service-token pair.
     * Blank values clear the headers — useful when switching a phone
     * back from "friend joining over Cloudflare" to "owner on the
     * LAN" without uninstalling.
     */
    suspend fun setCloudflareAccess(clientId: String, clientSecret: String) =
        ctx.dataStore.edit {
            it[Keys.CF_CLIENT_ID] = clientId.trim()
            it[Keys.CF_CLIENT_SECRET] = clientSecret.trim()
        }

    suspend fun setRetention(n: Int) = ctx.dataStore.edit { it[Keys.RETENTION] = n.coerceIn(1, 100) }
    suspend fun setLastSeen(id: Long) = ctx.dataStore.edit { it[Keys.LAST_SEEN_EID] = id }
    suspend fun setLastRun(ms: Long) = ctx.dataStore.edit { it[Keys.LAST_RUN_AT] = ms }
    suspend fun setAllowMeteredDownloads(allow: Boolean) =
        ctx.dataStore.edit { it[Keys.ALLOW_METERED] = allow }
}
