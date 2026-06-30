package com.winlator.star.reshade

import com.winlator.star.contents.Downloader
import org.json.JSONObject

/**
 * One downloadable ReShade effect from the LIVE catalog (reshade.json) hosted on the winlator-contents
 * repo — the SAME repo the app uses for components.json / contents.json. The catalog index lives at
 * the repo root; each effect's archive is a GitHub RELEASE ASSET (not a raw repo file), referenced by
 * the entry's explicit "url" field.
 *
 * Catalog URL:  https://raw.githubusercontent.com/The412Banner/winlator-contents/main/reshade.json
 *
 * Each archive is a zstd-compressed tar (.tzst) whose tar contains the folder "<id>/..." (the .fx plus
 * its co-located .fxh includes and any textures). It extracts into the ReShade ROOT drop-in folder
 * (getExternalFilesDir/ReShade/) → yielding getExternalFilesDir/ReShade/<id>/, the exact dir
 * ReshadeManager's scanner reads. [id] is the drop-in subfolder name (uniqueness key).
 *
 * LIVE reshade.json SCHEMA (exact published field names):
 *
 *   {
 *     "schemaVersion": 1,
 *     "category": "reshade-effect",
 *     "release": "reshade-v1",
 *     "mirrorBase": "https://github.com/.../releases/download/reshade-v1/",
 *     "count": 100,
 *     "note": "...",
 *     "effects": [
 *       {
 *         "id": "Technicolor",            // drop-in subfolder name (uniqueness key)
 *         "name": "Technicolor",          // display label
 *         "description": "Technicolor — prod80",
 *         "category": "Color/Tone",       // Bloom | Sharpen | CRT-Retro | Film-Grain | Vignette | Anti-aliasing | Lens-Geometry | Color-Tone | Other
 *         "author": "prod80",
 *         "license": "MIT",
 *         "url": "https://github.com/.../releases/download/reshade-v1/Technicolor.tzst", // download verbatim
 *         "file_size": "5332",            // bytes, as a string
 *         "file_checksum": "5532...AB",   // UPPERCASE MD5 of the .tzst — verified after download
 *         "version": 1
 *       }
 *     ]
 *   }
 */
data class ReshadeCatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val author: String,
    val license: String,
    val url: String,
    val fileSize: Long,
    val checksum: String,   // UPPERCASE MD5 of the .tzst (may be blank → no verification)
    val version: Int,
)

object ReshadeCatalog {
    const val URL = "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/reshade.json"

    /** Fetch + parse the catalog. Returns an empty list on any network/parse failure (caller falls
     *  back to whatever is already in the drop-in folder). Mirrors ComponentCatalog.load(). */
    fun load(): List<ReshadeCatalogEntry> {
        val json = Downloader.downloadString(URL) ?: return emptyList()
        val root = JSONObject(json)
        val mirrorBase = root.optString("mirrorBase")
        val arr = root.optJSONArray("effects") ?: return emptyList()
        val out = ArrayList<ReshadeCatalogEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { o.optString("name") }.trim()
            if (id.isEmpty()) continue
            // Prefer the explicit url; fall back to mirrorBase + id + ".tzst".
            val url = o.optString("url").ifBlank {
                if (mirrorBase.isBlank()) "" else mirrorBase.trimEnd('/') + "/" + id + ".tzst"
            }
            if (url.isEmpty()) continue
            out.add(
                ReshadeCatalogEntry(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    description = o.optString("description"),
                    category = o.optString("category").ifBlank { "Other" },
                    author = o.optString("author"),
                    license = o.optString("license"),
                    url = url,
                    fileSize = o.optString("file_size").toLongOrNull() ?: o.optLong("file_size", 0L),
                    checksum = o.optString("file_checksum").trim().uppercase(),
                    version = o.optInt("version", 1),
                )
            )
        }
        return out
    }
}
