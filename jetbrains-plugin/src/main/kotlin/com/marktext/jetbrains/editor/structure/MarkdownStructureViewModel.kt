package com.marktext.jetbrains.editor.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.FileEditorPositionListener
import com.intellij.ide.structureView.ModelListener
import com.intellij.ide.structureView.StructureViewClickEvent
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.util.treeView.smartTree.Filter
import com.intellij.ide.util.treeView.smartTree.Grouper
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon

/**
 * 基于 markdown 文档文本的 Structure 视图模型：
 * 监听 document 变化刷新标题树，并根据 web 端滚动位置把当前浏览到的标题推送到视图选中。
 */
internal class MarkdownStructureViewModel(
    private val document: Document,
    fileName: String,
    private val onNavigate: (headingText: String) -> Unit,
) : StructureViewModel, StructureViewModel.ClickHandler {

    private val root = MarkdownStructureRootElement(document, fileName, onNavigate)
    private val modelListeners = CopyOnWriteArrayList<ModelListener>()
    private val positionListeners = CopyOnWriteArrayList<FileEditorPositionListener>()
    private var currentHeadingText: String? = null
    private var currentHeadingIndex: Int = -1
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            fireModelUpdate()
        }
    }

    init {
        document.addDocumentListener(documentListener)
    }

    override fun getRoot(): StructureViewTreeElement = root

    override fun getGroupers(): Array<Grouper> = Grouper.EMPTY_ARRAY

    override fun getSorters(): Array<Sorter> = Sorter.EMPTY_ARRAY

    override fun getFilters(): Array<Filter> = Filter.EMPTY_ARRAY

    override fun getCurrentEditorElement(): Any? {
        val text = currentHeadingText ?: return null
        return root.find(text) ?: root.at(currentHeadingIndex)
    }

    override fun addEditorPositionListener(listener: FileEditorPositionListener) {
        positionListeners.add(listener)
    }

    override fun removeEditorPositionListener(listener: FileEditorPositionListener) {
        positionListeners.remove(listener)
    }

    override fun addModelListener(listener: ModelListener) {
        modelListeners.add(listener)
    }

    override fun removeModelListener(listener: ModelListener) {
        modelListeners.remove(listener)
    }

    override fun dispose() {
        document.removeDocumentListener(documentListener)
        modelListeners.clear()
        positionListeners.clear()
    }

    override fun shouldEnterElement(element: Any): Boolean = false

    /**
     * 单击 Structure 树节点即跳转到对应标题（双击则由 Navigatable.navigate 处理）。
     */
    override fun handleClick(event: StructureViewClickEvent): CompletableFuture<Boolean> {
        val element = event.element
        if (element.canNavigate()) {
            element.navigate(false)
        }
        return CompletableFuture.completedFuture(true)
    }

    /**
     * web 端滚动后上报当前视口顶部的标题，推动 Structure 视图选中该标题。
     * 是否真正选中取决于 Structure 视图的 "Autoscroll from Source" 开关。
     */
    fun onViewportHeadingChanged(headingText: String, headingIndex: Int) {
        if (currentHeadingText == headingText && currentHeadingIndex == headingIndex) return
        currentHeadingText = headingText
        currentHeadingIndex = headingIndex
        for (listener in positionListeners) {
            listener.onCurrentElementChanged()
        }
    }

    private fun fireModelUpdate() {
        for (listener in modelListeners) {
            listener.onModelChanged()
        }
    }
}

private class MarkdownStructureRootElement(
    private val document: Document,
    private val fileName: String,
    private val onNavigate: (String) -> Unit,
) : StructureViewTreeElement {

    private var cachedText: String? = null
    private var cachedChildren: Array<MarkdownHeadingElement> = emptyArray()

    override fun getValue(): Any = fileName

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = fileName
        override fun getIcon(open: Boolean): Icon = AllIcons.FileTypes.Text
    }

    override fun getChildren(): Array<out TreeElement> {
        refreshChildren()
        return cachedChildren
    }

    /**
     * 按标题文本在缓存的结构树中查找元素。返回树中的同一实例，
     * 因为 Structure 视图按对象身份选中节点。
     */
    fun find(headingText: String): MarkdownHeadingElement? {
        refreshChildren()
        val normalized = MarkdownOutlineParser.normalize(headingText)
        if (normalized.isEmpty()) return null
        findIn(cachedChildren) { element -> element.normalizedText == normalized }?.let { return it }
        return findIn(cachedChildren) { element ->
            element.normalizedText.isNotEmpty()
                && (element.normalizedText.contains(normalized) || normalized.contains(element.normalizedText))
        }
    }

    override fun canNavigate(): Boolean = false

    override fun navigate(requestFocus: Boolean) = Unit

    /** 按顶层标题顺序取第 [index] 个元素，作为文本匹配的兜底。 */
    fun at(index: Int): MarkdownHeadingElement? {
        refreshChildren()
        if (index < 0) return null
        val pressed = flatten(cachedChildren)
        return pressed.getOrNull(index)
    }

    private fun refreshChildren() {
        val text = document.text
        if (text == cachedText) return
        cachedText = text
        cachedChildren = buildHeadingNodes(MarkdownOutlineParser.parse(text), onNavigate)
    }

    /** 按文档顺序展开所有层级，得到与 web 端一致的顶层标题序列。 */
    private fun flatten(nodes: Array<MarkdownHeadingElement>): List<MarkdownHeadingElement> {
        val flat = ArrayList<MarkdownHeadingElement>()
        val stack = ArrayDeque<MarkdownHeadingElement>()
        nodes.reversed().forEach(stack::addFirst)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            flat.add(node)
            node.childElements().reversed().forEach(stack::addFirst)
        }
        return flat
    }

    private fun findIn(
        nodes: Array<MarkdownHeadingElement>,
        predicate: (MarkdownHeadingElement) -> Boolean,
    ): MarkdownHeadingElement? {
        for (node in nodes) {
            if (predicate(node)) return node
            findIn(node.childElements(), predicate)?.let { return it }
        }
        return null
    }
}

private class MarkdownHeadingElement(
    private val heading: MarkdownHeading,
    private val onNavigate: (String) -> Unit,
) : StructureViewTreeElement {

    private val children = ArrayList<MarkdownHeadingElement>()

    val level: Int get() = heading.level

    val normalizedText: String = MarkdownOutlineParser.normalize(heading.text)

    fun addChild(child: MarkdownHeadingElement) {
        children.add(child)
    }

    fun childElements(): Array<MarkdownHeadingElement> = children.toTypedArray()

    override fun getValue(): Any = heading

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = heading.text
        override fun getIcon(open: Boolean): Icon = AllIcons.Nodes.Tag
    }

    override fun getChildren(): Array<out TreeElement> = children.toTypedArray()

    override fun canNavigate(): Boolean = heading.text.isNotEmpty()

    override fun canNavigateToSource(): Boolean = heading.text.isNotEmpty()

    override fun navigate(requestFocus: Boolean) {
        if (heading.text.isNotEmpty()) onNavigate(heading.text)
    }
}

/**
 * 将扁平的标题列表按 level 构建成层级树。
 */
private fun buildHeadingNodes(
    headings: List<MarkdownHeading>,
    onNavigate: (String) -> Unit,
): Array<MarkdownHeadingElement> {
    val roots = ArrayList<MarkdownHeadingElement>()
    val stack = ArrayList<MarkdownHeadingElement>()
    for (heading in headings) {
        while (stack.isNotEmpty() && stack.last().level >= heading.level) {
            stack.removeAt(stack.lastIndex)
        }
        val node = MarkdownHeadingElement(heading, onNavigate)
        val parent = stack.lastOrNull()
        if (parent != null) parent.addChild(node) else roots.add(node)
        stack.add(node)
    }
    return roots.toTypedArray()
}
