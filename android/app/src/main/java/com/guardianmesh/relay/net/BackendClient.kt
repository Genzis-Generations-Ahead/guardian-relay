package com.guardianmesh.relay.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny blocking JSON client on HttpURLConnection — no OkHttp/Retrofit dependency,
 * so the SOS core has zero third-party libraries. Call from a background thread
 * (SosDispatcher / MeshRelay / SafeExit already do).
 */
object BackendClient {

    data class Response(val code: Int, val body: String?, val error: String?) {
        val ok get() = error == null && code in 200..299
        fun describe() = when {
            error != null -> error
            ok -> "HTTP $code"
            else -> "HTTP $code ${body?.take(120) ?: ""}"
        }
        fun json(): JSONObject? = body?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    var connectTimeoutMs = 4_000
    var readTimeoutMs = 6_000

    fun postJson(url: String, json: JSONObject): Response = request("POST", url, json.toString())

    fun get(url: String): Response = request("GET", url, null)

    private fun request(method: String, url: String, body: String?): Response {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            Response(code, text, null)
        } catch (e: IOException) {
            val hint = if (e.message?.contains("CLEARTEXT", true) == true)
                " (http:// blocked — check usesCleartextTraffic / network_security_config)" else ""
            Response(-1, null, "${e.javaClass.simpleName}: ${e.message}$hint")
        } catch (t: Throwable) {
            Response(-1, null, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * True if the active network claims internet capability. We deliberately do NOT
     * require VALIDATED: a demo backend on local Wi-Fi without upstream internet is
     * still reachable. The actual POST is the real test.
     */
    fun hasNetwork(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun networkLabel(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return "none") ?: return "none"
        val t = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "other"
        }
        val v = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "no-internet-validation"
        return "$t ($v)"
    }
}
