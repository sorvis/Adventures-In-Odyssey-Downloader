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
    /**
     * Legacy AIO-only lastSeen cursor. Kept readable so existing
     * installs don't lose their cursor on upgrade; new code reads via
     * `SettingsRepo.lastSeenFor("aio")` which falls back to this when
     * the new per-provider key is unset.
     */
    val LAST_SEEN_EID = longPreferencesKey("last_seen_episode_id")
    val LAST_RUN_AT = longPreferencesKey("last_run_at_ms")
    val ALLOW_METERED = booleanPreferencesKey("allow_metered_downloads")
    val CF_CLIENT_ID = stringPreferencesKey("cf_access_client_id")
    val CF_CLIENT_SECRET = stringPreferencesKey("cf_access_client_secret")
    /**
     * Currently-selected show on the main screen. Drives which set
     * of episodes the UI filters to and which artist string the
     * MediaMetadata picks up for an episode whose provider matches.
     * Defaults to "aio" — existing users see no change on upgrade.
     */
    val ACTIVE_SHOW = stringPreferencesKey("active_show")
    /**
     * Provider ids the daily-check worker is allowed to ingest from.
     * YSH defaults OFF per design — the user discovers it via the
     * "Manage shows…" entry on the switcher dropdown.
     */
    val ENABLED_PROVIDERS = stringSetPreferencesKey("enabled_providers")
}

/**
 * Per-provider lastSeen cursor — `externalId` (stringified) of the
 * newest episode the daily-check worker has already ingested for the
 * given provider. Keyed by provider id so each show advances
 * independently; YSH-FreeStream providers can leave this unset
 * because they're snapshot-source providers (no chronological cursor).
 */
private fun lastSeenKey(providerId: String) =
    stringPreferencesKey("last_seen_external_id__$providerId")

/**
 * Per-provider retention cap — how many downloaded episodes of this
 * show to keep on the phone before retention prunes the oldest.
 * Pre-v0.1.66 there was a single `retention_count` shared across every
 * provider. With YSH downloads (which never archive to NAS) counting
 * toward the same budget, a small retention number squeezed the AIO
 * slot to nothing. Per-provider keys let each show keep its own ring
 * size; the legacy `Keys.RETENTION` is the AIO fallback so existing
 * installs keep the user-set number on upgrade.
 */
private fun retentionKey(providerId: String) =
    intPreferencesKey("retention_count__$providerId")

const val DEFAULT_RETENTION = 7

data class Settings(
    val nasUrl: String,
    val nasToken: String,
    /**
     * Legacy AIO-only retention cap, kept readable as
     * `settings.retentionCount` so existing call sites (and tests)
     * keep compiling. New code paths should call
     * `SettingsRepo.retentionCountFor(providerId)` which honors the
     * per-provider override and falls back to this value for AIO.
     */
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
            retentionCount = p[Keys.RETENTION] ?: DEFAULT_RETENTION,
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

    /**
     * Legacy AIO-only retention setter. Mirrors the value into both
     * the legacy `Keys.RETENTION` key (so older app versions that
     * still read `settings.retentionCount` see the right number after
     * a downgrade) and the new per-provider key for AIO.
     */
    suspend fun setRetention(n: Int) = ctx.dataStore.edit {
        val coerced = n.coerceIn(1, 100)
        it[Keys.RETENTION] = coerced
        it[retentionKey("aio")] = coerced
    }

    /**
     * Per-provider retention cap. Reads `retention_count__<providerId>`
     * first; for AIO falls back to the legacy `Keys.RETENTION` so
     * existing installs keep the user-set value on upgrade. Defaults
     * to [DEFAULT_RETENTION] for every other provider on first run.
     */
    fun retentionCountFor(providerId: String): Flow<Int> = ctx.dataStore.data.map { p ->
        p[retentionKey(providerId)]
            ?: if (providerId == "aio") (p[Keys.RETENTION] ?: DEFAULT_RETENTION)
            else DEFAULT_RETENTION
    }

    /**
     * Persist a per-provider retention cap. Setting AIO also writes
     * through to the legacy key for downgrade safety; other providers
     * only touch their own key.
     */
    suspend fun setRetentionFor(providerId: String, n: Int) = ctx.dataStore.edit {
        val coerced = n.coerceIn(1, 100)
        it[retentionKey(providerId)] = coerced
        if (providerId == "aio") it[Keys.RETENTION] = coerced
    }
    suspend fun setLastSeen(id: Long) = ctx.dataStore.edit { it[Keys.LAST_SEEN_EID] = id }
    suspend fun setLastRun(ms: Long) = ctx.dataStore.edit { it[Keys.LAST_RUN_AT] = ms }
    suspend fun setAllowMeteredDownloads(allow: Boolean) =
        ctx.dataStore.edit { it[Keys.ALLOW_METERED] = allow }

    // -----------------------------------------------------------------
    // Per-provider lastSeen API (multi-show prep, step 2 of YSH plan).
    //
    // The legacy long-keyed cursor (`lastSeenEpisodeId` above) stays in
    // place during the transition: DailyCheckWorker still writes through
    // it for AIO until step 3 rewrites the worker to iterate providers.
    // `lastSeenFor("aio")` reads the new key first, falls back to the
    // legacy long, so existing installs don't lose their cursor on
    // upgrade.
    // -----------------------------------------------------------------

    /**
     * Newest externalId already ingested for `providerId`, or null on
     * a fresh install (or for snapshot-source providers that never
     * advance a cursor).
     *
     * AIO has a legacy-key fallback: pre-upgrade installs stored the
     * cursor as `Keys.LAST_SEEN_EID` (Long). When the new
     * `last_seen_external_id__aio` key is unset, we surface the legacy
     * value stringified so the worker doesn't re-pull 50 episodes on
     * the first run of the new version.
     */
    fun lastSeenFor(providerId: String): Flow<String?> = ctx.dataStore.data.map { p ->
        p[lastSeenKey(providerId)]
            ?: if (providerId == "aio") {
                p[Keys.LAST_SEEN_EID]?.takeIf { it != 0L }?.toString()
            } else null
    }

    suspend fun setLastSeen(providerId: String, externalId: String) =
        ctx.dataStore.edit { it[lastSeenKey(providerId)] = externalId }

    // -----------------------------------------------------------------
    // Active-show + per-provider enable (step 9 of YSH plan).
    //
    // activeShow is purely UX state — which show the user is browsing
    // right now. Defaults to "aio" so existing installs don't notice
    // any change until they tap the show-switcher dropdown.
    //
    // enabledProviders gates the daily-check worker's ingestion. YSH
    // defaults to OFF — the user discovers it via the "Manage shows…"
    // entry below the active-shows list in the dropdown. AIO defaults
    // to ON for everyone.
    // -----------------------------------------------------------------

    val activeShow: Flow<String> = ctx.dataStore.data.map { p ->
        p[Keys.ACTIVE_SHOW] ?: "aio"
    }

    suspend fun setActiveShow(providerId: String) =
        ctx.dataStore.edit { it[Keys.ACTIVE_SHOW] = providerId }

    val enabledProviders: Flow<Set<String>> = ctx.dataStore.data.map { p ->
        p[Keys.ENABLED_PROVIDERS] ?: setOf("aio")
    }

    suspend fun setProviderEnabled(providerId: String, enabled: Boolean) =
        ctx.dataStore.edit { p ->
            val current = p[Keys.ENABLED_PROVIDERS] ?: setOf("aio")
            p[Keys.ENABLED_PROVIDERS] = if (enabled) current + providerId else current - providerId
        }

    /**
     * Wipe all stored preferences. Test-only — Robolectric reuses the
     * Application across tests in a class, so DataStore values leak
     * between @Test methods otherwise. Production code never calls this.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun clearAllForTest() {
        ctx.dataStore.edit { it.clear() }
    }
}
