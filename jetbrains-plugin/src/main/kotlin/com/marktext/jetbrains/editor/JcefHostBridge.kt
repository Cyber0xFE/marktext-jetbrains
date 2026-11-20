package com.marktext.jetbrains.editor

import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.diagnostic.Logger
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefDisplayHandlerAdapter

internal class JcefHostBridge(
    private val browser: JBCefBrowser,
    private val messageHandler: (String) -> Unit,
    private val readyHandler: () -> Unit,
) {
    private val query = JBCefJSQuery.create(browser)

    private companion object {
        val LOG = Logger.getInstance(JcefHostBridge::class.java)
    }

    private val loadHandler = object : CefLoadHandlerAdapter() {
        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain != true) return
            LOG.info("JCEF loaded ${frame.url} (HTTP $httpStatusCode)")
            readyHandler()
        }

        override fun onLoadError(
            browser: CefBrowser?,
            frame: CefFrame?,
            errorCode: CefLoadHandler.ErrorCode?,
            errorText: String?,
            failedUrl: String?,
        ) {
            if (frame?.isMain != true) return
            LOG.warn("JCEF failed to load $failedUrl: $errorCode $errorText")
        }
    }

    private val displayHandler = object : CefDisplayHandlerAdapter() {
        override fun onConsoleMessage(
            browser: CefBrowser?,
            level: CefSettings.LogSeverity?,
            message: String?,
            source: String?,
            line: Int,
        ): Boolean {
            if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR) {
                LOG.warn("JCEF JavaScript error at $source:$line: $message")
            }
            return false
        }
    }

    init {
        query.addHandler { payload ->
            messageHandler(payload)
            null
        }
        browser.jbCefClient.addLoadHandler(loadHandler, browser.cefBrowser)
        browser.jbCefClient.addDisplayHandler(displayHandler, browser.cefBrowser)
    }

    fun load(html: String, baseUrl: String) {
        browser.loadHTML(html.replace("<!-- MARKTEXT_HOST_BRIDGE -->", outboundBridgeScript()), baseUrl)
    }

    fun call(functionName: String, argumentJson: String) {
        val expression = "window.marktextHost?.$functionName($argumentJson);"
        browser.cefBrowser.executeJavaScript(expression, WebBundle.scriptUrl(), 0)
    }

    fun dispose() {
        browser.jbCefClient.removeLoadHandler(loadHandler, browser.cefBrowser)
        browser.jbCefClient.removeDisplayHandler(displayHandler, browser.cefBrowser)
        Disposer.dispose(query)
    }

    private fun outboundBridgeScript(): String {
        val invokeQuery = query.inject("payload")
        return """
            window.__marktextHostPostMessage = function (payload) {
              $invokeQuery
            };
            window.addEventListener("error", function (event) {
              window.__marktextHostPostMessage(JSON.stringify({
                type: "error",
                message: event.message + " at " + event.filename + ":" + event.lineno + ":" + event.colno
              }));
            });
        """.trimIndent()
    }
}
