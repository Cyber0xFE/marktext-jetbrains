package com.marktext.jetbrains

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 预初始化代理配置服务，避免 JCEF 在类初始化块里取服务。
 *
 * 旧版 JCEF 会通过 `JBCefProxySettings.getInstance()` 读取 `HttpConfigurable`，而后者首次创建时
 * 又会请求 `ProxyMigrationService`。平台禁止在类初始化块里获取服务（会打印
 * “Class initialization must not depend on services” 错误日志），而 JCEF 的首次初始化发生在
 * 插件构造 `JBCefBrowser` 时，插件无法改变这个初始化上下文。
 *
 * 在项目启动阶段从普通调用栈里先创建 `HttpConfigurable`（连带创建 `ProxyMigrationService`），
 * 之后 JCEF 拿到的就是已存在的实例，平台不会再触发该校验。
 *
 * 该服务已被平台标记为 scheduled for removal（新版 JCEF 已不再依赖它），且插件编译器版本
 * 与运行时 IDE 可能不同，因此这里用反射按类名获取，避免在插件字节码中留下对该服务的
 * 静态引用；服务被移除后调用会失败并记录 debug 日志，不影响插件其余功能。
 */
internal class MarkTextStartupActivity : ProjectActivity {
    private companion object {
        val LOG = Logger.getInstance(MarkTextStartupActivity::class.java)
        val PREWARMED = AtomicBoolean(false)
    }

    override suspend fun execute(project: Project) {
        if (!PREWARMED.compareAndSet(false, true)) return
        runCatching {
            Class.forName("com.intellij.util.net.HttpConfigurable")
                .getMethod("getInstance")
                .invoke(null)
        }.onFailure { error ->
            LOG.debug("Could not pre-initialize the HTTP proxy configuration service", error)
        }
    }
}
