package `in`.chinmoydas.signal

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit
import com.google.gson.annotations.SerializedName
import retrofit2.Response

// --- UPGRADED DATA MODELS ---

data class LoginResponse(
    @SerializedName("status") val status: String,
    @SerializedName("token") val token: String,
    @SerializedName("username") val username: String,
    @SerializedName("error") val error: String?,
    @SerializedName("code") val code: String,
    @SerializedName("is_premium") val isPremium: Boolean?,
    @SerializedName("reflector_ip") val reflectorIp: String?,
    @SerializedName("reflector_port") val reflectorPort: Int?
)

// [NEW] Model for enterprise-grade CoTURN credentials
data class IceServer(
    @SerializedName("urls") val urls: List<String>,
    @SerializedName("username") val username: String?,
    @SerializedName("credential") val credential: String?
)

data class SignalCredsResponse(
    @SerializedName("status") val status: String,
    @SerializedName("iceServers") val iceServers: List<IceServer>?,
    @SerializedName("ttl") val ttl: Int?
)

data class WakeResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String?
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

// --- UPGRADED API INTERFACE ---

interface ApiService {

    @FormUrlEncoded
    @POST("auth.php")
    suspend fun login(
        @Field("username") user: String,
        @Field("password") pass: String,
        @Field("access_code") accessCode: String // For SaaS licensing
    ): LoginResponse

    @FormUrlEncoded
    @POST("find.php")
    suspend fun findPeer(
        @Header("Authorization") token: String,
        @Field("target_user") target: String,
        @Field("code") code: String
    ): PeerResponse

    // [NEW] Get CoTURN credentials for premium relaying
    @GET("get_signal_creds.php")
    suspend fun getSignalCreds(
        @Header("Authorization") token: String
    ): SignalCredsResponse

    @FormUrlEncoded
    @POST("channel.php")
    suspend fun findChannel(
        @Header("Authorization") token: String,
        @Field("channel_name") channel: String,
        @Field("channel_key") key: String
    ): ChannelResponse

    @FormUrlEncoded
    @POST("heartbeat.php")
    suspend fun sendHeartbeat(
        @Header("Authorization") token: String,
        @Field("port") port: Int,
        @Field("local_ip") localIp: String,
        @Field("channel") channel: String?,
        @Field("channel_key") key: String?,
        @Field("status") status: String = "online"
    ): GenericResponse // Use GenericResponse instead of Response<Unit>

    @POST("reset_code.php")
    suspend fun resetCode(
        @Header("Authorization") token: String
    ): ResetResponse

    @FormUrlEncoded
    @POST("signal.php")
    suspend fun sendSignal(
        @Header("Authorization") token: String,
        @Field("action") action: String,
        @Field("target") target: String?
    ): Response<Unit>

    @GET("signal.php?action=check_signals")
    suspend fun checkSignals(
        @Header("Authorization") token: String
    ): SignalResponse

    @FormUrlEncoded
    @POST("fcm_wake.php")
    suspend fun sendWakeSignal(
        @Header("Authorization") authHeader: String,
        @Field("target_token") token: String,
        @Field("sender_name") sender: String
    ): Response<WakeResponse>

    @FormUrlEncoded
    @POST("reset_auth.php")
    suspend fun requestOtp(
        @Field("action") action: String = "request_otp",
        @Field("username") username: String
    ): GenericResponse

    @FormUrlEncoded
    @POST("reset_auth.php")
    suspend fun resetPassword(
        @Field("action") action: String = "reset_pass",
        @Field("username") username: String,
        @Field("otp") otp: String,
        @Field("new_password") pass: String
    ): GenericResponse

    @FormUrlEncoded
    @POST("update_fcm.php")
    suspend fun updateFcmToken(
        @Header("Authorization") token: String,
        @Field("fcm_token") fcmToken: String
    ): Response<Unit>

    @FormUrlEncoded
    @POST("update_email.php")
    suspend fun updateRecoveryEmail(
        @Header("Authorization") authHeader: String,
        @Field("email") email: String
    ): Response<GenericResponse>
}

// --- UPGRADED RETROFIT CLIENT ---

object RetrofitClient {
    // Mothership Location
    private const val BASE_URL = "https://cdsignal.schoolspark.in/"

    // [MOTHERSHIP SECURITY] Shared Secret between Kotlin and PHP
    private const val API_KEY = "588fbb57d393cf97fd0759d1911dfe95ffbb969cfd33cfd3bc74b75b6040326f"

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    // Custom Interceptor to secure all Mothership requests
    private val securityInterceptor = okhttp3.Interceptor { chain ->
        val original = chain.request()
        val request = original.newBuilder()
            .header("X-API-KEY", API_KEY)
            .method(original.method, original.body)
            .build()
        chain.proceed(request)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(securityInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val fastHttpClient = OkHttpClient.Builder()
        .addInterceptor(logging)
        .addInterceptor(securityInterceptor)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    val fastApi: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(fastHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}