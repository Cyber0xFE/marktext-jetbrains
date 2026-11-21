package com.marktext.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.util.messages.Topic
import java.nio.file.Paths

@Service(Service.Level.APP)
@State(name = "MarkTextSettings", storages = [Storage("marktext.xml")])
class MarkTextSettings : PersistentStateComponent<MarkTextSettings.State> {
    data class State(
        var wheelSensitivity: Double = DEFAULT_WHEEL_SENSITIVITY,
        var useAsDefaultMarkdownEditor: Boolean = true,
        var showSourceLineNumbers: Boolean = true,
        var editorBackgroundColor: String? = null,
        var editorBackgroundImage: String? = null,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = State(
            wheelSensitivity = clampSensitivity(state.wheelSensitivity),
            useAsDefaultMarkdownEditor = state.useAsDefaultMarkdownEditor,
            showSourceLineNumbers = state.showSourceLineNumbers,
            editorBackgroundColor = normalizeColor(state.editorBackgroundColor),
            editorBackgroundImage = normalizeImagePath(state.editorBackgroundImage),
        )
    }

    fun wheelSensitivity(): Double = state.wheelSensitivity

    fun setWheelSensitivity(value: Double) {
        state.wheelSensitivity = clampSensitivity(value)
    }

    fun useAsDefaultMarkdownEditor(): Boolean = state.useAsDefaultMarkdownEditor

    fun setUseAsDefaultMarkdownEditor(value: Boolean) {
        state.useAsDefaultMarkdownEditor = value
    }

    fun showSourceLineNumbers(): Boolean = state.showSourceLineNumbers

    fun setShowSourceLineNumbers(value: Boolean) {
        state.showSourceLineNumbers = value
    }

    fun editorBackgroundColor(): String? = state.editorBackgroundColor

    fun setEditorBackgroundColor(value: String?) {
        state.editorBackgroundColor = normalizeColor(value)
    }

    fun editorBackgroundImage(): String? = state.editorBackgroundImage

    fun editorBackgroundImageUrl(): String? = state.editorBackgroundImage?.let { path ->
        runCatching { Paths.get(path).toUri().toASCIIString() }.getOrNull()
    }

    fun setEditorBackgroundImage(value: String?) {
        state.editorBackgroundImage = normalizeImagePath(value)
    }

    interface Listener {
        fun wheelSensitivityChanged(value: Double)

        fun sourceLineNumbersChanged(enabled: Boolean)

        fun backgroundChanged(color: String?, imageUrl: String?)
    }

    companion object {
        const val DEFAULT_WHEEL_SENSITIVITY = 1.0
        const val MIN_WHEEL_SENSITIVITY = 0.25
        const val MAX_WHEEL_SENSITIVITY = 4.0

        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("MarkText settings", Listener::class.java)

        fun get(): MarkTextSettings =
            ApplicationManager.getApplication().getService(MarkTextSettings::class.java)

        fun clampSensitivity(value: Double): Double =
            if (value.isFinite()) {
                value.coerceIn(MIN_WHEEL_SENSITIVITY, MAX_WHEEL_SENSITIVITY)
            } else {
                DEFAULT_WHEEL_SENSITIVITY
            }

        fun normalizeColor(value: String?): String? =
            value?.trim()?.takeIf { it.matches(Regex("^#[0-9a-fA-F]{6}$")) }?.uppercase()

        fun normalizeImagePath(value: String?): String? =
            value?.trim()?.takeIf(String::isNotEmpty)?.let { path ->
                runCatching { Paths.get(path).toAbsolutePath().normalize().toString() }.getOrNull()
            }
    }
}
