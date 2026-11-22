package com.marktext.jetbrains.editor

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.ide.BrowserUtil
import com.intellij.find.FindModel
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewFactoryEx
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.jcef.JBCefBrowser
import com.marktext.jetbrains.settings.MarkTextScrollPositions
import com.marktext.jetbrains.settings.MarkTextSettings
import com.marktext.jetbrains.editor.structure.MarkdownStructureViewModel
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.io.File
import java.beans.PropertyChangeSupport
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import javax.swing.JPanel
import kotlin.math.abs

internal class MarkTextFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : FileEditor {
    private val gson = Gson()
    private companion object {
        val LOG = Logger.getInstance(MarkTextFileEditor::class.java)
        const val EDITOR_NAME = "Muya Markdown Editor"
    }
    private val documentManager = FileDocumentManager.getInstance()
    private val scrollPositions = MarkTextScrollPositions.getInstance(project)
    private val document: Document? = documentManager.getDocument(file)

    /**
     * 供平台和第三方插件读取选中内容的真实 [Editor]。
     *
     * JCEF 中的 Muya 没有原生 Editor，而 AI 编程插件（Qoder、Copilot 等）通常通过
     * [Editor] 的 SelectionModel 与 SelectionEvent 获取选中文本，因此这里创建一个无 UI 的
     * Editor：它和 Muya 共用同一个 [Document]，把 Muya 的选区同步进去即可让这些插件
     * 拿到正确的文件、行号和选中文本。
     *
     * 必须用 [EditorFactory.createEditor] 而不是 [EditorFactory.createViewer]：Qoder 在
     * `EditorFactoryListener.editorCreated` 里用 `CosyInlayManager.isAvailable` 过滤掉
     * `isViewer()` 的 Editor，用 viewer 会连 selection 监听都不会挂上。
     */
    private val shadowEditor: Editor? = document?.let {
        // Qoder 在 editorCreated 回调里用 `PsiDocumentManager.getPsiFile(document)` 判断可用性，
        // 此时 PSI 还没关联会被缓存成“不可用”，所以先确保 PSI 已加载。
        runCatching { PsiManager.getInstance(project).findFile(file) }
        EditorFactory.getInstance().createEditor(it, project, file.fileType, false)
    }
    private val browser = JBCefBrowser()
    private val bridge = JcefHostBridge(browser, ::handleHostMessage, ::handleWebReady)
    private val panel = object : JPanel(BorderLayout()), DataProvider {
        override fun getData(dataId: String): Any? {
            return when {
                // 焦点在 JCEF 里时，DataContext 的编辑器就是这里的影子 Editor；
                // 例如 Qoder 的“添加上下文”动作就是从 CommonDataKeys.EDITOR 取选中内容。
                PlatformDataKeys.EDITOR.`is`(dataId) -> shadowEditor
                PlatformDataKeys.PREDEFINED_TEXT.`is`(dataId) -> predefinedSearchText()
                else -> null
            }
        }
    }.apply {
        add(browser.component, BorderLayout.CENTER)
    }
    private val propertyChanges = PropertyChangeSupport(this)
    private val componentShortcutsDisposable = Disposer.newDisposable("marktext.component.shortcuts")
    private val documentListenersDisposable = Disposer.newDisposable("marktext.editor.document")
    private val userData = UserDataHolderBase()
    private val settingsConnection = ApplicationManager.getApplication().messageBus.connect()
    private var applyingWebDocument = false
    private var disposed = false
    @Volatile
    private var structureModel: MarkdownStructureViewModel? = null
    @Volatile
    private var selectedText: String = ""
    @Volatile
    private var lastSyncedSelection: TextRange? = null
    private var lastSearchSignature: String? = null

    /** 使用 IDE 内置查找栏（与文本编辑器的 Ctrl+F 是同一个组件）。 */
    private val findSession: MarkTextFindSession by lazy {
        MarkTextFindSession(project, browser.component, ::searchInWeb, ::navigateWeb, ::closeFind)
    }

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

                override fun sourceLineNumbersChanged(enabled: Boolean) {
                    if (!disposed) bridge.call("setShowLineNumbers", gson.toJson(enabled))
                }

                override fun backgroundChanged(color: String?, imageUrl: String?) {
                    if (!disposed) {
                        bridge.call("setEditorBackground", gson.toJson(mapOf("color" to color, "imageUrl" to imageUrl)))
                    }
                }
            },
        )
        document?.addDocumentListener(documentListener, documentListenersDisposable)
        registerHistoryShortcuts()
        registerSearchShortcuts()
        bridge.load(WebBundle.html(), WebBundle.baseUrl(file.path))
    }

    override fun <T : Any> getUserData(key: Key<T>): T? = userData.getUserData(key)

    override fun <T : Any> putUserData(key: Key<T>, value: T?) {
        userData.putUserData(key, value)
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = browser.component

    /**
     * 编辑器名称。
     *
     * 平台在展示“当前文件用的是哪个编辑器”时（例如文件有多个编辑器时的切换条）取的就是这个名称；
     * 标签页标题由 `EditorTabPresentationUtil` 依据文件名生成，不受这里影响。
     */
    override fun getName(): String = EDITOR_NAME

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
                return MarkdownStructureViewModel(target, file.name, ::scrollWebToHeading).also {
                    structureModel = it
                }
            }
        }
    }

    override fun getState(level: FileEditorStateLevel): FileEditorState = EmptyFileEditorState

    override fun dispose() {
        if (disposed) return
        disposed = true
        settingsConnection.dispose()
        document?.removeDocumentListener(documentListener)
        Disposer.dispose(documentListenersDisposable)
        Disposer.dispose(componentShortcutsDisposable)
        shadowEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
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
        registerComponentShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl)) {
            bridge.call("history", gson.toJson("undo"))
        }
        registerComponentShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl or InputEvent.SHIFT_DOWN_MASK)) {
            bridge.call("history", gson.toJson("redo"))
        }
        registerComponentShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl)) {
            bridge.call("history", gson.toJson("redo"))
        }
    }

    /**
     * Ctrl+F / F3 / Shift+F3 与 IDE keymap 的 Find 系列动作冲突，必须像 undo/redo 一样
     * 在组件级抢先注册，否则 IDE 会打开针对影子 Editor 的原生查找窗口。
     *
     * 查找栏本身仍用 IDE 内置的 `SearchReplaceComponent`，所以外观、选项按钮与快捷键
     * 与文本编辑器的 Ctrl+F 完全一致，只是搜索由 Muya 执行、高亮画在渲染视图里。
     */
    private fun registerSearchShortcuts() {
        val ctrl = InputEvent.CTRL_DOWN_MASK
        registerComponentShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F, ctrl)) {
            openFind()
        }
        registerComponentShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F3, 0)) {
            if (findSession.isOpened) findSession.searchForward()
        }
        registerComponentShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F3, InputEvent.SHIFT_DOWN_MASK)) {
            if (findSession.isOpened) findSession.searchBackward()
        }
    }

    private fun registerComponentShortcut(keyStroke: KeyStroke, action: () -> Unit) {
        val shortcutAction = object : DumbAwareAction() {
            override fun actionPerformed(event: AnActionEvent) {
                if (disposed) return
                action()
            }
        }
        shortcutAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(keyStroke, null)),
            browser.component,
            componentShortcutsDisposable,
        )
    }

    /**
     * 打开 IDE 内置查找栏。
     *
     * 有选中文本时预填（单行输入框不接受换行），否则复用上一次的查询。
     */
    private fun openFind() {
        val selection = selectedText.trim()
        if (selection.isNotEmpty() && !selection.contains('\n')) {
            findSession.model.stringToFind = selection
        }
        val view = findSession.searchComponent
        if (view.parent == null) {
            panel.add(view, BorderLayout.NORTH)
        }
        // 关闭时 Web 端高亮已清除，重新打开必须重新检索。
        lastSearchSignature = null
        findSession.open()
        panel.revalidate()
        panel.repaint()
    }

    private fun closeFind() {
        findSession.hide()
        lastSearchSignature = null
        if (disposed) return
        bridge.call("clearSearch", "null")
        bridge.call("focus", "null")
        panel.revalidate()
        panel.repaint()
    }

    /** 把 FindModel 的检索条件交给 Muya；相同条件不重复扫描文档。 */
    private fun searchInWeb(model: FindModel) {
        if (disposed) return
        val signature = buildString {
            append(model.stringToFind.orEmpty()).append('\u0000')
            append(model.isCaseSensitive).append('\u0000')
            append(model.isWholeWordsOnly).append('\u0000')
            append(model.isRegularExpressions)
        }
        if (signature == lastSearchSignature) return
        lastSearchSignature = signature
        val payload = mapOf(
            "query" to model.stringToFind.orEmpty(),
            "caseSensitive" to model.isCaseSensitive,
            "wholeWord" to model.isWholeWordsOnly,
            "regexp" to model.isRegularExpressions,
        )
        bridge.call("search", gson.toJson(payload))
    }

    private fun navigateWeb(forward: Boolean) {
        if (disposed) return
        LOG.info("MarkText find: navigate forward=$forward")
        bridge.call(if (forward) "findNext" else "findPrevious", "null")
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
                // Muya 的输入可能只带了新的选区而没有单独的 selection-change，
                // 文档更新后再对一次影子 Editor 的选区，保证行号不落后。
                val text = runCatching { message.get("selectedText")?.asString }.getOrNull().orEmpty()
                selectedText = text
                syncShadowEditorSelection(text)
            }
            "selectionChanged" -> {
                val text = runCatching { message.get("selectedText")?.asString }.getOrNull().orEmpty()
                selectedText = text
                syncShadowEditorSelection(text)
            }
            "scrollPositionChanged" -> {
                val scrollTop = message.get("scrollTop")?.asInt ?: return
                // 回调来自 JCEF 线程，PropertiesComponent 需要在 EDT 上写入；
                // 这里不检查 disposed，编辑器刚关闭时最后一条位置也应当被记住。
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) scrollPositions.remember(file.path, scrollTop)
                }
            }
            "viewportHeadingChanged" -> {
                val headingText = message.get("headingText")?.asString ?: return
                val headingIndex = message.get("headingIndex")?.asInt ?: -1
                syncStructureViewToHeading(headingText, headingIndex)
            }
            "searchResults" -> {
                val total = message.get("total")?.asInt ?: 0
                val index = message.get("index")?.asInt ?: -1
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed) findSession.updateResults(total, index)
                }
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

    /**
     * 连按两下 Shift 打开 Search Everywhere 时的预填文本。
     *
     * 平台在 `GotoActionBase.getInitialText` 里优先读取 [PlatformDataKeys.PREDEFINED_TEXT]，
     * 再从原生 Editor 取选中文本；JCEF 编辑器没有 IDE 原生 `Editor`，所以由宿主组件以
     * `DataProvider` 的形式提供选中文本。
     */
    private fun predefinedSearchText(): String? {
        val text = selectedText.trim()
        // Search Everywhere 的输入框是单行文本框，跨行选择不预填（与平台对编辑器选择的处理一致）。
        if (text.isEmpty() || text.contains('\n')) return null
        return text
    }

    private fun scrollWebToHeading(headingText: String) {
        if (!disposed) bridge.call("scrollToHeading", gson.toJson(headingText))
    }

    /**
     * web 端滚动上报视口顶部标题后，把 Structure 视图选中到对应标题。
     *
     * 这里直接调用 [StructureViewFactoryEx.getStructureViewWrapper] 的 selectCurrentElement，
     * 而不是依赖 "Autoscroll from Source" 开关（该开关默认关闭，而 JCEF 编辑器没有
     * 原生 caret 可供平台轮询，不主动选中则永远看不到同步效果）。
     */
    private fun syncStructureViewToHeading(headingText: String, headingIndex: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed) return@invokeLater
            structureModel?.onViewportHeadingChanged(headingText, headingIndex)
            runCatching {
                StructureViewFactoryEx.getInstanceEx(project)
                    .structureViewWrapper
                    ?.selectCurrentElement(this, file, false)
            }.onFailure { error ->
                LOG.debug("Could not sync the structure view to '$headingText'", error)
            }
        }
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
            OpenFileDescriptor(project, virtualFile).navigate(true)
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
            "showLineNumbers" to MarkTextSettings.get().showSourceLineNumbers(),
            "scrollTop" to scrollPositions.positionOf(file.path),
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

    /**
     * 把 Muya 的选区同步到影子 Editor。
     *
     * 同步后 [Editor] 的 SelectionModel 会广播 SelectionEvent，监听该事件的 AI 插件
     * （如 Qoder 的“自动选中作为上下文”）就能拿到与 Muya 一致的文件、行号和选中文本。
     */
    private fun syncShadowEditorSelection(selectedText: String) {
        val target = document ?: return
        val editor = shadowEditor ?: return
        ApplicationManager.getApplication().invokeLater {
            if (disposed || editor.isDisposed) return@invokeLater

            val range = if (selectedText.isBlank())
                null
            else
                findSelectedRange(target.text, selectedText)
            val selectionModel = editor.selectionModel
            if (range == null) {
                lastSyncedSelection = null
                // 只在确实存在旧选区时清除，避免每次光标移动都广播一次事件。
                if (selectionModel.hasSelection()) selectionModel.removeSelection()
                return@invokeLater
            }

            if (selectionModel.selectionStart == range.startOffset && selectionModel.selectionEnd == range.endOffset) {
                return@invokeLater
            }
            lastSyncedSelection = range
            selectionModel.setSelection(range.startOffset, range.endOffset)
        }
    }

    private fun findSelectedRange(source: String, selectedText: String): TextRange? {
        // 选中文本在源中多次出现时，选距离上一次同步位置最近的匹配，
        // 否则像 `console.log` 这种高频片段会永远定位到文档开头。
        val exact = exactMatches(source, selectedText)
        if (exact.isNotEmpty()) return closestToLast(exact)

        val fragments = selectedText.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (fragments.isEmpty()) return null

        // Muya 的跨块选中会丢掉列表前缀、块间空行，源里未必存在完整原文，
        // 因此用首尾行作为锚点，同样取距离上次同步最近的区间。
        val candidates = mutableListOf<TextRange>()
        var first = source.indexOf(fragments.first())
        while (first >= 0) {
            val last = source.indexOf(fragments.last(), first)
            if (last >= 0) {
                candidates += TextRange(first, last + fragments.last().length)
            }
            first = source.indexOf(fragments.first(), first + 1)
        }
        return closestToLast(candidates)
    }

    private fun exactMatches(source: String, selectedText: String): List<TextRange> {
        val matches = mutableListOf<TextRange>()
        var start = source.indexOf(selectedText)
        while (start >= 0) {
            matches += TextRange(start, start + selectedText.length)
            start = source.indexOf(selectedText, start + 1)
        }
        return matches
    }

    private fun closestToLast(candidates: List<TextRange>): TextRange? {
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val previous = lastSyncedSelection?.startOffset ?: return candidates.first()
        return candidates.minByOrNull { abs(it.startOffset - previous) }
    }

    private object EmptyFileEditorState : FileEditorState {
        override fun canBeMergedWith(otherState: FileEditorState, level: FileEditorStateLevel): Boolean {
            return otherState === this
        }
    }
}
