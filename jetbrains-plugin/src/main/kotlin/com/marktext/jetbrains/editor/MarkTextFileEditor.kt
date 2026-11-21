package com.marktext.jetbrains.editor

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.openapi.wm.ToolWindowManager
import com.marktext.jetbrains.settings.MarkTextSettings
import com.marktext.jetbrains.editor.structure.MarkdownStructureViewModel
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.io.File
import java.beans.PropertyChangeSupport
import java.nio.file.Paths
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JPanel

internal class MarkTextFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : FileEditor {
    private val gson = Gson()
    private companion object {
        val LOG = Logger.getInstance(MarkTextFileEditor::class.java)
    }
    private val documentManager = FileDocumentManager.getInstance()
    private val document: Document? = documentManager.getDocument(file)
    private val browser = JBCefBrowser()
    private val bridge = JcefHostBridge(browser, ::handleHostMessage, ::handleWebReady)
    private val panel = JPanel(BorderLayout()).apply {
        add(browser.component, BorderLayout.CENTER)
    }
    private val propertyChanges = PropertyChangeSupport(this)
    private val historyShortcutsDisposable = Disposer.newDisposable("marktext.history.shortcuts")
    private val userData = UserDataHolderBase()
    private val settingsConnection = ApplicationManager.getApplication().messageBus.connect()
    private var applyingWebDocument = false
    private var disposed = false

    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (applyingWebDocument || disposed) return
            sendDocumentToWeb(event.document.text)
            propertyChanges.firePropertyChange(FileEditor.getPropModified(), false, true)
        }
    }

    init {
        settingsConnection.subscribe(
            MarkTextSettings.TOPIC,
            object : MarkTextSettings.Listener {
                override fun wheelSensitivityChanged(value: Double) {
                    if (!disposed) bridge.call("setWheelSensitivity", gson.toJson(value))
                }

                override fun backgroundChanged(color: String?, imageUrl: String?) {
                    if (!disposed) {
                        bridge.call("setEditorBackground", gson.toJson(mapOf("color" to color, "imageUrl" to imageUrl)))
                    }
                }
            },
        )
        document?.addDocumentListener(documentListener)
        registerHistoryShortcuts()
        bridge.load(WebBundle.html(), WebBundle.baseUrl(file.path))
    }

    override fun <T : Any> getUserData(key: Key<T>): T? = userData.getUserData(key)

    override fun <T : Any> putUserData(key: Key<T>, value: T?) {
        userData.putUserData(key, value)
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = browser.component

    override fun getName(): String = file.name

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = documentManager.isFileModified(file)

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        propertyChanges.addPropertyChangeListener(listener)
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        propertyChanges.removePropertyChangeListener(listener)
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun getStructureViewBuilder(): StructureViewBuilder? {
        val target = document ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel {
                return MarkdownStructureViewModel(target, file.name, ::scrollWebToHeading)
            }
        }
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState = EmptyFileEditorState

    override fun dispose() {
        if (disposed) return
        disposed = true
        settingsConnection.dispose()
        document?.removeDocumentListener(documentListener)
        Disposer.dispose(historyShortcutsDisposable)
        bridge.dispose()
        browser.dispose()
    }

    /**
     * Undo/redo must stay inside Muya. Otherwise the IDE keymap handles Ctrl+Z as a document undo,
     * which rewrites the buffer; the document listener then pushes the restored text back through
     * `loadDocument`, rebuilding the whole editor and dropping the caret to the top of the file.
     *
     * Component-level custom shortcuts are resolved before the IDE keymap actions (the same
     * mechanism the platform uses for Cut/Copy/Paste inside JCEF on macOS).
     */
    private fun registerHistoryShortcuts() {
        val ctrl = InputEvent.CTRL_DOWN_MASK
        registerHistoryShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "undo")
        registerHistoryShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl or InputEvent.SHIFT_DOWN_MASK), "redo")
        registerHistoryShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), "redo")
    }

    private fun registerHistoryShortcut(keyStroke: KeyStroke, action: String) {
        val historyAction = object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) {
                if (disposed) return
                bridge.call("history", gson.toJson(action))
            }
        }
        historyAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(keyStroke, null)),
            browser.component,
            historyShortcutsDisposable,
        )
    }

    private fun handleWebReady() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) sendDocumentToWeb(document?.text.orEmpty())
        }
    }

    private fun handleHostMessage(payload: String) {
        if (disposed) return
        val message = runCatching { gson.fromJson(payload, JsonObject::class.java) }.getOrNull() ?: return
        when (message.get("type")?.asString) {
            "documentChanged" -> {
                val markdown = message.get("markdown")?.asString ?: return
                updateDocument(markdown)
            }
            "selectionChanged" -> {
                val selectedText = message.get("selectedText")?.asString.orEmpty()
                syncPiEditorContext(selectedText)
            }
            "status" -> LOG.info("Web startup: ${message.get("message")?.asString}")
            "openLink" -> {
                val href = message.get("href")?.asString ?: return
                openLink(href)
            }
            "error" -> {
                val detail = message.get("message")?.asString ?: "Unknown web editor error"
                project.getService(MarkTextNotificationService::class.java).report(detail)
            }
        }
    }

    private fun scrollWebToHeading(headingText: String) {
        if (!disposed) bridge.call("scrollToHeading", gson.toJson(headingText))
    }

    private fun openLink(href: String) {
        // 页内锚点（#...）暂不支持定位，直接忽略
        val target = href.substringBefore('#')
        if (target.isBlank()) return

        val lower = target.lowercase()
        val isExternalUrl = lower.startsWith("http://")
            || lower.startsWith("https://")
            || lower.startsWith("mailto:")
            || lower.startsWith("ftp://")
            || lower.startsWith("tel:")
        if (isExternalUrl) {
            BrowserUtil.browse(target)
            return
        }

        // 相对路径：优先基于当前文件所在目录解析，其次项目根目录
        val candidates = buildList {
            val direct = File(target)
            if (direct.isAbsolute) {
                add(direct)
            }
            else {
                file.parent?.let { add(File(it.path, target)) }
                project.basePath?.let { add(File(it, target)) }
            }
        }
        val resolved = candidates.firstOrNull { it.isFile } ?: return

        val virtualFile = VfsUtil.findFileByIoFile(resolved, false) ?: run {
            LOG.info("Link target not resolvable in IDE: $href")
            return
        }

        // JCEF 的 JS query 回调不在 EDT 上，后台线程直接 openFile 会走 runBlockingCancellable 的非
        // cancellable 分支并报 “There is no ProgressIndicator or Job in this thread”，必须切到 EDT。
        ApplicationManager.getApplication().invokeLater {
            if (disposed || !virtualFile.isValid) return@invokeLater
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
    }

    private fun sendDocumentToWeb(markdown: String) {
        val readOnly = !file.isWritable
        LOG.info("Loading ${file.path} into Muya (readOnly=$readOnly, documentAvailable=${document != null})")
        val payload = mapOf(
            "markdown" to markdown,
            "dirname" to file.parent?.path,
            "wheelSensitivity" to MarkTextSettings.get().wheelSensitivity(),
            "editorBackgroundColor" to MarkTextSettings.get().editorBackgroundColor(),
            "editorBackgroundImageUrl" to MarkTextSettings.get().editorBackgroundImageUrl(),
            "readOnly" to readOnly,
        )
        bridge.call("loadDocument", gson.toJson(payload))
    }

    private fun updateDocument(markdown: String) {
        val target = document ?: return

        ApplicationManager.getApplication().invokeLater {
            if (disposed || target.text == markdown) return@invokeLater

            applyingWebDocument = true
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    target.setText(markdown)
                }
            } catch (error: Exception) {
                LOG.warn("Could not write Markdown edits to ${file.path}", error)
                project.getService(MarkTextNotificationService::class.java)
                    .report("Could not write Markdown edits: ${error.message ?: error.javaClass.simpleName}")
            } finally {
                applyingWebDocument = false
            }
            propertyChanges.firePropertyChange(FileEditor.getPropModified(), false, true)
        }
    }

    private fun syncPiEditorContext(selectedText: String) {
        val context = buildPiEditorContext(selectedText)
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater

            runCatching {
                val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Pi") ?: return@runCatching
                val panel = toolWindow.contentManager.selectedContent?.component ?: return@runCatching
                val serviceField = generateSequence<Class<*>>(panel.javaClass) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .firstOrNull { it.name == "service" }
                    ?: return@runCatching
                if (!serviceField.trySetAccessible()) return@runCatching

                val service = serviceField.get(panel) ?: return@runCatching
                val setter = service.javaClass.methods.firstOrNull { method ->
                    method.name == "setEditorContext"
                        && method.parameterTypes.contentEquals(arrayOf(String::class.java))
                } ?: return@runCatching
                setter.invoke(service, context)
            }.onFailure { error ->
                LOG.debug("Pi editor context synchronization is unavailable", error)
            }
        }
    }

    private fun buildPiEditorContext(selectedText: String): String? {
        val target = document ?: return null
        val text = selectedText.trim()
        if (text.isEmpty()) return null

        val source = target.text
        val range = findSelectedRange(source, text) ?: return null
        val startLine = target.getLineNumber(range.startOffset)
        val endLine = target.getLineNumber((range.endOffset - 1).coerceAtLeast(range.startOffset))
        val path = project.basePath?.let { basePath ->
            runCatching {
                Paths.get(basePath).toAbsolutePath().normalize()
                    .relativize(Paths.get(file.path).toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/')
            }.getOrNull()
        } ?: file.path
        val unsaved = documentManager.isDocumentUnsaved(target)

        return buildString {
            append("--- ").append(path).append(':').append(startLine + 1)
            if (endLine != startLine) append('-').append(endLine + 1)
            append(if (unsaved) " (buffer) ---\n" else " ---\n")
            if (unsaved) append(target.getText(range))
        }
    }

    private fun findSelectedRange(source: String, selectedText: String): TextRange? {
        val exactStart = source.indexOf(selectedText)
        if (exactStart >= 0) return TextRange(exactStart, exactStart + selectedText.length)

        val fragments = selectedText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (fragments.isEmpty()) return null

        val first = source.indexOf(fragments.first())
        val last = source.lastIndexOf(fragments.last())
        if (first < 0 || last < first) return null
        return TextRange(first, last + fragments.last().length)
    }

    private object EmptyFileEditorState : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
            return otherState === this
        }
    }
}
