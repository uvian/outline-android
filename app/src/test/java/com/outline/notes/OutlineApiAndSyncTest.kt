package com.outline.notes

import com.outline.notes.data.backup.BackupExporter
import com.outline.notes.data.local.CollectionEntity
import com.outline.notes.data.local.DocumentEntity
import com.outline.notes.data.local.SyncState
import com.outline.notes.data.prefs.AppPreferences
import com.outline.notes.data.remote.DocumentDto
import com.outline.notes.data.remote.OutlineApiClient
import com.outline.notes.data.sync.SyncDiagnostics
import com.outline.notes.data.sync.SyncEngine
import com.outline.notes.ui.components.UiFormatters
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class OutlineApiAndSyncTest {

    @Test
    fun normalizeServerUrl_handlesVariants() {
        assertEquals("https://wiki.example.com", AppPreferences.normalizeServerUrl("wiki.example.com/"))
        assertEquals("https://wiki.example.com", AppPreferences.normalizeServerUrl("https://wiki.example.com/api"))
        assertEquals("http://192.168.1.10:3000", AppPreferences.normalizeServerUrl("http://192.168.1.10:3000/"))
    }

    @Test
    fun conflictDetection_detectsRemoteDivergenceSafely() {
        val local = DocumentEntity(
            id = "doc-1",
            title = "Local Title",
            text = "Local edit",
            syncState = SyncState.LOCAL_MODIFIED,
            serverBaseRevision = 3,
            serverBaseUpdatedAt = "2026-11-20T10:00:00.000Z",
        )
        val sameRemote = DocumentDto(
            id = "doc-1",
            title = "Old Title",
            text = "Old text",
            revision = 3,
            updatedAt = "2026-11-20T10:00:00.000Z",
        )
        val divergedRemote = DocumentDto(
            id = "doc-1",
            title = "PC Edited Title",
            text = "PC edited text",
            revision = 4,
            updatedAt = "2026-11-21T08:00:00.000Z",
        )

        assertFalse(SyncEngine.hasConflict(local, sameRemote))
        assertTrue(SyncEngine.hasConflict(local, divergedRemote))
    }

    @Test
    fun stripMarkdownSnippet_producesCleanPreview() {
        val md = "# Heading\n\n- [ ] Buy milk\n![img](/api/attachments.redirect?id=1)\n**Bold** text"
        val preview = UiFormatters.stripMarkdownSnippet(md)
        assertEquals("Heading ☐ Buy milk [图片] Bold text", preview)
    }

    @Test
    fun uploadAttachment_sendsPresignedFormWithoutOutlineAuthToMinio() = runTest {
        val outlineServer = MockWebServer()
        val minioServer = MockWebServer()
        outlineServer.start()
        minioServer.start()

        try {
            val minioUploadUrl = minioServer.url("/outline-bucket").toString()
            outlineServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "ok": true,
                          "status": 200,
                          "data": {
                            "maxUploadSize": 26214400,
                            "uploadUrl": "$minioUploadUrl",
                            "form": {
                              "key": "uploads/test/photo.png",
                              "Policy": "base64policy",
                              "X-Amz-Signature": "sig123"
                            },
                            "attachment": {
                              "id": "att-123",
                              "name": "photo.png",
                              "contentType": "image/png",
                              "size": 4,
                              "url": "/api/attachments.redirect?id=att-123"
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            minioServer.enqueue(MockResponse().setResponseCode(204))

            val tempFile = File.createTempFile("outline_test_", ".png").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
                deleteOnExit()
            }

            val client = OutlineApiClient()
            val result = client.uploadAttachment(
                serverUrl = outlineServer.url("/").toString().trimEnd('/'),
                apiKey = "ol_api_test_secret",
                file = tempFile,
                fileName = "photo.png",
                contentType = "image/png",
                documentId = "doc-uuid-1",
            )

            assertEquals("/api/attachments.redirect?id=att-123", result.url)

            val outlineReq = outlineServer.takeRequest()
            assertEquals("Bearer ol_api_test_secret", outlineReq.getHeader("Authorization"))
            assertEquals("/api/attachments.create", outlineReq.path)

            val minioReq = minioServer.takeRequest()
            // Critical for MinIO/S3: must NOT leak Outline Bearer token onto presigned POST
            assertNull(minioReq.getHeader("Authorization"))
            val bodyText = minioReq.body.readUtf8()
            val policyPos = bodyText.indexOf("name=\"Policy\"")
            val filePos = bodyText.indexOf("name=\"file\"")
            assertTrue("Form fields must precede file part", policyPos in 0 until filePos)
        } finally {
            outlineServer.shutdown()
            minioServer.shutdown()
        }
    }

    @Test
    fun listStars_parsesLatestOutlineObjectResponseWithDocuments() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "ok": true,
                          "status": 200,
                          "data": {
                            "stars": [
                              { "id": "star-1", "documentId": "doc-star-1", "index": "a0" }
                            ],
                            "documents": [
                              {
                                "id": "doc-star-1",
                                "title": "Starred Note",
                                "text": "Hello star",
                                "collectionId": "col-1",
                                "publishedAt": "2026-11-20T00:00:00.000Z",
                                "updatedAt": "2026-11-20T00:00:00.000Z"
                              }
                            ]
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            val client = OutlineApiClient()
            val result = client.listStars(server.url("/").toString().trimEnd('/'), "ol_api_test")
            assertEquals(1, result.stars.size)
            assertEquals("doc-star-1", result.stars.first().documentId)
            assertEquals(1, result.documents.size)
            assertEquals("Starred Note", result.documents.first().title)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun authInfo_parsesServerLogoAndCustomAccentColor() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "ok": true,
                          "status": 200,
                          "data": {
                            "user": { "id": "u1", "name": "Alice", "email": "alice@example.com" },
                            "team": {
                              "id": "t1",
                              "name": "My Wiki",
                              "avatarUrl": "https://cdn.example.com/logo.png",
                              "preferences": {
                                "customTheme": {
                                  "accent": "#9E5CF7",
                                  "accentText": "#FFFFFF"
                                }
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            val client = OutlineApiClient()
            val auth = client.authInfo(server.url("/").toString().trimEnd('/'), "ol_api_test")
            assertEquals("https://cdn.example.com/logo.png", auth.team?.avatarUrl)
            assertEquals("#9E5CF7", auth.team?.preferences?.customTheme?.accent)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun extractOutlineDocIdentifier_handlesInternalAndIgnoresExternalLinks() {
        val server = "https://wiki.myserver.com"
        assertEquals(
            "project-plan-AbCd1234",
            SyncEngine.extractOutlineDocIdentifier("/doc/project-plan-AbCd1234", server),
        )
        assertEquals(
            "project-plan-AbCd1234",
            SyncEngine.extractOutlineDocIdentifier("https://wiki.myserver.com/doc/project-plan-AbCd1234#section", server),
        )
        assertEquals(
            "daily-log-XYZ99",
            SyncEngine.extractOutlineDocIdentifier("https://appassets.androidplatform.net/doc/daily-log-XYZ99?ref=1", server),
        )
        assertNull(SyncEngine.extractOutlineDocIdentifier("https://google.com/doc/fake", server))
    }

    @Test
    fun backupExporter_groupsByCollectionDeduplicatesAndSkipsTrash() {
        val cols = listOf(CollectionEntity(id = "c1", name = "Work/Notes"))
        val docs = listOf(
            DocumentEntity(id = "d1", collectionId = "c1", title = "Plan", text = "Hello"),
            DocumentEntity(id = "d2", collectionId = "c1", title = "Plan", text = "Duplicate"),
            DocumentEntity(id = "d3", collectionId = "c1", title = "Archived", text = "Old", isArchived = true),
            DocumentEntity(id = "d4", collectionId = "c1", title = "Trashed", text = "Skip", isDeleted = true),
        )

        val baos = ByteArrayOutputStream()
        val count = BackupExporter.writeMarkdownZip(cols, docs, baos)
        assertEquals(3, count)

        val entryNames = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(baos.toByteArray())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                entry = zis.nextEntry
            }
        }
        assertTrue(entryNames.contains("Work_Notes/Plan.md"))
        assertTrue(entryNames.contains("Work_Notes/Plan_2.md"))
        assertTrue(entryNames.contains("_Archived/Archived.md"))
        assertFalse(entryNames.any { it.contains("Trashed") })
    }

    @Test
    fun syncDiagnostics_logsAndDumps() {
        SyncDiagnostics.clear()
        SyncDiagnostics.log("TEST", "hello world")
        val text = SyncDiagnostics.dumpText()
        assertTrue(text.contains("[TEST] hello world"))
    }
}
