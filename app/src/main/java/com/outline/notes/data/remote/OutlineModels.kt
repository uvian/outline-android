package com.outline.notes.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ApiResponse<T>(
    val ok: Boolean = true,
    val status: Int = 200,
    val data: T? = null,
    val pagination: PaginationDto? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class PaginationDto(
    val offset: Int = 0,
    val limit: Int = 25,
    val nextPath: String? = null,
)

@Serializable
data class AuthInfoData(
    val user: UserDto? = null,
    val team: TeamDto? = null,
)

@Serializable
data class UserDto(
    val id: String = "",
    val name: String = "",
    val email: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class TeamDto(
    val id: String = "",
    val name: String = "",
    val url: String? = null,
    val avatarUrl: String? = null,
    val defaultCollectionId: String? = null,
    val preferences: TeamPreferencesDto? = null,
)

@Serializable
data class TeamPreferencesDto(
    val customTheme: CustomThemeDto? = null,
)

@Serializable
data class CustomThemeDto(
    val accent: String? = null,
    val accentText: String? = null,
)

@Serializable
data class CollectionDto(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val color: String? = null,
    val icon: String? = null,
    val index: String? = null,
    val sort: CollectionSortDto? = null,
    val updatedAt: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class CollectionSortDto(
    val field: String = "title",
    val direction: String = "asc",
)

@Serializable
data class NavigationNodeDto(
    val id: String,
    val title: String = "",
    val url: String? = null,
    val emoji: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val children: List<NavigationNodeDto> = emptyList(),
)

@Serializable
data class DocumentDto(
    val id: String,
    val urlId: String? = null,
    val title: String = "",
    val text: String = "",
    val emoji: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val collectionId: String? = null,
    val parentDocumentId: String? = null,
    val revision: Int = 0,
    val publishedAt: String? = null,
    val archivedAt: String? = null,
    val deletedAt: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class SearchResultItemDto(
    val ranking: Double = 0.0,
    val context: String = "",
    val document: DocumentDto,
)

@Serializable
data class StarDto(
    val id: String,
    val documentId: String? = null,
    val collectionId: String? = null,
    val index: String? = null,
)

@Serializable
data class StarsListData(
    val stars: List<StarDto> = emptyList(),
    val documents: List<DocumentDto> = emptyList(),
)

@Serializable
data class AttachmentCreateData(
    val maxUploadSize: Long? = null,
    val uploadUrl: String = "",
    val form: Map<String, JsonElement> = emptyMap(),
    val attachment: AttachmentDto? = null,
)

@Serializable
data class AttachmentDto(
    val id: String = "",
    val documentId: String? = null,
    val contentType: String? = null,
    val name: String = "",
    val size: Long = 0L,
    val url: String = "",
)
