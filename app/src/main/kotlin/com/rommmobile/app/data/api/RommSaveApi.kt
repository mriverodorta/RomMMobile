package com.rommmobile.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface RommSaveApi {
    @POST("api/devices")
    suspend fun register(@Body device: SaveSyncDevice): SaveSyncRegistration

    @POST("api/sync/negotiate")
    suspend fun negotiate(@Body request: SaveSyncManifest): SaveSyncSession

    @Multipart
    @POST("api/saves")
    suspend fun upload(
        @Query("rom_id") romId: Int,
        @Query("device_id") deviceId: String,
        @Query("session_id") sessionId: Int,
        @Query("slot") slot: String,
        @Query("emulator") emulator: String,
        @Query("content_hash") hash: String,
        @Part save: MultipartBody.Part,
        @Query("overwrite") overwrite: Boolean = false,
    ): JsonElement

    @GET("api/saves")
    suspend fun history(@Query("rom_id") romId: Int): List<SaveSyncDetails>

    @Streaming
    @GET("api/saves/{id}/content")
    suspend fun download(
        @Path("id") saveId: Int,
        @Query("device_id") deviceId: String,
        @Query("session_id") sessionId: Int?,
        @Query("optimistic") optimistic: Boolean = false,
    ): ResponseBody

    @POST("api/saves/{id}/downloaded")
    suspend fun confirmDownload(@Path("id") saveId: Int, @Body confirmation: SaveSyncConfirmation): JsonElement

    @POST("api/sync/sessions/{id}/complete")
    suspend fun complete(@Path("id") sessionId: Int, @Body result: SaveSyncCompletion): JsonElement
}

@Serializable
data class SaveSyncDevice(
    val name: String,
    val hostname: String,
    val platform: String,
    val client: String,
    @SerialName("client_version") val clientVersion: String,
    @SerialName("sync_mode") val syncMode: String,
)

@Serializable
data class SaveSyncRegistration(@SerialName("device_id") val deviceId: String)

@Serializable
data class SaveSyncManifest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("rom_ids") val romIds: List<Int>,
    val saves: List<SaveSyncFile>,
    val emulators: List<String>,
    @SerialName("restore_unlisted") val restoreUnlisted: Boolean,
)

@Serializable
data class SaveSyncFile(
    @SerialName("rom_id") val romId: Int,
    @SerialName("file_name") val fileName: String,
    val slot: String,
    val emulator: String,
    @SerialName("content_hash") val hash: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("file_size_bytes") val size: Long,
)

@Serializable
data class SaveSyncSession(
    @SerialName("session_id") val sessionId: Int,
    val operations: List<SaveSyncOperation>,
)

@Serializable
data class SaveSyncOperation(
    val action: String,
    @SerialName("rom_id") val romId: Int,
    @SerialName("save_id") val saveId: Int? = null,
    val slot: String? = null,
    val emulator: String? = null,
    @SerialName("server_content_hash") val hash: String? = null,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
)

@Serializable
data class SaveSyncConfirmation(
    @SerialName("device_id") val deviceId: String,
    @SerialName("content_hash") val hash: String,
)

@Serializable
data class SaveSyncCompletion(
    @SerialName("operations_completed") val completed: Int,
    @SerialName("operations_failed") val failed: Int,
)


@Serializable
data class SaveSyncDetails(
    val id: Int,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("content_hash") val hash: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val slot: String? = null,
    val emulator: String? = null,
)
