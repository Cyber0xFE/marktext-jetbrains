package com.marktext.jetbrains.editor

import com.intellij.find.FindModel
import com.intellij.find.SearchReplaceComponent
import com.intellij.find.SearchSession
import com.intellij.find.editorHeaderActions.ToggleMatchCase
import com.intellij.find.editorHeaderActions.ToggleRegex
import com.intellij.find.editorHeaderActions.ToggleWholeWordsOnlyAction
import com.intellij.find.EditorSearchSession
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import javax.swing.JComponent

/**
 * JCEF Markdown 编辑器的查找会话。
 *
 * 界面的查找栏完全复用 IDE 内置的 [SearchReplaceComponent]（与文本编辑器的
 * Ctrl+F 同一个组件），这里只负责三件事：
 *  1. 把 [FindModel] 里的检索条件转给 Web 端 Muya；
 *  2. 把 Web 端返回的匹配数量 / 当前序号写回查找栏状态文本；
 *  3. 把前进 / 后退与关闭请求转给宿主。
 *
 * 之所以不复用平台针对原生 [com.intellij.openapi.editor.Editor] 的
 * `EditorSearchSession`：Muya 的高亮与选区在 JCEF 里，原生会话搜索的是
 * 不可见的影子编辑器，既看不到高亮也无法把 markdown 源码偏移映射回渲染视图。
 */
internal class MarkTextFindSession(
    private val project: Project,
    private val targetComponent: JComponent,
    private val onSearch: (FindModel) -> Unit,
    private val onNavigate: (forward: Boolean) -> Unit,
    private val onClose: () -> Unit,
) : SearchSession, SearchReplaceComponent.Listener {

    /** 查找条件；界面选项按钮直接修改它。 */
    val model = FindModel()

    private var component: SearchReplaceComponent? = null
    private var matchCount = 0
    private var currentIndex = -1

    /** 查找栏组件；首次访问时创建，之后复用（与 IDE 同一行为：再次 Ctrl+F 只重新聚焦）。 */
    val searchComponent: SearchReplaceComponent
        get() = component ?: buildComponent().also { component = it }

    val isOpened: Boolean
        get() = component?.isVisible == true

    private fun buildComponent(): SearchReplaceComponent {
        val built = SearchReplaceComponent
            .buildFor(project, targetComponent, this)
            // 注意：不能用 withShowOnlySearchPanel()，那个分支只摆放左侧搜索区、
            // 不添加右侧工具栏（计数 / ↑↓ / 漏斗 / 更多都会消失）。
            .addPrimarySearchActions(*EditorSearchSession.createPrimarySearchActions())
            .addExtraSearchActions(ToggleMatchCase(), ToggleWholeWordsOnlyAction(), ToggleRegex())
            .withCloseAction { onClose() }
            .build()
        built.addListener(this)
        // 选项按钮（Aa / W / .*）直接改 FindModel，需要重新执行检索。
        model.addObserver(object : FindModel.FindModelObserver {
            override fun findModelChanged(findModel: FindModel) {
                onSearch(findModel)
            }
        })
        return built
    }

    /** 打开查找栏：刷新输入框内容并聚焦，随后按当前条件执行一次检索。 */
    fun open() {
        val view = searchComponent
        view.update(model.stringToFind.orEmpty(), "", false, false)
        view.isVisible = true
        // 刚被加入布局时组件尚未 validate，立即请求焦点可能落空，放到下一次 EDT 事件里。
        ApplicationManager.getApplication().invokeLater {
            view.requestFocusInTheSearchFieldAndSelectContent(project)
        }
        onSearch(model)
    }

    fun hide() {
        component?.isVisible = false
        matchCount = 0
        currentIndex = -1
    }

    /** Web 端上报的匹配结果。 */
    fun updateResults(total: Int, index: Int) {
        val view = component ?: return
        matchCount = total
        currentIndex = index
        if (total == 0) {
            view.setStatusText(if (view.searchTextComponent.text.isEmpty()) "" else "0/0")
            view.setNotFoundBackground()
        }
        else {
            view.setRegularBackground()
            view.setStatusText("${index + 1}/$total")
        }
        view.updateActions()
    }

    override fun getFindModel(): FindModel = model

    override fun getComponent(): SearchReplaceComponent = searchComponent

    override fun hasMatches(): Boolean = matchCount > 0

    override fun searchForward() = onNavigate(true)

    override fun searchBackward() = onNavigate(false)

    override fun close() = onClose()

    /** 查找栏输入框内容变化（实时搜索）。 */
    override fun searchFieldDocumentChanged() {
        model.stringToFind = searchComponent.searchTextComponent.text
        onSearch(model)
    }

    override fun toggleSearchReplaceMode() {
        model.isReplaceState = !model.isReplaceState
    }
}
