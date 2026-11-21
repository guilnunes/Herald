package com.guilnunes.herald.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class NotionException(message: String, val status: Int = 0) : IOException(message)

/** Minimal Notion REST client. Free API, personal internal-integration token. */
class NotionApi(private val token: () -> String?) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private suspend fun call(method: String, path: String, body: JsonObject? = null): JsonObject = withContext(Dispatchers.IO) {
        val t = token() ?: throw NotionException("No Notion token set.", 401)
        var attempt = 0
        while (true) {
            val req = Request.Builder()
                .url("https://api.notion.com/v1/$path")
                .header("Authorization", "Bearer $t")
                .header("Notion-Version", "2022-06-28")
                .method(method, body?.toString()?.toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return@withContext json.parseToJsonElement(text).jsonObject
                    (resp.code == 429 || resp.code >= 500) && attempt < 6 -> {
                        val wait = resp.header("Retry-After")?.toLongOrNull()?.times(1000) ?: (600L shl attempt)
                        attempt++
                        delay(wait.coerceAtMost(15_000))
                    }
                    else -> {
                        val msg = runCatching { (json.parseToJsonElement(text).jsonObject["message"] as? JsonPrimitive)?.content }.getOrNull()
                        throw NotionException(friendly(resp.code, msg), resp.code)
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException()
    }

    private fun friendly(code: Int, msg: String?): String = when (code) {
        401 -> "Notion rejected the token. Check it in Settings."
        403, 404 -> "Herald can't see this page. In Notion, open the page (or a page above it), tap ••• › Connections and add your Herald integration."
        else -> msg ?: "Notion error $code."
    }

    suspend fun search(query: String, cursor: String? = null): Pair<List<PageRef>, String?> {
        val body = buildJsonObject {
            put("query", query)
            put("page_size", 30)
            putJsonObject("filter") { put("property", "object"); put("value", "page") }
            putJsonObject("sort") { put("direction", "descending"); put("timestamp", "last_edited_time") }
            if (cursor != null) put("start_cursor", cursor)
        }
        val res = call("POST", "search", body)
        val pages = (res["results"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.bool("archived") || o.bool("in_trash")) return@mapNotNull null
            PageRef(
                id = TextUtil.normalizeId(o.str("id") ?: return@mapNotNull null),
                title = NotionText.pageTitle(o),
                lastEdited = o.str("last_edited_time") ?: "",
                icon = NotionText.pageIcon(o),
            )
        }
        val next = if (res.bool("has_more")) res.str("next_cursor") else null
        return pages to next
    }

    suspend fun page(id: String): JsonObject = call("GET", "pages/$id")

    suspend fun children(blockId: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var cursor: String? = null
        do {
            val q = "blocks/$blockId/children?page_size=100" + (cursor?.let { "&start_cursor=$it" } ?: "")
            val res = call("GET", q)
            (res["results"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(out::add) }
            cursor = if (res.bool("has_more")) res.str("next_cursor") else null
        } while (cursor != null)
        return out
    }
}
