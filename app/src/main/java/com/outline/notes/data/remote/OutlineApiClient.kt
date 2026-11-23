package com.outline.notes.data.remote

import kotlinx.coroutines.Dispatchers
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
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class OutlineApiException(
    val statusCode: Int,
    override val message: String,
) : IOException(message)

class OutlineApiClient(
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

    suspend fun authInfo(serverUrl: String, apiKey: String): AuthInfoData {
        val res = postRpc(serverUrl, apiKey, "auth.info", buildJsonObject {}, AuthInfoData.serializer())
        return res.data ?: throw OutlineApiException(500, "Empty auth.info response")
    }

    suspend fun listCollections(serverUrl: String, apiKey: String): List<CollectionDto> {
        val all = mutableListOf<CollectionDto>()
        var offset = 0
        val limit = 100
        while (true) {
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
            val batch = res.data.orEmpty()
            all.addAll(batch)
            if (batch.size < limit) break
            offset += limit
        }
        return all
    }

    suspend fun createCollection(
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

    suspend fun collectionDocumentsTree(
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

    suspend fun listAllDocuments(
        serverUrl: String,
        apiKey: String,
        collectionId: String? = null,
    ): List<DocumentDto> {
        val all = mutableListOf<DocumentDto>()
        var offset = 0
        val limit = 100
        while (true) {
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
            val batch = res.data.orEmpty()
            all.addAll(batch)
            if (batch.size < limit) break
            offset += limit
        }
        return all
    }

    suspend fun listDrafts(serverUrl: String, apiKey: String): List<DocumentDto> {
        val all = mutableListOf<DocumentDto>()
        var offset = 0
        val limit = 100
        while (true) {
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
            val batch = res.data.orEmpty()
            all.addAll(batch)
            if (batch.size < limit) break
            offset += limit
        }
        return all
    }

    suspend fun listArchivedDocuments(serverUrl: String, apiKey: String): List<DocumentDto> {
        val all = mutableListOf<DocumentDto>()
        var offset = 0
        val limit = 100
        while (true) {
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
            val batch = res.data.orEmpty()
            all.addAll(batch)
            if (batch.size < limit) break
            offset += limit
        }
        return all
    }

    suspend fun listDeletedDocuments(serverUrl: String, apiKey: String): List<DocumentDto> {
        val all = mutableListOf<DocumentDto>()
        var offset = 0
        val limit = 100
        while (true) {
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
            val batch = res.data.orEmpty()
            all.addAll(batch)
            if (batch.size < limit) break
            offset += limit
        }
        return all
    }

    suspend fun moveDocument(
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

    suspend fun archiveDocument(
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

    suspend fun restoreDocument(
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

    suspend fun getDocumentInfo(
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

    suspend fun createDocument(
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

    suspend fun updateDocument(
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

    suspend fun deleteDocument(
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

    suspend fun searchDocuments(
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
    suspend fun listStars(serverUrl: String, apiKey: String): StarsListData {
        val allStars = mutableListOf<StarDto>()
        val allDocs = mutableListOf<DocumentDto>()
        var offset = 0
        val limit = 100

        while (true) {
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
            val dataElem = res.data ?: break
            val batchStars: List<StarDto> = when (dataElem) {
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
            allStars.addAll(batchStars)
            if (batchStars.size < limit) break
            offset += limit
        }
        return StarsListData(stars = allStars, documents = allDocs)
    }

    suspend fun createStar(
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

    suspend fun deleteStar(
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
     * Returns the Outline attachment URL (e.g. /api/attachments.redirect?id=...).
     */
    suspend fun uploadAttachment(
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
        // S3 / MinIO requires all policy/signature form fields BEFORE the 'file' part
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

        // Only send Outline Bearer token if uploading back to Outline's own local endpoint,
        // NOT to S3/MinIO presigned POST (MinIO rejects conflicting Authorization headers).
        if (isSameOriginAsOutline && createData.form.none { it.key.equals("X-Amz-Signature", ignoreCase = true) || it.key.equals("Policy", ignoreCase = true) }) {
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
     * Fetches an Outline attachment/image with Bearer auth and caches it locally on disk
     * so images remain viewable offline inside the WYSIWYG WebView.
     */
    fun fetchAndCacheMediaBlocking(
        serverUrl: String,
        apiKey: String,
        rawUrl: String,
    ): CachedMedia? {
        val resolved = resolveUrl(serverUrl, rawUrl)
        val cacheKey = sha256(resolved)
        val cacheDir = mediaCacheDir?.apply { mkdirs() }
        val dataFile = cacheDir?.let { File(it, "$cacheKey.bin") }
        val metaFile = cacheDir?.let { File(it, "$cacheKey.mime") }

        if (dataFile != null && dataFile.exists() && dataFile.length() > 0L) {
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
                val bytes = resp.body?.bytes() ?: return@use null
                val mime = resp.header("Content-Type")?.substringBefore(';')?.trim()
                    .orEmpty()
                    .ifBlank { "image/png" }
                if (dataFile != null && metaFile != null) {
                    dataFile.writeBytes(bytes)
                    metaFile.writeText(mime)
                    CachedMedia(file = dataFile, mimeType = mime)
                } else {
                    CachedMedia(bytes = bytes, mimeType = mime)
                }
            }
        }.getOrNull()
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
}

data class CachedMedia(
    val file: File? = null,
    val bytes: ByteArray? = null,
    val mimeType: String,
)
