package io.homeassistant.companion.android.util

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.VisibleForTesting
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.homeassistant.companion.android.common.R as commonR
import io.homeassistant.companion.android.common.data.keychain.ClientCertProvider
import io.homeassistant.companion.android.common.data.keychain.KeyChainRepository
import io.homeassistant.companion.android.frontend.EvaluateJavascriptUsage
import io.homeassistant.companion.android.frontend.error.FrontendConnectionError
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import timber.log.Timber

@VisibleForTesting
internal const val HISTORY_CHANGED_LISTENER = "androidInternalHaHistoryChanged"

private const val MAX_MEDIA_READINESS_CHECKS = 40
private const val MEDIA_READINESS_CHECK_INTERVAL_MS = 250L
private const val FIRST_SUCCESSFUL_HTTP_STATUS = 200
private const val LAST_SUCCESSFUL_HTTP_STATUS = 299

/**
 * Notifies once when a new document starts in any frame, then on every same-document history change
 * through the Navigation API.
 *
 * The listener is captured before page scripts run, so a page global with the same name cannot replace it.
 */
private val HISTORY_CHANGED_SCRIPT = """
    (() => {
      const listener = $HISTORY_CHANGED_LISTENER;
      const notify = () => listener.postMessage("");
      window.navigation?.addEventListener("currententrychange", notify);
      notify();
    })();
""".trimIndent()

/**
 * Reads the main document's response metadata without issuing another request. Older WebViews may
 * not expose `responseStatus`; for those, only a successfully decoded media document qualifies.
 * HTML stays on the Home Assistant frontend handshake path.
 */
private val STANDALONE_RESOURCE_READINESS_SCRIPT = """
    (() => {
      const navigation = performance.getEntriesByType("navigation")[0];
      const mime = (document.contentType || "").toLowerCase();
      const status = navigation && typeof navigation.responseStatus === "number"
        ? navigation.responseStatus : null;
      const redirectCount = navigation && typeof navigation.redirectCount === "number"
        ? navigation.redirectCount : null;
      const redirectStart = navigation && typeof navigation.redirectStart === "number"
        ? navigation.redirectStart : null;
      const html = mime === "text/html" || mime === "application/xhtml+xml";
      const imageReady = Array.from(document.images).some(image => image.complete && image.naturalWidth > 0);
      const mediaElements = Array.from(document.querySelectorAll("video,audio"));
      const mediaReady = imageReady || mediaElements
        .some(media => media.readyState >= 2 && media.error === null);
      const mediaPending = Array.from(document.images).some(image => !image.complete) ||
        mediaElements.some(media => media.readyState < 2 && media.error === null);
      return {
        status,
        redirectCount,
        redirectStart,
        mime,
        html,
        mediaReady,
        mediaPending
      };
    })();
""".trimIndent()

private fun hasSameHttpOrigin(url: String?, baseUrl: String?): Boolean {
    return url?.toHttpUrlOrNull()?.let { parsedUrl ->
        baseUrl?.toHttpUrlOrNull()?.let { parsedUrl.hasSameOrigin(it) }
    } ?: false
}

private data class MainFrameNavigationSnapshot(
    val selectedUrl: String?,
    val startedUrl: String?,
    val finishedUrl: String?,
    val trackedWebView: WebView?,
    val isStandaloneTarget: Boolean,
)

private data class ReadinessNavigationSnapshot(
    val generation: Long,
    val activeGeneration: Long,
    val failedGeneration: Long?,
    val trackedWebView: WebView?,
    val webView: WebView,
    val startedUrl: String?,
    val selectedUrl: String?,
    val isStandaloneTarget: Boolean,
    val currentPageUrl: String?,
    val finishedUrl: String,
)

private fun isCurrentReadinessNavigation(navigation: ReadinessNavigationSnapshot): Boolean {
    val generationMatches = navigation.generation == navigation.activeGeneration
    val navigationHasNotFailed = navigation.failedGeneration != navigation.generation
    val webViewMatches = navigation.trackedWebView === navigation.webView
    val selectedUrlMatches = navigation.startedUrl == navigation.selectedUrl
    val targetIsEligible = navigation.isStandaloneTarget
    val currentPageMatches = navigation.currentPageUrl == navigation.finishedUrl
    val originMatches = hasSameHttpOrigin(navigation.finishedUrl, navigation.selectedUrl)
    return generationMatches &&
        navigationHasNotFailed &&
        webViewMatches &&
        selectedUrlMatches &&
        targetIsEligible &&
        currentPageMatches &&
        originMatches
}

private fun isCurrentMainFrameUrl(
    navigation: MainFrameNavigationSnapshot,
    view: WebView?,
    resourceUrl: String?,
): Boolean {
    if (resourceUrl == navigation.selectedUrl) return true
    val hasSelectedUrl = navigation.selectedUrl != null
    val isCurrentStandaloneTarget = navigation.isStandaloneTarget
    val isCurrentNavigation = navigation.startedUrl == navigation.selectedUrl
    val isCurrentWebView = view != null && navigation.trackedWebView === view
    val hasSameOrigin = hasSameHttpOrigin(resourceUrl, navigation.selectedUrl)
    val isCurrentDocumentUrl = navigation.trackedWebView?.let { trackedWebView ->
        trackedWebView === view && (resourceUrl == trackedWebView.url || resourceUrl == navigation.finishedUrl)
    } == true
    val isRedirectedMainFrameError =
        hasSelectedUrl &&
            isCurrentStandaloneTarget &&
            isCurrentNavigation &&
            isCurrentWebView &&
            hasSameOrigin &&
            isCurrentDocumentUrl
    return resourceUrl == navigation.selectedUrl || isRedirectedMainFrameError
}

private fun standaloneResponseResult(evaluationResult: String?): Boolean? {
    val result =
        evaluationResult?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return false
    val mime = result["mime"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
    val responseStatus = result["status"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    return if (isEligibleStandaloneDocument(mime, result)) {
        standaloneReadinessFromStatusOrMedia(responseStatus, mime, result)
    } else {
        false
    }
}

private fun isEligibleStandaloneDocument(mime: String, result: kotlinx.serialization.json.JsonObject): Boolean {
    val isHtmlDocument = result["html"]?.jsonPrimitive?.booleanOrNull == true ||
        mime == "text/html" ||
        mime == "application/xhtml+xml"

    val redirectCount = result["redirectCount"]?.jsonPrimitive?.intOrNull
    val redirectStart = result["redirectStart"]?.jsonPrimitive?.doubleOrNull

    // If navigation timing reports a redirect, require a positive redirectStart. A zero count or
    // missing field is not treated as proof that no redirect occurred; the final URL is separately
    // checked against the selected origin before this function is used.
    val redirectIsValid = redirectCount == null || redirectCount <= 0 || (redirectStart != null && redirectStart > 0.0)
    return mime.isNotBlank() && !isHtmlDocument && redirectIsValid
}

private fun isMediaMime(mime: String): Boolean =
    mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")

private fun isSuccessfulHttpStatus(status: Int): Boolean =
    status in FIRST_SUCCESSFUL_HTTP_STATUS..LAST_SUCCESSFUL_HTTP_STATUS

private fun standaloneReadinessFromStatusOrMedia(
    responseStatus: Int?,
    mime: String,
    result: kotlinx.serialization.json.JsonObject,
): Boolean? = responseStatus?.let { isSuccessfulHttpStatus(it) }
    ?: standaloneMediaReadinessResult(isMediaMime(mime), result)

private fun standaloneMediaReadinessResult(
    isMediaMime: Boolean,
    result: kotlinx.serialization.json.JsonObject,
): Boolean? {
    if (!isMediaMime) return false
    return when {
        result["mediaReady"]?.jsonPrimitive?.booleanOrNull == true -> true
        result["mediaPending"]?.jsonPrimitive?.booleanOrNull == true -> null
        else -> false
    }
}

/**
 * Factory for creating [HAWebViewClient] instances dedicated to loading Home Assistant frontend.
 *
 * The created clients handle Home Assistant-specific concerns such as TLS client authentication,
 * error mapping to [FrontendConnectionError], and JavaScript injection into the WebView.
 */
class HAWebViewClientFactory @Inject constructor(private val keyChainRepository: KeyChainRepository) {
    /**
     * Creates a new [HAWebViewClient] with the specified configuration.
     *
     * @param currentUrlFlow StateFlow providing the current URL being loaded.
     *        Used to filter errors - only errors for this URL trigger [onFrontendError].
     * @param onFrontendError Callback when a WebView error is mapped to a [FrontendConnectionError].
     * @param onCrash Optional callback invoked after WebView crash recovery.
     * @param onUrlIntercepted Optional callback to intercept URL navigation.
     *        Receives the URI and whether TLS client auth was required.
     *        Return `true` to prevent WebView from loading the URL.
     * @param onPageFinished Optional callback when a page finishes loading, with the final URL
     *        (after any redirects have resolved).
     * @param onReceivedHttpAuthRequest Optional callback when the server requests HTTP Basic Auth.
     *        Receives the handler, host, the resource URL that triggered the request, and the realm.
     * @param onCanGoBackChanged Optional callback invoked when the WebView back/forward list changes,
     *        reporting whether the WebView can currently navigate back. Covers navigations inside
     *        iframes too, when the WebView supports it.
     * @param onSubresourceSslError Optional callback when an SSL error occurs on a resource other than
     *        the main URL being loaded. Receives the URL of the failing resource. The frontend itself
     *        is unaffected, so this is a notice rather than a connection error.
     * @param onStandaloneResourceReady Optional callback when the current selected API/local target
     *        finishes as a successful non-HTML main-frame document.
     * @param isStandaloneResourceTarget Predicate based on the selected target metadata, not the
     *        resolved URL path (which may contain a configured server prefix).
     */
    suspend fun create(
        currentUrlFlow: StateFlow<String?>,
        onFrontendError: (FrontendConnectionError) -> Unit,
        onCrash: (() -> Unit)? = null,
        onUrlIntercepted: ((uri: Uri, isTLSClientAuthNeeded: Boolean) -> Boolean)? = null,
        onPageFinished: ((url: String?) -> Unit)? = null,
        onReceivedHttpAuthRequest: (
            (
                handler: HttpAuthHandler,
                host: String,
                resource: String,
                realm: String,
            ) -> Unit
        )? = null,
        onCanGoBackChanged: ((canGoBack: Boolean) -> Unit)? = null,
        onSubresourceSslError: ((url: String?) -> Unit)? = null,
        onStandaloneResourceReady: ((startedUrl: String?, finishedUrl: String?) -> Unit)? = null,
        isStandaloneResourceTarget: ((selectedUrl: String?) -> Boolean)? = null,
    ): HAWebViewClient {
        return HAWebViewClient(
            keyChainRepository = keyChainRepository,
            clientCertProvider = keyChainRepository.getClientCertProvider(),
            currentUrlFlow = currentUrlFlow,
            onFrontendError = onFrontendError,
            onCrash = onCrash,
            onUrlIntercepted = onUrlIntercepted,
            onPageFinished = onPageFinished,
            onReceivedHttpAuthRequest = onReceivedHttpAuthRequest,
            onCanGoBackChanged = onCanGoBackChanged,
            onSubresourceSslError = onSubresourceSslError,
            onStandaloneResourceReady = onStandaloneResourceReady,
            isStandaloneResourceTarget = isStandaloneResourceTarget,
        )
    }
}

/**
 * WebViewClient dedicated to loading Home Assistant frontend.
 *
 * Handles error mapping to [FrontendConnectionError], TLS client authentication, and crash recovery.
 * Use [HAWebViewClientFactory] to create instances.
 */
class HAWebViewClient internal constructor(
    keyChainRepository: KeyChainRepository,
    clientCertProvider: ClientCertProvider,
    private val currentUrlFlow: StateFlow<String?>,
    private val onFrontendError: (FrontendConnectionError) -> Unit,
    private val onCrash: (() -> Unit)?,
    private val onUrlIntercepted: ((uri: Uri, isTLSClientAuthNeeded: Boolean) -> Boolean)?,
    private val onPageFinished: ((url: String?) -> Unit)?,
    private val onReceivedHttpAuthRequest: (
        (handler: HttpAuthHandler, host: String, resource: String, realm: String) -> Unit
    )?,
    private val onCanGoBackChanged: ((canGoBack: Boolean) -> Unit)? = null,
    private val onSubresourceSslError: ((url: String?) -> Unit)? = null,
    private val onStandaloneResourceReady: ((startedUrl: String?, finishedUrl: String?) -> Unit)? = null,
    private val isStandaloneResourceTarget: ((selectedUrl: String?) -> Boolean)? = null,
) : TLSWebViewClient(keyChainRepository, clientCertProvider) {

    /** Last resource URL loaded by the WebView, used to identify the resource requesting auth. */
    private var lastResourceUrl: String? = null

    /**
     * Weak reference to the WebView already reporting history changes. This client outlives the WebViews
     * recreated by the screen, so a new WebView is detected and observed as well.
     */
    private var historyObservedWebView: WeakReference<WebView>? = null

    /** Identity of the most recently started main-frame navigation handled by this client. */
    private var mainFrameGeneration: Long = 0
    private var mainFrameStartedUrl: String? = null
    private var mainFrameFinishedUrl: String? = null
    private var mainFrameWebView: WeakReference<WebView>? = null
    private var failedMainFrameGeneration: Long? = null

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (view != null && url != null) {
            mainFrameGeneration++
            mainFrameStartedUrl = url
            mainFrameFinishedUrl = null
            mainFrameWebView = WeakReference(view)
            failedMainFrameGeneration = null
        }
        val onCanGoBackChanged = onCanGoBackChanged
        if (view != null && onCanGoBackChanged != null && historyObservedWebView?.get() !== view) {
            historyObservedWebView = WeakReference(view)
            view.observeHistoryChanges(onCanGoBackChanged)
        }
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        super.onLoadResource(view, url)
        lastResourceUrl = url
    }

    /**
     * Reads readiness from the current document because raw API/local media documents do not load
     * Home Assistant frontend code and cannot send an externalBus readiness event.
     */
    @OptIn(EvaluateJavascriptUsage::class)
    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished?.invoke(url)

        val selectedUrl = currentUrlFlow.value
        selectedUrl
            ?.takeIf { it == mainFrameStartedUrl }
            ?.takeIf { isStandaloneResourceTarget?.invoke(it) == true }
            ?.let { startedUrl ->
                val webView = view
                val finishedUrl = url
                if (webView != null && finishedUrl != null) {
                    val isTrackedWebView = mainFrameWebView?.get() === webView
                    val originMatches = hasSameHttpOrigin(finishedUrl, startedUrl)
                    val pageUrlMatches = webView.url == finishedUrl
                    if (isTrackedWebView && originMatches && pageUrlMatches) {
                        mainFrameFinishedUrl = finishedUrl
                        val generation = mainFrameGeneration
                        val isCurrentNavigation = {
                            isCurrentReadinessNavigation(
                                ReadinessNavigationSnapshot(
                                    generation = generation,
                                    activeGeneration = mainFrameGeneration,
                                    failedGeneration = failedMainFrameGeneration,
                                    trackedWebView = mainFrameWebView?.get(),
                                    webView = webView,
                                    startedUrl = mainFrameStartedUrl,
                                    selectedUrl = currentUrlFlow.value,
                                    isStandaloneTarget =
                                    isStandaloneResourceTarget?.invoke(currentUrlFlow.value) == true,
                                    currentPageUrl = webView.url,
                                    finishedUrl = finishedUrl,
                                ),
                            )
                        }

                        fun evaluateReadiness(attempt: Int = 0) {
                            if (isCurrentNavigation()) {
                                webView.evaluateJavascript(STANDALONE_RESOURCE_READINESS_SCRIPT) { evaluationResult ->
                                    if (isCurrentNavigation()) {
                                        when (standaloneResponseResult(evaluationResult)) {
                                            true -> onStandaloneResourceReady?.invoke(startedUrl, finishedUrl)
                                            null -> if (attempt < MAX_MEDIA_READINESS_CHECKS) {
                                                webView.postDelayed(
                                                    { evaluateReadiness(attempt + 1) },
                                                    MEDIA_READINESS_CHECK_INTERVAL_MS,
                                                )
                                            }
                                            false -> Unit
                                        }
                                    }
                                }
                            }
                        }

                        evaluateReadiness()
                    }
                }
            }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        view?.let { onCanGoBackChanged?.invoke(it.canGoBack()) }
    }

    override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
        val lastResourceUrl = lastResourceUrl
        if (handler != null &&
            host != null &&
            realm != null &&
            onReceivedHttpAuthRequest != null &&
            lastResourceUrl != null
        ) {
            onReceivedHttpAuthRequest.invoke(handler, host, lastResourceUrl, realm)
        } else {
            super.onReceivedHttpAuthRequest(view, handler, host, realm)
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)

        // Keep the exact selected URL behavior, and also retain errors for a same-origin main-frame
        // redirect. Checking the current WebView URL prevents a late error from an older distinct URL
        // from replacing the active navigation's state.
        val selectedUrl = currentUrlFlow.value
        val navigation = MainFrameNavigationSnapshot(
            selectedUrl = selectedUrl,
            startedUrl = mainFrameStartedUrl,
            finishedUrl = mainFrameFinishedUrl,
            trackedWebView = mainFrameWebView?.get(),
            isStandaloneTarget = selectedUrl?.let { isStandaloneResourceTarget?.invoke(it) } == true,
        )
        if (request?.isForMainFrame != true || !isCurrentMainFrameUrl(navigation, view, request.url.toString())) return
        failedMainFrameGeneration = mainFrameGeneration

        val errorDetails = formatErrorDetails(
            view?.context,
            error?.errorCode,
            error?.description?.toString(),
        )
        Timber.e("onReceivedError: $errorDetails")

        val frontendConnectionError = when (error?.errorCode) {
            ERROR_FAILED_SSL_HANDSHAKE -> FrontendConnectionError.SslError(
                message = commonR.string.webview_error_FAILED_SSL_HANDSHAKE,
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )

            ERROR_AUTHENTICATION -> FrontendConnectionError.AuthRevoked(
                message = commonR.string.webview_error_AUTHENTICATION,
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )

            ERROR_PROXY_AUTHENTICATION -> FrontendConnectionError.AuthRevoked(
                message = commonR.string.webview_error_PROXY_AUTHENTICATION,
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )

            ERROR_UNSUPPORTED_AUTH_SCHEME -> FrontendConnectionError.AuthRevoked(
                message = commonR.string.webview_error_AUTH_SCHEME,
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )

            ERROR_HOST_LOOKUP -> FrontendConnectionError.Unreachable(
                message = commonR.string.webview_error_HOST_LOOKUP,
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )

            ERROR_TIMEOUT -> FrontendConnectionError.Timeout(
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )

            ERROR_CONNECT -> FrontendConnectionError.Unreachable(
                message = commonR.string.webview_error_CONNECT,
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )

            else -> FrontendConnectionError.Unknown(
                errorDetails = errorDetails,
                rawErrorType = WebResourceError::class.toString(),
            )
        }
        onFrontendError(frontendConnectionError)
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)

        // Keep the exact selected URL behavior, and also retain errors for a same-origin main-frame
        // redirect. Checking the current WebView URL prevents a late error from an older distinct URL
        // from replacing the active navigation's state.
        val selectedUrl = currentUrlFlow.value
        val navigation = MainFrameNavigationSnapshot(
            selectedUrl = selectedUrl,
            startedUrl = mainFrameStartedUrl,
            finishedUrl = mainFrameFinishedUrl,
            trackedWebView = mainFrameWebView?.get(),
            isStandaloneTarget = selectedUrl?.let { isStandaloneResourceTarget?.invoke(it) } == true,
        )
        if (request?.isForMainFrame != true || !isCurrentMainFrameUrl(navigation, view, request.url.toString())) return
        failedMainFrameGeneration = mainFrameGeneration

        val errorDetails = formatErrorDetails(
            view?.context,
            errorResponse?.statusCode,
            errorResponse?.reasonPhrase,
        )
        Timber.e("onReceivedHttpError: $errorDetails")

        val frontendConnectionError = when {
            isTLSClientAuthNeeded && !isCertificateChainValid -> FrontendConnectionError.TlsCertExpired(
                errorDetails = errorDetails,
                rawErrorType = WebResourceResponse::class.toString(),
            )

            isTLSClientAuthNeeded && errorResponse?.statusCode == 400 -> FrontendConnectionError.TlsCertNotFound(
                errorDetails = errorDetails,
                rawErrorType = WebResourceResponse::class.toString(),
            )

            else -> FrontendConnectionError.Unknown(
                errorDetails = errorDetails,
                rawErrorType = WebResourceResponse::class.toString(),
            )
        }
        onFrontendError(frontendConnectionError)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        super.onReceivedSslError(view, handler, error)

        // Only fail the connection for the main URL being loaded. An SSL error on any other resource
        // the page pulls in (a third party script, an image, ...) must not block a working frontend.
        // The call to super already cancelled the handshake, so the resource simply won't load.
        val resourceUrl: String? = error?.url
        val selectedUrl = currentUrlFlow.value
        val navigation = MainFrameNavigationSnapshot(
            selectedUrl = selectedUrl,
            startedUrl = mainFrameStartedUrl,
            finishedUrl = mainFrameFinishedUrl,
            trackedWebView = mainFrameWebView?.get(),
            isStandaloneTarget = selectedUrl?.let { isStandaloneResourceTarget?.invoke(it) } == true,
        )
        if (error != null && !isCurrentMainFrameUrl(navigation, view, resourceUrl)) {
            Timber.w("Ignoring SSL error on subresource ${sensitive(resourceUrl.orEmpty())}: $error")
            onSubresourceSslError?.invoke(resourceUrl)
            return
        }

        failedMainFrameGeneration = mainFrameGeneration

        Timber.e("onReceivedSslError: $error")

        val messageRes = when (error?.primaryError) {
            SslError.SSL_DATE_INVALID -> commonR.string.webview_error_SSL_DATE_INVALID
            SslError.SSL_EXPIRED -> commonR.string.webview_error_SSL_EXPIRED
            SslError.SSL_IDMISMATCH -> commonR.string.webview_error_SSL_IDMISMATCH
            SslError.SSL_INVALID -> commonR.string.webview_error_SSL_INVALID
            SslError.SSL_NOTYETVALID -> commonR.string.webview_error_SSL_NOTYETVALID
            SslError.SSL_UNTRUSTED -> commonR.string.webview_error_SSL_UNTRUSTED
            else -> commonR.string.error_ssl
        }
        onFrontendError(
            FrontendConnectionError.SslError(
                message = messageRes,
                errorDetails = error.toString(),
                rawErrorType = SslError::class.toString(),
            ),
        )
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        Timber.e("onRenderProcessGone: webView crashed")
        onCrash?.invoke()
        return true
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return request?.url?.let { onUrlIntercepted?.invoke(it, isTLSClientAuthNeeded) } ?: false
    }

    private fun formatErrorDetails(context: Context?, code: Int?, description: String?): String {
        return context?.getString(
            commonR.string.connection_error_more_details_description_content,
            code.toString(),
            description?.takeIf { it.isNotEmpty() }
                ?: context.getString(commonR.string.no_description),
        ) ?: ""
    }
}

/**
 * Reports [WebView.canGoBack] through [onCanGoBackChanged] when the history entry of any frame changes.
 *
 * `doUpdateVisitedHistory` only fires for the main frame, so a script injected in every frame posts an
 * empty message whenever the frame's history entry changes, triggering a new read of [WebView.canGoBack].
 * The script only runs in frames that begin loading afterward, so call it from `onPageStarted` at the latest.
 */
@SuppressLint("RequiresFeature")
private fun WebView.observeHistoryChanges(onCanGoBackChanged: (canGoBack: Boolean) -> Unit) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    ) {
        // Frames (e.g. iframe panels) can be on any origin. The message carries no data and
        // because of that are safe to accept from all origins.
        val allowedOriginRules = setOf("*")
        WebViewCompat.addWebMessageListener(this, HISTORY_CHANGED_LISTENER, allowedOriginRules) { webView, _, _, _, _ ->
            onCanGoBackChanged(webView.canGoBack())
        }
        WebViewCompat.addDocumentStartJavaScript(this, HISTORY_CHANGED_SCRIPT, allowedOriginRules)
    } else {
        Timber.w("History changes not observable, back navigation inside iframes may be skipped")
    }
}
