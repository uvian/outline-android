package com.outline.notes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class OutlineApiException(
    val statusCode: Int,
    override val message: String,
) : IOException(message)

open class OutlineApiClient(
    private val mediaCacheDir: File? = null,
) {
    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        explicitNulls = false
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Dedicated client for S3/MinIO direct uploads (never injects Outline auth headers)
    private val storageUploadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun <T> postRpc(
        serverUrl: String,
        apiKey: String,
        method: String,
        body: JsonObject = buildJsonObject {},
        dataSerializer: KSerializer<T>,
    ): ApiResponse<T> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val base = serverUrl.trimEnd('/')
        val url = "$base/api/$method"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val parsedError = runCatching {
                    val obj = json.decodeFromString(JsonObject.serializer(), raw)
                    obj["message"]?.jsonPrimitive?.contentOrNull
                        ?: obj["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw OutlineApiException(
                    statusCode = response.code,
                    message = parsedError ?: "HTTP ${response.code}: ${response.message}",
                )
            }
            val parsed = json.decodeFromString(ApiResponse.serializer(dataSerializer), raw)
            if (!parsed.ok) {
                throw OutlineApiException(
                    statusCode = parsed.status,
                    message = parsed.message ?: parsed.error ?: "API error ($method)",
                )
            }
            parsed
        }
    }

    /**
     * Unified cancellable pagination helper across Outline RPC list endpoints.
     */
    private suspend fun <T> paginate(
        limit: Int = DEFAULT_PAGE_LIMIT,
        fetchPage: suspend (offset: Int, limit: Int) -> Pair<List<T>, PaginationDto?>,
    ): List<T> {
        val all = mutableListOf<T>()
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val (batch, pagination) = fetchPage(offset, limit)
            all.addAll(batch)
            val hasNext = if (pagination != null) {
                !pagination.nextPath.isNullOrBlank() || batch.size >= limit
            } else {
                batch.size >= limit
            }
            if (!hasNext || batch.isEmpty()) break
            offset += batch.size.coerceAtLeast(limit)
        }
        return all
    }

    open suspend fun authInfo(serverUrl: String, apiKey: String): AuthInfoData {
        val res = postRpc(serverUrl, apiKey, "auth.info", buildJsonObject {}, AuthInfoData.serializer())
        return res.data ?: throw OutlineApiException(500, "Empty auth.info response")
    }

    open suspend fun listCollections(serverUrl: String, apiKey: String): List<CollectionDto> {
        return paginate { offset, limit ->
            val res = postRpc(
                serverUrl = serverUrl,
                apiKey = apiKey,
                method = "collections.list",
                body = buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                },
                dataSerializer = ListSerializer(CollectionDto.serializer()),
            )
            res.data.orEmpty() to res.pagination
        }
    }

    open suspend fun createCollection(
        serverUrl: String,
        apiKey: String,
        name: String,
        description: String = "",
        color: String = "#0366D6",
    ): CollectionDto {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "collections.create",
            body = buildJsonObject {
                put("name", name)
                if (description.isNotBlank()) put("description", description)
                put("color", color)
            },
            dataSerializer = CollectionDto.serializer(),
        )
        return res.data ?: throw OutlineApiException(500, "Empty collections.create response")
    }

    open suspend fun collectionDocumentsTree(
        serverUrl: String,
        apiKey: String,
        collectionId: String,
    ): List<NavigationNodeDto> {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "collections.documents",
            body = buildJsonObject { put("id", collectionId) },
            dataSerializer = ListSerializer(NavigationNodeDto.serializer()),
        )
        return res.data.orEmpty()
    }

    open suspend fun listAllDocuments(
        serverUrl: String,
        apiKey: String,
        collectionId: String? = null,
    ): List<DocumentDto> {
        return paginate { offset, limit ->
            val res = postRpc(
                serverUrl = serverUrl,
                apiKey = apiKey,
                method = "documents.list",
                body = buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                    put("sort", "updatedAt")
                    put("direction", "DESC")
                    if (!collectionId.isNullOrBlank()) {
                        put("collectionId", collectionId)
                    }
                },
                dataSerializer = ListSerializer(DocumentDto.serializer()),
            )
            res.data.orEmpty() to res.pagination
        }
    }

    open suspend fun listDrafts(serverUrl: String, apiKey: String): List<DocumentDto> {
        return paginate { offset, limit ->
            val res = postRpc(
                serverUrl = serverUrl,
                apiKey = apiKey,
                method = "documents.drafts",
                body = buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                },
                dataSerializer = ListSerializer(DocumentDto.serializer()),
            )
            res.data.orEmpty() to res.pagination
        }
    }

    open suspend fun listArchivedDocuments(serverUrl: String, apiKey: String): List<DocumentDto> {
        return paginate { offset, limit ->
            val res = postRpc(
                serverUrl = serverUrl,
                apiKey = apiKey,
                method = "documents.archived",
                body = buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                    put("sort", "updatedAt")
                    put("direction", "DESC")
                },
                dataSerializer = ListSerializer(DocumentDto.serializer()),
            )
            res.data.orEmpty() to res.pagination
        }
    }

    open suspend fun listDeletedDocuments(serverUrl: String, apiKey: String): List<DocumentDto> {
        return paginate { offset, limit ->
            val res = postRpc(
                serverUrl = serverUrl,
                apiKey = apiKey,
                method = "documents.deleted",
                body = buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                    put("sort", "updatedAt")
                    put("direction", "DESC")
                },
                dataSerializer = ListSerializer(DocumentDto.serializer()),
            )
            res.data.orEmpty() to res.pagination
        }
    }

    open suspend fun moveDocument(
        serverUrl: String,
        apiKey: String,
        documentId: String,
        collectionId: String,
        parentDocumentId: String? = null,
    ) {
        postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.move",
            body = buildJsonObject {
                put("id", documentId)
                put("collectionId", collectionId)
                if (!parentDocumentId.isNullOrBlank()) {
                    put("parentDocumentId", parentDocumentId)
                }
            },
            dataSerializer = JsonElement.serializer(),
        )
    }

    open suspend fun archiveDocument(
        serverUrl: String,
        apiKey: String,
        documentId: String,
    ): DocumentDto {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.archive",
            body = buildJsonObject { put("id", documentId) },
            dataSerializer = DocumentDto.serializer(),
        )
        return res.data ?: throw OutlineApiException(500, "Empty documents.archive response")
    }

    open suspend fun restoreDocument(
        serverUrl: String,
        apiKey: String,
        documentId: String,
    ): DocumentDto {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.restore",
            body = buildJsonObject { put("id", documentId) },
            dataSerializer = DocumentDto.serializer(),
        )
        return res.data ?: throw OutlineApiException(500, "Empty documents.restore response")
    }

    open suspend fun getDocumentInfo(
        serverUrl: String,
        apiKey: String,
        documentId: String,
    ): DocumentDto {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.info",
            body = buildJsonObject { put("id", documentId) },
            dataSerializer = DocumentDto.serializer(),
        )
        return res.data ?: throw OutlineApiException(404, "Document not found")
    }

    open suspend fun createDocument(
        serverUrl: String,
        apiKey: String,
        title: String,
        text: String,
        collectionId: String?,
        parentDocumentId: String?,
        publish: Boolean,
    ): DocumentDto {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.create",
            body = buildJsonObject {
                put("title", title.ifBlank { "无标题笔记" })
                put("text", text)
                if (!collectionId.isNullOrBlank()) put("collectionId", collectionId)
                if (!parentDocumentId.isNullOrBlank()) put("parentDocumentId", parentDocumentId)
                put("publish", publish && !collectionId.isNullOrBlank())
            },
            dataSerializer = DocumentDto.serializer(),
        )
        return res.data ?: throw OutlineApiException(500, "Empty documents.create response")
    }

    open suspend fun updateDocument(
        serverUrl: String,
        apiKey: String,
        documentId: String,
        title: String,
        text: String,
        publish: Boolean? = null,
    ): DocumentDto {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.update",
            body = buildJsonObject {
                put("id", documentId)
                put("title", title.ifBlank { "无标题笔记" })
                put("text", text)
                if (publish != null) put("publish", publish)
                put("done", true)
            },
            dataSerializer = DocumentDto.serializer(),
        )
        return res.data ?: throw OutlineApiException(500, "Empty documents.update response")
    }

    open suspend fun deleteDocument(
        serverUrl: String,
        apiKey: String,
        documentId: String,
        permanent: Boolean = false,
    ) {
        postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.delete",
            body = buildJsonObject {
                put("id", documentId)
                put("permanent", permanent)
            },
            dataSerializer = JsonElement.serializer(),
        )
    }

    open suspend fun searchDocuments(
        serverUrl: String,
        apiKey: String,
        query: String,
        limit: Int = 25,
    ): List<SearchResultItemDto> {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "documents.search",
            body = buildJsonObject {
                put("query", query)
                put("limit", limit)
            },
            dataSerializer = ListSerializer(SearchResultItemDto.serializer()),
        )
        return res.data.orEmpty()
    }

    /**
     * Supports both latest Outline response shape:
     *   { data: { stars: [...], documents: [...] } }
     * and legacy array shape:
     *   { data: [...] }
     */
    open suspend fun listStars(serverUrl: String, apiKey: String): StarsListData {
        val allDocs = mutableListOf<DocumentDto>()
        val allStars = paginate { offset, limit ->
            val res = postRpc(
                serverUrl = serverUrl,
                apiKey = apiKey,
                method = "stars.list",
                body = buildJsonObject {
                    put("offset", offset)
                    put("limit", limit)
                },
                dataSerializer = JsonElement.serializer(),
            )
            val dataElem = res.data
            val batchStars = when (dataElem) {
                is JsonObject -> {
                    val parsed = json.decodeFromJsonElement(StarsListData.serializer(), dataElem)
                    allDocs.addAll(parsed.documents)
                    parsed.stars
                }
                is JsonArray -> {
                    json.decodeFromJsonElement(ListSerializer(StarDto.serializer()), dataElem)
                }
                else -> emptyList()
            }
            batchStars to res.pagination
        }
        return StarsListData(stars = allStars, documents = allDocs)
    }

    open suspend fun createStar(
        serverUrl: String,
        apiKey: String,
        documentId: String,
    ): StarDto {
        val res = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "stars.create",
            body = buildJsonObject { put("documentId", documentId) },
            dataSerializer = StarDto.serializer(),
        )
        return res.data ?: throw OutlineApiException(500, "Empty stars.create response")
    }

    open suspend fun deleteStar(
        serverUrl: String,
        apiKey: String,
        starId: String,
    ) {
        postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "stars.delete",
            body = buildJsonObject { put("id", starId) },
            dataSerializer = JsonElement.serializer(),
        )
    }

    /**
     * Two-step Outline attachment upload supporting MinIO / S3 presigned POST and local Outline storage.
     */
    open suspend fun uploadAttachment(
        serverUrl: String,
        apiKey: String,
        file: File,
        fileName: String,
        contentType: String,
        documentId: String?,
    ): AttachmentDto = withContext(Dispatchers.IO) {
        val createRes = postRpc(
            serverUrl = serverUrl,
            apiKey = apiKey,
            method = "attachments.create",
            body = buildJsonObject {
                put("name", fileName)
                put("contentType", contentType)
                put("size", file.length())
                if (!documentId.isNullOrBlank() && !documentId.startsWith("local_")) {
                    put("documentId", documentId)
                }
            },
            dataSerializer = AttachmentCreateData.serializer(),
        )
        val createData = createRes.data
            ?: throw OutlineApiException(500, "attachments.create returned empty data")
        val attachment = createData.attachment
            ?: throw OutlineApiException(500, "attachments.create missing attachment metadata")

        val base = serverUrl.trimEnd('/')
        val resolvedUploadUrl = when {
            createData.uploadUrl.startsWith("http://") || createData.uploadUrl.startsWith("https://") ->
                createData.uploadUrl
            createData.uploadUrl.startsWith("/") ->
                "$base${createData.uploadUrl}"
            else ->
                "$base/${createData.uploadUrl}"
        }

        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        createData.form.forEach { (key, value) ->
            val strVal = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            multipartBuilder.addFormDataPart(key, strVal)
        }
        val fileBody = file.asRequestBody(contentType.toMediaType())
        multipartBuilder.addFormDataPart("file", fileName, fileBody)

        val isSameOriginAsOutline = isSameOrigin(base, resolvedUploadUrl)
        val uploadReqBuilder = Request.Builder()
            .url(resolvedUploadUrl)
            .post(multipartBuilder.build())

        if (isSameOriginAsOutline && createData.form.none {
                it.key.equals("X-Amz-Signature", ignoreCase = true) ||
                    it.key.equals("Policy", ignoreCase = true)
            }
        ) {
            uploadReqBuilder.header("Authorization", "Bearer $apiKey")
        }

        storageUploadClient.newCall(uploadReqBuilder.build()).execute().use { uploadResp ->
            if (!uploadResp.isSuccessful && uploadResp.code != 204) {
                val errBody = uploadResp.body?.string().orEmpty().take(300)
                throw OutlineApiException(
                    statusCode = uploadResp.code,
                    message = "MinIO/存储上传失败 (${uploadResp.code}): $errBody",
                )
            }
        }

        attachment
    }

    /**
     * Streams an Outline attachment/image safely to disk via a temp file + atomic rename
     * with a per-file size cap to prevent OOM and partial-file corruption.
     */
    fun fetchAndCacheMediaBlocking(
        serverUrl: String,
        apiKey: String,
        rawUrl: String,
        maxFileBytes: Long = MAX_SINGLE_MEDIA_BYTES,
    ): CachedMedia? {
        val resolved = resolveUrl(serverUrl, rawUrl)
        val cacheKey = sha256(resolved)
        val cacheDir = mediaCacheDir?.apply { mkdirs() }
        val dataFile = cacheDir?.let { File(it, "$cacheKey.bin") }
        val metaFile = cacheDir?.let { File(it, "$cacheKey.mime") }

        if (dataFile != null && dataFile.exists() && dataFile.length() > 0L) {
            // Touch mtime for LRU tracking
            dataFile.setLastModified(System.currentTimeMillis())
            val mime = metaFile?.takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "image/png" }
            return CachedMedia(file = dataFile, mimeType = mime)
        }

        if (serverUrl.isBlank()) return null

        val reqBuilder = Request.Builder().url(resolved)
        if (isSameOrigin(serverUrl, resolved)) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }

        return runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body ?: return@use null
                val contentLength = body.contentLength()
                if (contentLength > maxFileBytes) return@use null

                val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    .orEmpty()
                    .ifBlank { "image/png" }

                if (cacheDir != null && dataFile != null && metaFile != null) {
                    val tmpFile = File(cacheDir, "$cacheKey.${System.nanoTime()}.tmp")
                    var totalRead = 0L
                    var exceeded = false
                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(tmpFile).use { output ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    totalRead += n
                                    if (totalRead > maxFileBytes) {
                                        exceeded = true
                                        break
                                    }
                                    output.write(buf, 0, n)
                                }
                            }
                        }
                        if (exceeded || totalRead == 0L) {
                            tmpFile.delete()
                            return@use null
                        }
                        if (!tmpFile.renameTo(dataFile)) {
                            tmpFile.copyTo(dataFile, overwrite = true)
                            tmpFile.delete()
                        }
                        metaFile.writeText(mime)
                        CachedMedia(file = dataFile, mimeType = mime)
                    } catch (t: Throwable) {
                        tmpFile.delete()
                        throw t
                    }
                } else {
                    val bytes = body.bytes()
                    if (bytes.size > maxFileBytes) return@use null
                    CachedMedia(bytes = bytes, mimeType = mime)
                }
            }
        }.getOrNull()
    }

    /**
     * Copies a locally uploaded attachment into the media cache under its new server URL
     * so it remains immediately viewable offline without re-downloading.
     */
    fun seedMediaCache(
        serverUrl: String,
        rawUrl: String,
        sourceFile: File,
        mimeType: String,
    ) {
        val cacheDir = mediaCacheDir?.apply { mkdirs() } ?: return
        if (!sourceFile.exists() || sourceFile.length() == 0L || sourceFile.length() > MAX_SINGLE_MEDIA_BYTES) return
        runCatching {
            val resolved = resolveUrl(serverUrl, rawUrl)
            val cacheKey = sha256(resolved)
            val dataFile = File(cacheDir, "$cacheKey.bin")
            val metaFile = File(cacheDir, "$cacheKey.mime")
            val tmpFile = File(cacheDir, "$cacheKey.${System.nanoTime()}.tmp")
            sourceFile.copyTo(tmpFile, overwrite = true)
            if (!tmpFile.renameTo(dataFile)) {
                tmpFile.copyTo(dataFile, overwrite = true)
                tmpFile.delete()
            }
            metaFile.writeText(mimeType.ifBlank { "image/jpeg" })
        }
    }

    /**
     * Enforces TTL + LRU total size budget on the disk media cache.
     */
    fun pruneMediaCache(
        maxTotalBytes: Long = MAX_TOTAL_CACHE_BYTES,
        maxAgeMillis: Long = DEFAULT_CACHE_TTL_MS,
    ) {
        val dir = mediaCacheDir ?: return
        if (!dir.exists()) return
        val now = System.currentTimeMillis()

        // Remove any leftover temp files first
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }

        val binFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.toMutableList() ?: return

        // 1. TTL eviction
        val iterator = binFiles.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (now - file.lastModified() > maxAgeMillis) {
                deleteCacheEntry(dir, file)
                iterator.remove()
            }
        }

        // 2. LRU total-size eviction (oldest lastModified first)
        var totalSize = binFiles.sumOf { it.length() }
        if (totalSize > maxTotalBytes) {
            binFiles.sortBy { it.lastModified() }
            for (file in binFiles) {
                if (totalSize <= maxTotalBytes) break
                totalSize -= file.length()
                deleteCacheEntry(dir, file)
            }
        }
    }

    private fun deleteCacheEntry(dir: File, binFile: File) {
        val base = binFile.name.removeSuffix(".bin")
        binFile.delete()
        File(dir, "$base.mime").delete()
    }

    fun getCachedMediaFile(serverUrl: String, rawUrl: String): File? {
        if (rawUrl.isBlank()) return null
        val resolved = resolveUrl(serverUrl, rawUrl)
        val cacheKey = sha256(resolved)
        val file = mediaCacheDir?.let { File(it, "$cacheKey.bin") }
        return file?.takeIf { it.exists() && it.length() > 0L }
    }

    fun resolveUrl(serverUrl: String, rawUrl: String): String {
        val base = serverUrl.trimEnd('/')
        return when {
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            rawUrl.startsWith("/") -> "$base$rawUrl"
            else -> "$base/$rawUrl"
        }
    }

    private fun isSameOrigin(baseUrl: String, targetUrl: String): Boolean {
        val a = baseUrl.toHttpUrlOrNull() ?: return false
        val b = targetUrl.toHttpUrlOrNull() ?: return false
        return a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DEFAULT_PAGE_LIMIT = 100
        const val MAX_SINGLE_MEDIA_BYTES = 20L * 1024L * 1024L // 20 MB per cached image
        const val MAX_TOTAL_CACHE_BYTES = 150L * 1024L * 1024L // 150 MB total LRU cap
        const val DEFAULT_CACHE_TTL_MS = 14L * 24L * 60L * 60L * 1000L // 14 days
    }
}

data class CachedMedia(
    val file: File? = null,
    val bytes: ByteArray? = null,
    val mimeType: String,
)
