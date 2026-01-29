package `in`.chinmoydas.signal

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import com.google.gson.annotations.SerializedName
import retrofit2.Response

// --- DATA MODELS (Fully Restored) ---

data class WakeResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String?
)

data class LoginResponse(
    @SerializedName("status") val status: String,
    @SerializedName("token") val token: String,
    @SerializedName("username") val username: String,
    @SerializedName("error") val error: String?,
    @SerializedName("code") val code: String
)

data class PeerResponse(
    @SerializedName("status") val status: String,
    @SerializedName("ip") val ip: String?,
    @SerializedName("local_ip") val local_ip: String?,
    @SerializedName("port") val port: Int?,
    @SerializedName("fcm_token") val fcm_token: String?
)

data class ChannelUser(
    @SerializedName("username") val username: String,
    @SerializedName("public_ip") val public_ip: String?,
    @SerializedName("local_ip") val local_ip: String?
)

data class ChannelResponse(
    @SerializedName("status") val status: String,
    @SerializedName("users") val users: List<ChannelUser>?
)

data class ResetResponse(
    @SerializedName("status") val status: String,
    @SerializedName("new_code") val new_code: String?
)

data class GenericResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String?
)

data class IncomingSignal(
    @SerializedName("sender") val sender: String,
    @SerializedName("public_ip") val public_ip: String?,
    @SerializedName("public_port") val public_port: Int?
)

data class SignalResponse(
    @SerializedName("callers") val callers: List<String>?,
    @SerializedName("signals") val signals: List<IncomingSignal>?
)

// --- API INTERFACE ---

interface ApiService {

    @FormUrlEncoded
    @POST("api/auth.php")
    suspend fun login(
        @Field("username") user: String,
        @Field("password") pass: String,
        @Field("code") code: String
    ): LoginResponse

    @FormUrlEncoded
    @POST("api/find.php")
    suspend fun findPeer(
        @Header("Authorization") token: String,
        @Field("target_user") target: String,
        @Field("code") code: String
    ): PeerResponse

    @FormUrlEncoded
    @POST("api/channel.php")
    suspend fun findChannel(
        @Header("Authorization") token: String,
        @Field("channel_name") channel: String,
        @Field("channel_key") key: String
    ): ChannelResponse

    @FormUrlEncoded
    @POST("api/heartbeat.php")
    suspend fun sendHeartbeat(
        @Header("Authorization") token: String,
        @Field("port") port: Int,
        @Field("local_ip") localIp: String,
        @Field("channel") channel: String?,
        @Field("channel_key") key: String?,
        @Field("status") status: String = "online"
    ): Response<Unit>

    @POST("api/reset_code.php")
    suspend fun resetCode(
        @Header("Authorization") token: String
    ): ResetResponse

    @FormUrlEncoded
    @POST("api/signal.php")
    suspend fun sendSignal(
        @Header("Authorization") token: String,
        @Field("action") action: String,
        @Field("target") target: String?
    ): Response<Unit>

    @GET("api/signal.php?action=check_signals")
    suspend fun checkSignals(
        @Header("Authorization") token: String
    ): SignalResponse

    @FormUrlEncoded
    @POST("api/fcm_wake.php")
    suspend fun sendWakeSignal(
        @Header("Authorization") authHeader: String,
        @Field("target_token") token: String,
        @Field("sender_name") sender: String
    ): Response<WakeResponse>

    @FormUrlEncoded
    @POST("api/reset_auth.php")
    suspend fun requestOtp(
        @Field("action") action: String = "request_otp",
        @Field("username") username: String
    ): GenericResponse

    @FormUrlEncoded
    @POST("api/reset_auth.php")
    suspend fun resetPassword(
        @Field("action") action: String = "reset_pass",
        @Field("username") username: String,
        @Field("otp") otp: String,
        @Field("new_password") pass: String
    ): GenericResponse

    @FormUrlEncoded
    @POST("api/update_fcm.php")
    suspend fun updateFcmToken(
        @Header("Authorization") token: String,
        @Field("fcm_token") fcmToken: String
    ): Response<Unit>

    @FormUrlEncoded
    @POST("api/update_email.php")
    suspend fun updateRecoveryEmail(
        @Header("Authorization") authHeader: String,
        @Field("email") email: String
    ): Response<GenericResponse>
}

// --- RETROFIT CLIENT ---

object RetrofitClient {
    private const val BASE_URL = "https://signal.chinmoydas.in/"

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // 1. Standard Client (Login, Large Syncs) - 30s Timeout
    // Used for heavy operations where waiting is acceptable.
    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // 2. Fast Client (PTT Signaling, Peer Discovery) - 5s Timeout [MISSION CRITICAL]
    // Fails fast so the UI can switch to "Offline" mode instantly instead of hanging the user.
    private val fastHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // Don't retry blindly on real-time actions
        .build()

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // Use this for PTT/Signal calls to ensure responsiveness
    val fastApi: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(fastHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}