package com.vansaska.wattbike.app

import android.net.Uri
import com.vansaska.wattbike.core.RideRecord
import com.vansaska.wattbike.core.TcxWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class StravaClient(private val prefs: Prefs, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = prefs.stravaClientId.isNotBlank() && prefs.stravaClientSecret.isNotBlank()
    val isConnected: Boolean get() = prefs.stravaRefreshToken != null

    fun authorizeUri(): Uri = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
        .appendQueryParameter("client_id", prefs.stravaClientId)
        .appendQueryParameter("redirect_uri", REDIRECT_URI)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("approval_prompt", "auto")
        .appendQueryParameter("scope", "activity:write,read")
        .build()

    /** Called with the code Strava passes back after you approve access. */
    suspend fun exchangeCode(code: String) = withContext(Dispatchers.IO) {
        val obj = tokenRequest(
            FormBody.Builder()
                .add("client_id", prefs.stravaClientId)
                .add("client_secret", prefs.stravaClientSecret)
                .add("code", code)
                .add("grant_type", "authorization_code")
                .build(),
        )
        storeTokens(obj)
        val athlete = obj["athlete"]?.jsonObject
        val name = listOfNotNull(
            athlete?.get("firstname")?.jsonPrimitive?.contentOrNull,
            athlete?.get("lastname")?.jsonPrimitive?.contentOrNull,
        ).joinToString(" ")
        prefs.stravaAthlete = name.ifBlank { "connected" }
    }

    fun disconnect() {
        prefs.stravaAccessToken = null
        prefs.stravaRefreshToken = null
        prefs.stravaExpiresAt = 0
        prefs.stravaAthlete = null
    }

    private fun tokenRequest(body: FormBody): JsonObject {
        val req = Request.Builder().url("https://www.strava.com/oauth/token").post(body).build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Strava sign-in failed (HTTP ${resp.code}): ${text.take(200)}")
            json.parseToJsonElement(text).jsonObject
        }
    }

    private fun storeTokens(obj: JsonObject) {
        prefs.stravaAccessToken = obj["access_token"]!!.jsonPrimitive.content
        prefs.stravaRefreshToken = obj["refresh_token"]!!.jsonPrimitive.content
        prefs.stravaExpiresAt = obj["expires_at"]!!.jsonPrimitive.long
    }

    private fun accessToken(): String {
        val nowS = System.currentTimeMillis() / 1000
        val current = prefs.stravaAccessToken
        if (current != null && prefs.stravaExpiresAt - 300 > nowS) return current
        val refresh = prefs.stravaRefreshToken ?: error("Not connected to Strava")
        val obj = tokenRequest(
            FormBody.Builder()
                .add("client_id", prefs.stravaClientId)
                .add("client_secret", prefs.stravaClientSecret)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build(),
        )
        storeTokens(obj)
        return prefs.stravaAccessToken!!
    }

    /** Uploads a ride and waits for Strava to process it. Returns the Strava activity id. */
    suspend fun upload(ride: RideRecord): Long = withContext(Dispatchers.IO) {
        val token = accessToken()
        val tcx = TcxWriter.write(ride)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "${ride.id}.tcx", tcx.toRequestBody("application/xml".toMediaType()))
            .addFormDataPart("data_type", "tcx")
            .addFormDataPart("name", ride.displayTitle)
            .addFormDataPart("description", ride.stravaDescription())
            .addFormDataPart("trainer", "1")
            .addFormDataPart("external_id", "wattbike-app-${ride.id}")
            .build()
        val req = Request.Builder().url("https://www.strava.com/api/v3/uploads")
            .header("Authorization", "Bearer $token").post(body).build()
        var upload = http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Upload failed (HTTP ${resp.code}): ${text.take(200)}")
            json.parseToJsonElement(text).jsonObject
        }
        val uploadId = upload["id"]!!.jsonPrimitive.long
        repeat(20) {
            upload["error"]?.jsonPrimitive?.contentOrNull?.let { error("Strava: $it") }
            upload["activity_id"]?.jsonPrimitive?.longOrNull?.let { return@withContext it }
            delay(2000)
            val poll = Request.Builder().url("https://www.strava.com/api/v3/uploads/$uploadId")
                .header("Authorization", "Bearer $token").build()
            upload = http.newCall(poll).execute().use { resp ->
                json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
            }
        }
        error("Strava is still processing the upload; check Strava in a minute")
    }

    companion object {
        const val REDIRECT_URI = "wattbikeapp://localhost/strava"
    }
}
