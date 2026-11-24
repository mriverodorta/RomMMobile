package com.rommmobile.app

import com.rommmobile.app.core.network.redirectRequest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectPolicyTest {
    private val request = Request.Builder()
        .url("https://romm.example/cover")
        .header("Authorization", "Bearer test")
        .header("CF-Access-Client-Id", "test-id")
        .header("CF-Access-Client-Secret", "test-secret")
        .build()

    private fun redirect(location: String, request: Request = this.request, code: Int = 302) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Redirect")
        .header("Location", location)
        .build()
        .let(::redirectRequest)

    @Test
    fun `same origin keeps credentials and external covers do not`() {
        assertEquals("test-secret", redirect("/cover.jpg")?.header("CF-Access-Client-Secret"))
        val outside = checkNotNull(redirect("https://cdn.example/cover.jpg"))
        assertEquals("cdn.example", outside.url.host)
        assertNull(outside.header("Authorization"))
        assertNull(outside.header("CF-Access-Client-Id"))
        assertNull(outside.header("CF-Access-Client-Secret"))
    }

    @Test
    fun `redirects cannot downgrade HTTPS or resend a write to another origin`() {
        assertNull(redirect("http://romm.example/cover.jpg"))
        val upload = request.newBuilder().post("fixture".toRequestBody()).build()
        assertNull(redirect("https://cdn.example/upload", upload, 307))
        assertEquals("POST", redirect("/upload", upload, 307)?.method)
    }
}
