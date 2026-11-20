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
 * 监听 document 变化，实时把标题层级刷新到 Structure 视图。
 */
internal class MarkdownStructureViewModel(
    private val document: Document,
    fileName: String,
    private val onNavigate: (headingText: String) -> Unit,
) : StructureViewModel, StructureViewModel.ClickHandler {

    private val root = MarkdownStructureRootElement(document, fileName, onNavigate)
    private val modelListeners = CopyOnWriteArrayList<ModelListener>()
    private val positionListeners = CopyOnWriteArrayList<FileEditorPositionListener>()
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

    override fun getCurrentEditorElement(): Any? = null

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

    override fun getValue(): Any = fileName

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = fileName
        override fun getIcon(open: Boolean): Icon = AllIcons.FileTypes.Text
    }

    override fun getChildren(): Array<out TreeElement> {
        val headings = MarkdownOutlineParser.parse(document.text)
        return buildHeadingNodes(headings, onNavigate)
    }

    override fun canNavigate(): Boolean = false

    override fun navigate(requestFocus: Boolean) = Unit
}

private class MarkdownHeadingElement(
    private val heading: MarkdownHeading,
    private val onNavigate: (String) -> Unit,
) : StructureViewTreeElement {

    private val children = ArrayList<MarkdownHeadingElement>()

    val level: Int get() = heading.level

    fun addChild(child: MarkdownHeadingElement) {
        children.add(child)
    }

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
): Array<out TreeElement> {
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
