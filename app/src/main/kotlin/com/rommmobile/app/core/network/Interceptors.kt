package com.rommmobile.app.core.network

import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Placeholder base URL for Retrofit; the real host is swapped in per request. */
const val RETROFIT_PLACEHOLDER = "http://romm.invalid/"

/**
 * Rewrites the placeholder host to the active server, keeping an optional path prefix
 * (RomM mounted under /romm behind a reverse proxy).
 */
@Singleton
class HostSelectionInterceptor @Inject constructor(private val serverStore: ServerStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != "romm.invalid") return chain.proceed(request)
        val base = serverStore.config.value.activeHttpUrl
            ?: throw ApiException(ApiErrorKind.NOT_CONFIGURED, endpoint = request.url.encodedPath)
        val prefix = base.encodedPath.trimEnd('/')
        val newUrl = request.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .encodedPath(prefix + request.url.encodedPath)
            .build()
        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}

/**
 * Plain HTTP is fine on the LAN (private ranges, .local) and refused towards the internet
 * unless the user explicitly allowed it in settings. Enforced in code because
 * network_security_config cannot express IP ranges.
 */
@Singleton
class CleartextPolicyInterceptor @Inject constructor(
    private val settings: Provider<SettingsStore>,
    @ApplicationScope scope: CoroutineScope,
) : Interceptor {
    @Volatile var allowInsecureRemote: Boolean = false

    init {
        // Follow the setting from the graph, not from a screen: background downloads resumed by
        // WorkManager run with no ViewModel alive and would otherwise always see `false`.
        scope.launch {
            settings.get().settings.map { it.allowInsecureRemote }.distinctUntilChanged()
                .collect { allowInsecureRemote = it }
        }
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        for (hop in 0..10) {
            checkCleartext(request.url)
            val response = chain.proceed(request)
            val next = redirectRequest(response) ?: return response
            response.close()
            if (hop == 10) throw java.net.ProtocolException("Too many redirects")
            request = next
        }
        error("Unreachable redirect limit")
    }

    private fun checkCleartext(url: HttpUrl) {
        if (!url.isHttps && !UrlNormalizer.isPrivateHost(url.host) && !allowInsecureRemote) {
            throw ApiException(ApiErrorKind.CLEARTEXT_BLOCKED, endpoint = url.host)
        }
    }
}

internal fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

internal fun redirectRequest(response: Response): Request? {
    if (response.code !in setOf(300, 301, 302, 303, 307, 308)) return null
    val request = response.request
    val target = response.header("Location")?.let(request.url::resolve) ?: return null
    if (request.url.isHttps && !target.isHttps) return null
    val sameOrigin = request.url.sameOrigin(target)
    val safeMethod = request.method == "GET" || request.method == "HEAD"
    if (!sameOrigin && !safeMethod) return null
    val next = request.newBuilder().url(target)
    if (!sameOrigin) {
        for (header in listOf("Authorization", "Proxy-Authorization", "Cookie", "CF-Access-Client-Id", "CF-Access-Client-Secret")) {
            next.removeHeader(header)
        }
        next.tag(AuthRequest::class.java, null)
    }
    if (!safeMethod) {
        if (response.code == 307 || response.code == 308) {
            if (request.body?.isOneShot() == true || request.body?.isDuplex() == true) return null
        } else {
            next.get().removeHeader("Content-Type").removeHeader("Content-Length").removeHeader("Transfer-Encoding")
        }
    }
    return next.build()
}

/**
 * Adds `Authorization` (and Cloudflare Access headers) only to requests aimed at one of the
 * user's own server hosts. Cover art from external CDNs never receives credentials.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val authStore: AuthStore,
    private val serverStore: ServerStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val cfg = serverStore.config.value
        if (!cfg.isKnownOrigin(request.url)) return chain.proceed(request)
        val builder = request.newBuilder()
        if (request.header("Authorization") == null) {
            val credentials = authStore.requestCredentials()
            credentials.state.authorizationHeader()?.let {
                builder.header("Authorization", it).tag(AuthRequest::class.java, credentials)
            }
        }
        applyCloudflareHeaders(builder, cfg)
        builder.header("Accept", request.header("Accept") ?: "application/json, */*;q=0.8")
        return chain.proceed(builder.build())
    }
}

/**
 * Cloudflare Access service-token headers. Values are typed by hand, and OkHttp throws on any
 * character outside 0x20..0x7E — an exception from an interceptor reaches the dispatcher thread
 * and kills the process, so anything unusable is dropped instead of sent.
 */
fun applyCloudflareHeaders(builder: Request.Builder, cfg: ServerConfig) {
    val id = cfg.cfClientId?.trim()
    val secret = cfg.cfClientSecret?.trim()
    if (id.isNullOrEmpty() || secret.isNullOrEmpty()) return
    if (!isHeaderSafe(id) || !isHeaderSafe(secret)) return
    builder.header("CF-Access-Client-Id", id).header("CF-Access-Client-Secret", secret)
}

private fun isHeaderSafe(value: String): Boolean = value.all { it.code in 0x20..0x7E }

/**
 * Turns HTTP failures into [ApiException] with the server's `detail`, and catches the classic
 * reverse-proxy trap: a 200 HTML login page where JSON was expected.
 */
@Singleton
class ErrorMappingInterceptor @Inject constructor(private val json: Json) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val path = request.url.encodedPath
        val isContent = path.contains("/content/") || path.endsWith("/content")
        if (response.isSuccessful) {
            if (!isContent && path.contains("/api/")) {
                val type = response.header("Content-Type").orEmpty()
                if (type.isNotEmpty() && !type.contains("json", ignoreCase = true) && response.code != 204) {
                    response.close()
                    throw ApiException(ApiErrorKind.NON_JSON, httpCode = response.code, detail = type, endpoint = path)
                }
            }
            return response
        }
        val body = runCatching { response.peekBody(64 * 1024).string() }.getOrNull()
        response.close()
        val detail = body?.let(::extractDetail)
        val kind = when (response.code) {
            401 -> ApiErrorKind.UNAUTHORIZED
            403 -> ApiErrorKind.FORBIDDEN
            404 -> ApiErrorKind.NOT_FOUND
            else -> ApiErrorKind.SERVER
        }
        throw ApiException(kind, httpCode = response.code, detail = detail, endpoint = path)
    }

    private fun extractDetail(body: String): String? = runCatching {
        val el = json.parseToJsonElement(body)
        val detail = (el as? JsonObject)?.get("detail") ?: return@runCatching body.take(200)
        when (detail) {
            is JsonPrimitive -> detail.content
            is JsonArray -> detail.joinToString("; ") { item ->
                (item as? JsonObject)?.let { o ->
                    val loc = (o["loc"] as? JsonArray)?.joinToString(".") { it.jsonPrimitive.content }
                    val msg = o["msg"]?.jsonPrimitive?.content
                    listOfNotNull(loc, msg).joinToString(": ")
                } ?: item.toString()
            }
            else -> detail.toString()
        }
    }.getOrElse { body.take(200).ifBlank { null } }
}

/**
 * OAuth refresh on 401. Client tokens and Basic credentials cannot be refreshed: a 401 there
 * means the credentials are gone and the session is cleared (only on 401, never on other 4xx).
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val authStore: AuthStore,
    private val serverStore: ServerStore,
    private val json: Json,
    cleartext: CleartextPolicyInterceptor,
) : Authenticator {
    private val refreshClient = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(cleartext)
        .build()
    private val refreshing = AtomicBoolean(false)

    override fun authenticate(route: Route?, response: Response): Request? {
        val cfg = serverStore.config.value
        val request = response.request
        val original = request.tag(AuthRequest::class.java) ?: return null
        val base = listOfNotNull(cfg.baseUrl, cfg.remoteUrl).mapNotNull { it.toHttpUrlOrNull() }
            .firstOrNull { it.sameOrigin(request.url) } ?: return null
        val credentials = authStore.requestCredentials()
        if (credentials.session != original.session) return null
        if (credentials.state.authorizationHeader() != request.header("Authorization")) {
            return request.withCredentials(credentials)
        }
        if (responseCount(response) >= 2) return giveUp(credentials)
        if (credentials.state.mode != AuthMode.OAUTH) return giveUp(credentials)
        val refreshToken = credentials.state.refreshToken ?: return giveUp(credentials)

        if (!refreshing.compareAndSet(false, true)) {
            repeat(20) {
                Thread.sleep(150)
                val current = authStore.requestCredentials()
                if (current.session != credentials.session) return null
                if (current.state.accessToken != credentials.state.accessToken) return request.withCredentials(current)
            }
            return null
        }

        try {
            val current = authStore.requestCredentials()
            if (current.session != credentials.session) return null
            if (current.state.accessToken != credentials.state.accessToken) return request.withCredentials(current)

            val tokenUrl = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/api/token").build()
            val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).build()
            val refreshRequest = Request.Builder().url(tokenUrl).post(form).apply { applyCloudflareHeaders(this, cfg) }.build()
            refreshClient.newCall(refreshRequest).execute().use { result ->
                if (result.code in listOf(400, 401, 403)) return giveUp(credentials)
                if (!result.isSuccessful) return null
                val obj = json.parseToJsonElement(result.body.string()).jsonObject
                val access = obj["access_token"]?.jsonPrimitive?.content ?: return null
                val newRefresh = obj["refresh_token"]?.jsonPrimitive?.content ?: refreshToken
                val expires = obj["expires"]?.jsonPrimitive?.content?.toLongOrNull()
                val updated = authStore.refresh(credentials, access, newRefresh, expires) ?: return null
                return request.withCredentials(updated)
            }
        } catch (_: Exception) {
            return null
        } finally {
            refreshing.set(false)
        }
    }

    private fun Request.withCredentials(credentials: AuthRequest): Request? =
        credentials.state.authorizationHeader()?.let {
            newBuilder().header("Authorization", it).tag(AuthRequest::class.java, credentials).build()
        }

    private fun giveUp(credentials: AuthRequest): Request? {
        authStore.expire(credentials)
        return null
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var n = 0
        while (r != null) { n++; r = r.priorResponse }
        return n
    }
}
