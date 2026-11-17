package com.kirora.androidsdk.net

import com.google.gson.JsonElement
import com.kirora.androidsdk.net.models.AppOpenRequest
import com.kirora.androidsdk.net.models.BatchEventsRequest
import com.kirora.androidsdk.net.models.DataEnvelope
import com.kirora.androidsdk.net.models.DeviceAnswer
import com.kirora.androidsdk.net.models.DeviceRegistrationRequest
import com.kirora.androidsdk.net.models.PushApp
import com.kirora.androidsdk.net.models.PushConsentRequest
import com.kirora.androidsdk.net.models.PushTrackingRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * The client API (`/client/v1`). `authorization` overrides the SDK's current user token, so work
 * for one user (a sign-out's flush and detachment) always goes with that user's own token.
 */
internal interface KiroraApiService {

    @GET("client/v1/push-app")
    suspend fun getPushApp(
        @Query("platform") platform: String = "android",
        @Query("pushAppId") pushAppId: String? = null,
        @Header("Authorization") authorization: String? = null
    ): Response<DataEnvelope<PushApp>>

    @PUT("client/v1/devices/{externalInstallationId}")
    suspend fun registerDevice(
        @Path("externalInstallationId") externalInstallationId: String,
        @Body request: DeviceRegistrationRequest,
        @Header("Authorization") authorization: String? = null
    ): Response<DataEnvelope<DeviceAnswer>>

    /**
     * Sign-out. With [lastSeenAt] (that of the device's last successful registration), only the
     * outgoing user's registrations made at or before it are detached: 409 says they registered
     * the device again since, and that registration is kept. Without it, the detach is unconditional.
     */
    @DELETE("client/v1/devices/{externalInstallationId}")
    suspend fun deleteDevice(
        @Path("externalInstallationId") externalInstallationId: String,
        @Query("lastSeenAt") lastSeenAt: String? = null,
        @Header("Authorization") authorization: String? = null
    ): Response<Unit>

    @PUT("client/v1/push-consent")
    suspend fun updatePushConsent(
        @Body request: PushConsentRequest,
        @Header("Authorization") authorization: String? = null
    ): Response<Unit>

    @POST("client/v1/events/batch")
    suspend fun sendEventBatch(
        @Body request: BatchEventsRequest,
        @Header("Authorization") authorization: String? = null
    ): Response<JsonElement>

    /** An app open, for influenced opens. Not an event: it never counts against the plan's events. */
    @POST("client/v1/devices/{externalInstallationId}/app-opens")
    suspend fun postAppOpen(
        @Path("externalInstallationId") externalInstallationId: String,
        @Body request: AppOpenRequest,
        @Header("Authorization") authorization: String? = null
    ): Response<Unit>

    /** A push's `trackingUrl` (`delivered`, `opened` or `clicked`); needs no credentials, so the SDK sends none. */
    @Headers("Kirora-No-Credentials: true")
    @POST
    suspend fun reportPush(@Url url: String, @Body body: PushTrackingRequest): Response<Unit>
}
