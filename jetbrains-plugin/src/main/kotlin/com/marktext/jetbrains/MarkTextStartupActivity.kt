package com.marktext.jetbrains

import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.HttpConfigurable

/**
 * 预初始化代理配置服务，避免 JCEF 在类初始化块里取服务。
 *
 * `JBCefApp$Holder` 的静态初始化块会调用 `HttpConfigurable.getInstance()`，后者首次创建时
 * 又会请求 `ProxyMigrationService`。平台禁止在类初始化块里获取服务（会打印
 * “Class initialization must not depend on services” 错误日志），而 JCEF 的首次初始化发生在
 * 插件构造 `JBCefBrowser` 时，插件无法改变这个初始化上下文。
 *
 * 在应用启动阶段从普通调用栈里先创建 `HttpConfigurable`（连带创建 `ProxyMigrationService`），
 * 之后 `JBCefApp$Holder` 拿到的就是已存在的实例，平台不会再触发该校验。
 *
 * `HttpConfigurable` 已被平台标记为 forRemoval，等 JCEF 不再在类初始化块里依赖它之后，
 * 这段预热即可删除；这里用 runCatching 包裹，避免该服务被移除后启动报错。
 */
internal class MarkTextStartupActivity : ApplicationInitializedListener {
    private companion object {
        val LOG = Logger.getInstance(MarkTextStartupActivity::class.java)
    }

    @Suppress("DEPRECATION")
    override suspend fun execute() {
        runCatching {
            ApplicationManager.getApplication().getService(HttpConfigurable::class.java)
        }.onFailure { error ->
            LOG.debug("Could not pre-initialize the HTTP proxy configuration service", error)
        }
    }
}
