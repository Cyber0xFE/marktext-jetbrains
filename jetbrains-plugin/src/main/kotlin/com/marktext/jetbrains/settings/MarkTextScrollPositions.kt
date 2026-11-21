package com.marktext.jetbrains.settings

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * 按文件记住 Muya 编辑器上次关闭时的滚动位置。
 *
 * 位置保存在项目级 [PropertiesComponent]（项目 `.idea/workspace.xml`），
 * 因此只在当前项目内生效，也不会让全局设置文件随打开过的文件无限增长。
 */
@Service(Service.Level.PROJECT)
internal class MarkTextScrollPositions(private val project: Project) {
    private val properties = PropertiesComponent.getInstance(project)

    fun positionOf(filePath: String): Int = properties.getInt(key(filePath), 0).coerceAtLeast(0)

    fun remember(filePath: String, scrollTop: Int) {
        val value = scrollTop.coerceAtLeast(0)
        val key = key(filePath)
        if (value == 0) {
            if (properties.getValue(key) != null) properties.unsetValue(key)
        }
        else if (properties.getInt(key, -1) != value) {
            properties.setValue(key, value.toString())
        }
    }

    private fun key(filePath: String): String = "$KEY_PREFIX$filePath"

    companion object {
        private const val KEY_PREFIX = "marktext.scrollTop."

        fun getInstance(project: Project): MarkTextScrollPositions =
            project.getService(MarkTextScrollPositions::class.java)
    }
}
