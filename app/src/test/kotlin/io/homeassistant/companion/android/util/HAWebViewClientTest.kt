package io.homeassistant.companion.android.util

import android.net.Uri
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient.ERROR_AUTHENTICATION
import android.webkit.WebViewClient.ERROR_CONNECT
import android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE
import android.webkit.WebViewClient.ERROR_HOST_LOOKUP
import android.webkit.WebViewClient.ERROR_PROXY_AUTHENTICATION
import android.webkit.WebViewClient.ERROR_TIMEOUT
import android.webkit.WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME
import androidx.annotation.StringRes
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.keychain.ClientCertProvider
import io.homeassistant.companion.android.common.data.keychain.ClientCertificate
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.frontend.error.FrontendConnectionError
import io.homeassistant.companion.android.testing.unit.MainDispatcherJUnit5Extension
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@ExtendWith(MainDispatcherJUnit5Extension::class)
class HAWebViewClientTest {

    private val keyChainRepository: KeyChainRepository = mockk(relaxed = true)
    private val clientCertProvider = object : ClientCertProvider {
        override val certificate: ClientCertificate? = null
    }
    private val currentUrlFlow = MutableStateFlow<String?>(null)
    private var capturedError: FrontendConnectionError? = null
    private val subresourceSslErrorUrls = mutableListOf<String?>()

    private lateinit var webViewClient: HAWebViewClient

    @BeforeEach
    fun setup() {
        capturedError = null
        subresourceSslErrorUrls.clear()
        webViewClient = HAWebViewClient(
            keyChainRepository = keyChainRepository,
            clientCertProvider = clientCertProvider,
            currentUrlFlow = currentUrlFlow,
            onFrontendError = { capturedError = it },
            onCrash = null,
            onUrlIntercepted = null,
            onPageFinished = null,
            onReceivedHttpAuthRequest = null,
            onSubresourceSslError = { subresourceSslErrorUrls += it },
            isStandaloneResourceTarget = { url ->
                url?.contains("/api/") == true || url?.contains("/local/") == true
            },
        )
    }

    @Test
    fun `Given onPageFinished callback when onPageFinished then invokes callback with final url`() {
        var finishedUrl: String? = null
        val client = HAWebViewClient(
            keyChainRepository = keyChainRepository,
            clientCertProvider = clientCertProvider,
            currentUrlFlow = currentUrlFlow,
            onFrontendError = { capturedError = it },
            onCrash = null,
            onUrlIntercepted = null,
            onPageFinished = { finishedUrl = it },
            onReceivedHttpAuthRequest = null,
        )

        client.onPageFinished(null, "http://homeassistant.local:80/onboarding")

        assertEquals("http://homeassistant.local:80/onboarding", finishedUrl)
    }

    @Nested
    inner class StandaloneResourceReadiness {
        private val evaluationCallback = slot<ValueCallback<String>>()
        private val evaluatedScript = slot<String>()
        private var readyUrls: Pair<String?, String?>? = null
        private lateinit var client: HAWebViewClient
        private lateinit var webView: WebView

        private fun beginNavigation(startedUrl: String, finishedUrl: String, eligibleTarget: Boolean = true) {
            currentUrlFlow.value = startedUrl
            webView = mockk(relaxed = true) {
                every { url } returns finishedUrl
                every { evaluateJavascript(capture(evaluatedScript), capture(evaluationCallback)) } returns Unit
            }
            client = HAWebViewClient(
                keyChainRepository = keyChainRepository,
                clientCertProvider = clientCertProvider,
                currentUrlFlow = currentUrlFlow,
                onFrontendError = { capturedError = it },
                onCrash = null,
                onUrlIntercepted = null,
                onPageFinished = null,
                onReceivedHttpAuthRequest = null,
                onStandaloneResourceReady = { source, final -> readyUrls = source to final },
                isStandaloneResourceTarget = { eligibleTarget },
            )
            client.onPageStarted(webView, startedUrl, null)
            client.onPageFinished(webView, finishedUrl)
        }

        private fun completeEvaluation(result: String) {
            evaluationCallback.captured.onReceiveValue(result)
        }

        @Test
        fun `Given successful 206 media response when main frame finishes then signals readiness`() {
            val url = "https://example.com/api/camera.png"
            beginNavigation(url, url)

            completeEvaluation(
                """{"status":206,"redirectCount":0,"redirectStart":0,"mime":"image/png","html":false,"mediaReady":true}""",
            )

            assertEquals(url to url, readyUrls)
            assertTrue(evaluatedScript.captured.contains("responseStatus"))
            assertTrue(evaluatedScript.captured.contains("redirectCount"))
        }

        @Test
        fun `Given HTTP error response when main frame finishes then does not signal readiness`() {
            val url = "https://example.com/api/error.json"
            beginNavigation(url, url)

            completeEvaluation(
                """{"status":404,"redirectCount":0,"redirectStart":0,"mime":"application/json","html":false,"mediaReady":false}""",
            )

            assertEquals(null, readyUrls)
        }

        @Test
        fun `Given unavailable status and decoded media when main frame finishes then signals guarded fallback`() {
            val url = "https://example.com/local/camera.mp4"
            beginNavigation(url, url)

            completeEvaluation(
                """{"status":0,"redirectCount":0,"redirectStart":0,"mime":"video/mp4","html":false,"mediaReady":true}""",
            )

            assertEquals(url to url, readyUrls)
        }

        @Test
        fun `Given statusless media still loading when main frame finishes then reevaluates until decoded`() {
            val url = "https://example.com/local/camera.mp4"
            beginNavigation(url, url)
            every { webView.postDelayed(any(), any()) } answers {
                firstArg<Runnable>().run()
                true
            }

            completeEvaluation(
                """{"status":0,"redirectCount":0,"redirectStart":0,"mime":"video/mp4","html":false,"mediaReady":false,"mediaPending":true}""",
            )
            completeEvaluation(
                """{"status":0,"redirectCount":0,"redirectStart":0,"mime":"video/mp4","html":false,"mediaReady":true,"mediaPending":false}""",
            )

            assertEquals(url to url, readyUrls)
            verify(exactly = 2) { webView.evaluateJavascript(any(), any()) }
        }

        @Test
        fun `Given unavailable status and JSON document when main frame finishes then does not signal fallback`() {
            val url = "https://example.com/api/error.json"
            beginNavigation(url, url)

            completeEvaluation(
                """{"status":0,"redirectCount":0,"redirectStart":0,"mime":"application/json","html":false,"mediaReady":false}""",
            )

            assertEquals(null, readyUrls)
        }

        @Test
        fun `Given frontend HTML 200 when main frame finishes then leaves it to the frontend handshake`() {
            val url = "https://example.com/api/hassio_ingress/token/"
            beginNavigation(url, url)

            completeEvaluation(
                """{"status":200,"redirectCount":0,"redirectStart":0,"mime":"text/html","html":true,"mediaReady":false}""",
            )

            assertEquals(null, readyUrls)
        }

        @Test
        fun `Given same-origin redirect to API media when main frame finishes then signals final readiness`() {
            val startedUrl = "https://example.com/api/redirect"
            val finishedUrl = "https://example.com/api/camera.mp4"
            beginNavigation(startedUrl, finishedUrl)

            completeEvaluation(
                """{"status":200,"redirectCount":1,"redirectStart":2.5,"mime":"video/mp4","html":false,"mediaReady":true}""",
            )

            assertEquals(startedUrl to finishedUrl, readyUrls)
        }

        @Test
        fun `Given same-origin redirect outside api to media when main frame finishes then signals final readiness`() {
            val startedUrl = "https://example.com/api/download"
            val finishedUrl = "https://example.com/media/camera.mp4"
            beginNavigation(startedUrl, finishedUrl)

            completeEvaluation(
                """{"status":200,"redirectCount":1,"redirectStart":2.5,"mime":"video/mp4","html":false,"mediaReady":true}""",
            )

            assertEquals(startedUrl to finishedUrl, readyUrls)
        }

        @Test
        fun `Given selected target outside api and local when main frame finishes then does not evaluate for standalone readiness`() {
            val url = "https://example.com/dashboard"
            beginNavigation(url, url, eligibleTarget = false)

            assertEquals(null, readyUrls)
            verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
        }

        @Test
        fun `Given final document is off-origin when main frame finishes then does not signal readiness`() {
            beginNavigation("https://example.com/api/redirect", "https://other.example/api/camera.mp4")

            assertEquals(null, readyUrls)
            verify(exactly = 0) { webView.evaluateJavascript(any(), any()) }
        }

        @Test
        fun `Given a native main-frame HTTP error before asynchronous readiness result then readiness is invalidated`() {
            val url = "https://example.com/api/camera.mp4"
            beginNavigation(url, url)
            val request = mockRequest(url)
            val response = mockk<WebResourceResponse> {
                every { statusCode } returns 404
                every { reasonPhrase } returns "Not Found"
            }
            client.onReceivedHttpError(webView, request, response)

            completeEvaluation(
                """{"status":0,"redirectCount":0,"redirectStart":0,"mime":"video/mp4","html":false,"mediaReady":true}""",
            )

            assertEquals(null, readyUrls)
            assertTrue(capturedError is FrontendConnectionError.Unknown)
        }
    }

    @Test
    fun `Given SSL_DATE_INVALID error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_DATE_INVALID, commonR.string.webview_error_SSL_DATE_INVALID)
    }

    @Test
    fun `Given SSL_EXPIRED error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_EXPIRED, commonR.string.webview_error_SSL_EXPIRED)
    }

    @Test
    fun `Given SSL_IDMISMATCH error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_IDMISMATCH, commonR.string.webview_error_SSL_IDMISMATCH)
    }

    @Test
    fun `Given SSL_INVALID error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_INVALID, commonR.string.webview_error_SSL_INVALID)
    }

    @Test
    fun `Given SSL_NOTYETVALID error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_NOTYETVALID, commonR.string.webview_error_SSL_NOTYETVALID)
    }

    @Test
    fun `Given SSL_UNTRUSTED error when onReceivedSslError then emits SslError`() {
        testSslError(SslError.SSL_UNTRUSTED, commonR.string.webview_error_SSL_UNTRUSTED)
    }

    @Test
    fun `Given null SSL error when onReceivedSslError then emits generic SSL error`() {
        webViewClient.onReceivedSslError(null, null, null)

        assertNotNull(capturedError)
        assertTrue(capturedError is FrontendConnectionError.SslError)
        assertEquals(commonR.string.error_ssl, capturedError?.message)
    }

    @Test
    fun `Given SSL error for a subresource when onReceivedSslError then reports its url instead of failing`() {
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val subresourceUrl = "https://analytics.example.com/beacon.min.js"
        val sslError = mockSslError(SslError.SSL_UNTRUSTED, url = subresourceUrl)

        webViewClient.onReceivedSslError(null, null, sslError)

        assertEquals(null, capturedError)
        assertEquals(listOf(subresourceUrl), subresourceSslErrorUrls)
    }

    private fun testSslError(primaryError: Int, @StringRes expectedMessageRes: Int) {
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val sslError = mockSslError(primaryError, url = "http://homeassistant.local:8123/auth/authorize")

        webViewClient.onReceivedSslError(null, null, sslError)

        assertFrontendError<FrontendConnectionError.SslError>(
            expectedMessageRes,
            "SSL Error: $primaryError",
            SslError::class,
        )
        assertTrue(subresourceSslErrorUrls.isEmpty())
    }

    private fun mockSslError(primaryError: Int, url: String): SslError = mockk {
        every { this@mockk.primaryError } returns primaryError
        every { this@mockk.url } returns url
        every { this@mockk.toString() } returns "SSL Error: $primaryError"
    }

    @Test
    fun `Given expired TLS cert when onReceivedHttpError then emits TlsCertExpired`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)

        webViewClient.isTLSClientAuthNeeded = true
        webViewClient.isCertificateChainValid = false

        webViewClient.onReceivedHttpError(webView, request, null)

        assertFrontendError<FrontendConnectionError.TlsCertExpired>(
            commonR.string.tls_cert_expired_message,
            errorDetails(null, "No description"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given TLS cert not found when onReceivedHttpError then emits TlsCertNotFound`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 400
            every { reasonPhrase } returns "Bad Request"
        }

        webViewClient.isTLSClientAuthNeeded = true
        webViewClient.isCertificateChainValid = true

        webViewClient.onReceivedHttpError(webView, request, response)

        assertFrontendError<FrontendConnectionError.TlsCertNotFound>(
            commonR.string.tls_cert_not_found_message,
            errorDetails(400, "Bad Request"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given generic HTTP error when onReceivedHttpError then emits Unknown`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 418
            every { reasonPhrase } returns "I'm a teapot"
        }

        webViewClient.isTLSClientAuthNeeded = false

        webViewClient.onReceivedHttpError(webView, request, response)

        assertFrontendError<FrontendConnectionError.Unknown>(
            commonR.string.connection_error_unknown_error,
            errorDetails(418, "I'm a teapot"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given HTTP error without reason when onReceivedHttpError then emits Unknown with no description`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 500
            every { reasonPhrase } returns ""
        }

        webViewClient.isTLSClientAuthNeeded = false

        webViewClient.onReceivedHttpError(webView, request, response)

        assertFrontendError<FrontendConnectionError.Unknown>(
            commonR.string.connection_error_unknown_error,
            errorDetails(500, "No description"),
            WebResourceResponse::class,
        )
    }

    @Test
    fun `Given HTTP error for different URL when onReceivedHttpError then does not emit error`() {
        val webView = mockWebView()
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val request = mockRequest("http://different-url.com/something")
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 500
            every { reasonPhrase } returns "Server Error"
        }

        webViewClient.onReceivedHttpError(webView, request, response)

        assertEquals(null, capturedError)
    }

    @Test
    fun `Given same-origin redirect main-frame HTTP error when received then emits error`() {
        val selectedUrl = "http://homeassistant.local:8123/api/redirect"
        val finalUrl = "http://homeassistant.local:8123/media/missing.mp4"
        currentUrlFlow.value = selectedUrl
        val webView = mockWebView()
        every { webView.url } returns finalUrl
        val request = mockRequest(finalUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 404
            every { reasonPhrase } returns "Not Found"
        }
        webViewClient.onPageStarted(webView, selectedUrl, null)

        webViewClient.onReceivedHttpError(webView, request, response)

        assertTrue(capturedError is FrontendConnectionError.Unknown)
    }

    @Test
    fun `Given delayed main-frame error for a previous distinct URL then does not replace current load`() {
        val previousUrl = "http://homeassistant.local:8123/api/previous"
        val currentUrl = "http://homeassistant.local:8123/api/current"
        currentUrlFlow.value = currentUrl
        val webView = mockWebView()
        every { webView.url } returns currentUrl
        webViewClient.onPageStarted(webView, currentUrl, null)
        val request = mockRequest(previousUrl)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 404
            every { reasonPhrase } returns "Not Found"
        }

        webViewClient.onReceivedHttpError(webView, request, response)

        assertEquals(null, capturedError)
    }

    @Test
    fun `Given same-origin HTTP error for a subresource then does not emit main-frame error`() {
        val currentUrl = "http://homeassistant.local:8123/api/states"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl, isForMainFrame = false)
        val response = mockk<WebResourceResponse> {
            every { statusCode } returns 500
            every { reasonPhrase } returns "Server Error"
        }

        webViewClient.onReceivedHttpError(mockWebView(), request, response)

        assertEquals(null, capturedError)
    }

    @Test
    fun `Given ERROR_FAILED_SSL_HANDSHAKE when onReceivedError then emits SslError`() {
        testReceivedError(
            errorCode = ERROR_FAILED_SSL_HANDSHAKE,
            expectedMessageRes = commonR.string.webview_error_FAILED_SSL_HANDSHAKE,
            expectedErrorType = FrontendConnectionError.SslError::class,
        )
    }

    @Test
    fun `Given ERROR_AUTHENTICATION when onReceivedError then emits AuthRevoked`() {
        testReceivedError(
            errorCode = ERROR_AUTHENTICATION,
            expectedMessageRes = commonR.string.webview_error_AUTHENTICATION,
            expectedErrorType = FrontendConnectionError.AuthRevoked::class,
        )
    }

    @Test
    fun `Given ERROR_PROXY_AUTHENTICATION when onReceivedError then emits AuthRevoked`() {
        testReceivedError(
            errorCode = ERROR_PROXY_AUTHENTICATION,
            expectedMessageRes = commonR.string.webview_error_PROXY_AUTHENTICATION,
            expectedErrorType = FrontendConnectionError.AuthRevoked::class,
        )
    }

    @Test
    fun `Given ERROR_UNSUPPORTED_AUTH_SCHEME when onReceivedError then emits AuthRevoked`() {
        testReceivedError(
            errorCode = ERROR_UNSUPPORTED_AUTH_SCHEME,
            expectedMessageRes = commonR.string.webview_error_AUTH_SCHEME,
            expectedErrorType = FrontendConnectionError.AuthRevoked::class,
        )
    }

    @Test
    fun `Given ERROR_HOST_LOOKUP when onReceivedError then emits Unreachable`() {
        testReceivedError(
            errorCode = ERROR_HOST_LOOKUP,
            expectedMessageRes = commonR.string.webview_error_HOST_LOOKUP,
            expectedErrorType = FrontendConnectionError.Unreachable::class,
        )
    }

    @Test
    fun `Given ERROR_TIMEOUT when onReceivedError then emits Timeout`() {
        testReceivedError(
            errorCode = ERROR_TIMEOUT,
            expectedMessageRes = commonR.string.webview_error_TIMEOUT,
            expectedErrorType = FrontendConnectionError.Timeout::class,
        )
    }

    @Test
    fun `Given ERROR_CONNECT when onReceivedError then emits Unreachable`() {
        testReceivedError(
            errorCode = ERROR_CONNECT,
            expectedMessageRes = commonR.string.webview_error_CONNECT,
            expectedErrorType = FrontendConnectionError.Unreachable::class,
        )
    }

    @Test
    fun `Given unknown error code when onReceivedError then emits Unknown`() {
        testReceivedError(
            errorCode = -999,
            expectedMessageRes = commonR.string.connection_error_unknown_error,
            expectedErrorType = FrontendConnectionError.Unknown::class,
        )
    }

    @Test
    fun `Given error without description when onReceivedError then emits error with no description`() {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val error = mockk<WebResourceError> {
            every { errorCode } returns -1
            every { description } returns ""
        }

        webViewClient.onReceivedError(webView, request, error)

        assertFrontendError<FrontendConnectionError.Unknown>(
            commonR.string.connection_error_unknown_error,
            errorDetails(-1, "No description"),
            WebResourceError::class,
        )
    }

    @Test
    fun `Given error for different URL when onReceivedError then does not emit error`() {
        val webView = mockWebView()
        currentUrlFlow.value = "http://homeassistant.local:8123/auth/authorize"
        val request = mockRequest("http://different-url.com/something")
        val error = mockk<WebResourceError> {
            every { errorCode } returns ERROR_HOST_LOOKUP
            every { description } returns "Host lookup failed"
        }

        webViewClient.onReceivedError(webView, request, error)

        assertEquals(null, capturedError)
    }

    private fun testReceivedError(
        errorCode: Int,
        @StringRes expectedMessageRes: Int,
        expectedErrorType: KClass<out FrontendConnectionError>,
    ) {
        val webView = mockWebView()
        val currentUrl = "http://homeassistant.local:8123/auth/authorize"
        currentUrlFlow.value = currentUrl
        val request = mockRequest(currentUrl)
        val description = "Error description"
        val error = mockk<WebResourceError> {
            every { this@mockk.errorCode } returns errorCode
            every { this@mockk.description } returns description
        }

        webViewClient.onReceivedError(webView, request, error)

        assertNotNull(capturedError)
        assertTrue(expectedErrorType.isInstance(capturedError))
        assertEquals(expectedMessageRes, capturedError?.message)
        assertEquals(errorDetails(errorCode, description), capturedError?.errorDetails)
        assertEquals(WebResourceError::class.toString(), capturedError?.rawErrorType)
    }

    private inline fun <reified T : FrontendConnectionError> assertFrontendError(
        @StringRes messageId: Int,
        errorDetails: String?,
        errorClass: KClass<*>,
    ) {
        assertNotNull(capturedError)
        assertTrue(capturedError is T, "Expected ${T::class.simpleName} but got ${capturedError?.let { it::class.simpleName }}")
        assertEquals(messageId, capturedError?.message)
        assertEquals(errorDetails, capturedError?.errorDetails)
        assertEquals(errorClass.toString(), capturedError?.rawErrorType)
    }

    private fun errorDetails(code: Int?, description: String?): String {
        return "Status Code: ${code}\nDescription: $description"
    }

    private fun mockWebView(): WebView {
        return mockk {
            every { context } returns mockk {
                val code = slot<String>()
                val detail = slot<String>()
                every { getString(any(), capture(code), capture(detail)) } answers {
                    errorDetails(code.captured.toIntOrNull(), detail.captured)
                }
                every { getString(commonR.string.no_description) } returns "No description"
            }
        }
    }

    @Test
    fun `Given onReceivedHttpAuthRequest callback when auth requested then callback receives resource url`() {
        var capturedHandler: HttpAuthHandler? = null
        var capturedHost: String? = null
        var capturedResource: String? = null
        var capturedRealm: String? = null
        val client = HAWebViewClient(
            keyChainRepository = keyChainRepository,
            clientCertProvider = clientCertProvider,
            currentUrlFlow = currentUrlFlow,
            onFrontendError = { capturedError = it },
            onCrash = null,
            onUrlIntercepted = null,
            onPageFinished = null,
            onReceivedHttpAuthRequest = { handler, host, resource, realm ->
                capturedHandler = handler
                capturedHost = host
                capturedResource = resource
                capturedRealm = realm
            },
        )
        val handler = mockk<HttpAuthHandler>(relaxed = true)

        // onLoadResource sets the last resource URL
        client.onLoadResource(mockk(relaxed = true), "https://example.com/protected")
        client.onReceivedHttpAuthRequest(mockk(relaxed = true), handler, "example.com", "myrealm")

        assertTrue(capturedHandler === handler)
        assertEquals("example.com", capturedHost)
        assertEquals("https://example.com/protected", capturedResource)
        assertEquals("myrealm", capturedRealm)
    }

    @Test
    fun `Given no onReceivedHttpAuthRequest callback when auth requested then does not crash`() {
        webViewClient.onReceivedHttpAuthRequest(mockk(relaxed = true), mockk(relaxed = true), "example.com", "realm")
        // No exception thrown
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `Given onCanGoBackChanged callback when doUpdateVisitedHistory then reports webView canGoBack`(
        canGoBack: Boolean,
    ) {
        var captured: Boolean? = null
        val client = HAWebViewClient(
            keyChainRepository = keyChainRepository,
            clientCertProvider = clientCertProvider,
            currentUrlFlow = currentUrlFlow,
            onFrontendError = { capturedError = it },
            onCrash = null,
            onUrlIntercepted = null,
            onPageFinished = null,
            onReceivedHttpAuthRequest = null,
            onCanGoBackChanged = { captured = it },
        )
        val webView = mockk<WebView> { every { canGoBack() } returns canGoBack }

        client.doUpdateVisitedHistory(webView, "https://example.com", false)

        assertEquals(canGoBack, captured)
    }

    @Nested
    inner class HistoryChanges {
        private val listenerSlot = slot<WebViewCompat.WebMessageListener>()
        private val reported = mutableListOf<Boolean>()

        @AfterEach
        fun tearDown() {
            unmockkAll()
        }

        private fun mockWebViewFeatures(supported: Boolean) {
            mockkStatic(WebViewFeature::class)
            every { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) } returns supported
            every { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) } returns supported
            mockkStatic(WebViewCompat::class)
            every { WebViewCompat.addWebMessageListener(any(), any(), any(), capture(listenerSlot)) } returns Unit
            every { WebViewCompat.addDocumentStartJavaScript(any(), any(), any()) } returns mockk()
        }

        private fun createClient(onCanGoBackChanged: ((Boolean) -> Unit)? = { reported += it }) = HAWebViewClient(
            keyChainRepository = keyChainRepository,
            clientCertProvider = clientCertProvider,
            currentUrlFlow = currentUrlFlow,
            onFrontendError = { capturedError = it },
            onCrash = null,
            onUrlIntercepted = null,
            onPageFinished = null,
            onReceivedHttpAuthRequest = null,
            onCanGoBackChanged = onCanGoBackChanged,
        )

        private fun verifyRegistrations(webView: WebView, times: Int) {
            verify(exactly = times) {
                WebViewCompat.addWebMessageListener(webView, HISTORY_CHANGED_LISTENER, setOf("*"), any())
                WebViewCompat.addDocumentStartJavaScript(webView, any(), setOf("*"))
            }
        }

        @Test
        fun `Given supported WebView when pages start then registers history script once per WebView`() {
            mockWebViewFeatures(supported = true)
            val client = createClient()
            val firstWebView = mockk<WebView>()
            val recreatedWebView = mockk<WebView>()

            client.onPageStarted(firstWebView, "https://example.com", null)
            client.onPageStarted(firstWebView, "https://example.com/other", null)
            client.onPageStarted(recreatedWebView, "https://example.com", null)

            verifyRegistrations(firstWebView, times = 1)
            verifyRegistrations(recreatedWebView, times = 1)
        }

        @Test
        fun `Given registered WebView when a frame posts a message then reports current canGoBack`() {
            mockWebViewFeatures(supported = true)
            val webView = mockk<WebView>()
            createClient().onPageStarted(webView, "https://example.com", null)

            every { webView.canGoBack() } returns true
            listenerSlot.captured.onPostMessage(webView, mockk(), mockk<Uri>(), false, mockk())
            every { webView.canGoBack() } returns false
            listenerSlot.captured.onPostMessage(webView, mockk(), mockk<Uri>(), false, mockk())

            assertEquals(listOf(true, false), reported)
        }

        @Test
        fun `Given unsupported WebView when page starts then registers nothing`() {
            mockWebViewFeatures(supported = false)
            val webView = mockk<WebView>()

            createClient().onPageStarted(webView, "https://example.com", null)

            verifyRegistrations(webView, times = 0)
        }

        @Test
        fun `Given no onCanGoBackChanged callback when page starts then registers nothing`() {
            mockWebViewFeatures(supported = true)
            val webView = mockk<WebView>()

            createClient(onCanGoBackChanged = null).onPageStarted(webView, "https://example.com", null)

            verifyRegistrations(webView, times = 0)
        }
    }

    private fun mockRequest(url: String, isForMainFrame: Boolean = true) = mockk<android.webkit.WebResourceRequest> {
        every { this@mockk.isForMainFrame } returns isForMainFrame
        every { this@mockk.url } returns mockk {
            every { this@mockk.toString() } returns url
        }
    }
}
