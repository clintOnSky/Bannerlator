package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.store.download.DownloadEntry
import com.winlator.star.store.download.DownloadRegistry
import com.winlator.star.store.download.DownloadState
import com.winlator.star.store.download.Store
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Amazon-side seeding for the cross-store Download Manager (Phase A).
 *
 * Mirror of [SteamLibrarySync]: bridges the on-disk Amazon library into the
 * store-agnostic [DownloadRegistry] so the Library section isn't empty on first open.
 * Amazon has no DB — installed state lives entirely in the `bh_amazon_prefs` file, so
 * this walks the `amazon_dir_<productId>` keys, confirms each is really installed via
 * the engine's on-disk marker ([AmazonDownloadManager.isInstalled]) or a recorded
 * launch exe, and upserts an INSTALLED [DownloadEntry] per game (which the registry
 * persists to its durable library).
 *
 * Name / cover come from the `amazon_library_cache` JSON the games screen writes (same
 * prefs); missing metadata falls back to the productId so a row still renders.
 *
 * Lives on the Amazon side ON PURPOSE — the registry imports zero store types. Call once
 * early (see [AmazonGameDetailActivity.onCreate] / [AmazonGamesActivity.onCreate]), right
 * after [DownloadRegistry.init]. Idempotent; never throws into startup.
 */
object AmazonLibrarySync {

    private const val TAG = "AmazonLibrarySync"
    private const val PREFS_NAME = "bh_amazon_prefs"
    private const val CACHE_KEY = "amazon_library_cache"
    private const val DIR_PREFIX = "amazon_dir_"

    private val seeded = AtomicBoolean(false)

    /**
     * One-time sync of the installed Amazon library into the registry. Safe to call
     * repeatedly; only the first call does work. Never throws — a seeding failure must
     * not take down store startup.
     */
    fun seed(ctx: Context) {
        if (!seeded.compareAndSet(false, true)) return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            // productId -> (title, artUrl) from the games-screen cache (best-effort).
            val meta = HashMap<String, Pair<String, String?>>()
            prefs.getString(CACHE_KEY, null)?.let { json ->
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) {
                        val j = arr.optJSONObject(i) ?: continue
                        val pid = j.optString("productId", "")
                        if (pid.isEmpty()) continue
                        val title = j.optString("title", "")
                        val art = j.optString("artUrl", "").ifEmpty { null }
                        meta[pid] = title to art
                    }
                }
            }

            var added = 0
            // Snapshot the keys — walking prefs.all directly while upserting is fine (no
            // concurrent writer at startup), but a copy keeps it obviously safe.
            for ((k, v) in prefs.all) {
                if (!k.startsWith(DIR_PREFIX)) continue
                val productId = k.substring(DIR_PREFIX.length)
                if (productId.isEmpty()) continue
                val dir = (v as? String) ?: continue
                if (dir.isEmpty()) continue

                val installDir = File(dir)
                // Consider installed if the engine's completion marker is present OR a launch
                // exe was recorded (covers games installed before the marker existed — matches
                // AmazonGameDetailActivity.refreshActionState's `installed = exe != null`).
                val exe = prefs.getString("amazon_exe_$productId", null)
                val installed = AmazonDownloadManager.isInstalled(installDir) || exe != null
                if (!installed) continue

                val key = "${Store.AMAZON}:$productId"
                // Don't clobber a live/queued download that may already own this key.
                if (DownloadRegistry.isActive(key)) continue

                val (title, art) = meta[productId] ?: (productId to null)
                val name = title.ifEmpty { productId }
                val bytes = prefs.getLong("amazon_size_$productId", 0L).let { if (it > 0L) it else 0L }

                DownloadRegistry.upsert(
                    DownloadEntry(
                        store = Store.AMAZON,
                        id = productId,
                        name = name,
                        cover = art,
                        state = DownloadState.INSTALLED,
                        pct = 100,
                        installDone = bytes,
                        installTotal = bytes,
                        supportsPause = false,
                        installPath = installDir.absolutePath,
                    )
                )
                added++
            }
            Log.i(TAG, "Seeded $added installed Amazon game(s) into DownloadRegistry")
        } catch (t: Throwable) {
            // Reset so a later call (once prefs are ready) can retry.
            seeded.set(false)
            Log.w(TAG, "Library seed skipped: ${t.message}")
        }
    }
}
