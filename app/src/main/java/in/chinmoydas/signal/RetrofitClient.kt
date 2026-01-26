package `in`.chinmoydas.signal

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// --- RESPONSE MODELS ---
data class WakeResponse(
    val status: String,  // "success" or "error"
    val message: String?,
    val error: String?
)
data class LoginResponse(
    val status: String,
    val token: String,
    val username: String,
    val error: String?,
    val code: String
)

data class PeerResponse(
    val status: String,
    val ip: String?,
    val local_ip: String?,
    val port: Int?
)

data class ChannelUser(
    val username: String,
    val public_ip: String?,
    val local_ip: String?
)

data class ChannelResponse(
    val status: String,
    val users: List<ChannelUser>?
)

data class ResetResponse(
    val status: String,
    val new_code: String?
)

// [CRITICAL UPDATE START]
// This new model holds the IP address sent by the server.
// Without this, the app receives the signal but doesn't know where to connect.
data class IncomingSignal(
    val sender: String,
    val public_ip: String?,
    val public_port: Int? // <--- NEW FIELD
)

data class SignalResponse(
    val callers: List<String>?,        // Legacy support (Old Server)
    val signals: List<IncomingSignal>? // New robust support (Smart Server)
)
// [CRITICAL UPDATE END]

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
    ): retrofit2.Response<Unit>

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
    ): retrofit2.Response<Unit>

    @GET("api/signal.php?action=check_signals")
    suspend fun checkSignals(
        @Header("Authorization") token: String
    ): SignalResponse

    // [NEW] The Cloud Wake Method
    @FormUrlEncoded
    @POST("api/fcm_wake.php")
    suspend fun sendWakeSignal(
        @Field("target_token") token: String,
        @Field("sender_name") sender: String
    ): retrofit2.Response<WakeResponse>
}

object RetrofitClient {
    private const val BASE_URL = "https://signal.chinmoydas.in/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}