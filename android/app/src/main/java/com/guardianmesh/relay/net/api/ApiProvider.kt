package com.guardianmesh.relay.net.api

import com.guardianmesh.relay.BuildConfig
import com.guardianmesh.relay.Guardian
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * One Retrofit instance for the whole app, built against [ApiConfig.BASE_URL].
 *
 * [HostOverrideInterceptor] swaps in the host from Settings when the user has set one,
 * so the LAN IP can change at a demo without rebuilding and without creating a second
 * Retrofit. Retrofit's own baseUrl stays the constant.
 */
object ApiProvider {

    val api: GuardianApi by lazy { retrofit.create(GuardianApi::class.java) }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(ApiConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(ApiConfig.READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(HostOverrideInterceptor)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()
    }

    /** What the next request will actually hit — shown in Settings so there's no guessing. */
    fun effectiveBaseUrl(): String {
        val saved = runCatching { Guardian.config.backendBaseUrl }.getOrNull()
        return if (saved.isNullOrBlank()) ApiConfig.BASE_URL else ApiConfig.normalize(saved)
    }

    private object HostOverrideInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val override = runCatching { Guardian.config.backendBaseUrl }.getOrNull()
            if (override.isNullOrBlank()) return chain.proceed(request)

            val target: HttpUrl = ApiConfig.normalize(override).toHttpUrlOrNull()
                ?: return chain.proceed(request)

            // Keep the path and query Retrofit built; only move it to the configured host.
            val rebuilt = request.url.newBuilder()
                .scheme(target.scheme)
                .host(target.host)
                .port(target.port)
                .build()
            return chain.proceed(request.newBuilder().url(rebuilt).build())
        }
    }
}
