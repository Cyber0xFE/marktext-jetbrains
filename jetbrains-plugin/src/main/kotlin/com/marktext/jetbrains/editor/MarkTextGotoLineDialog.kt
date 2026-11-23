package com.marktext.jetbrains.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent

/**
 * Ctrl+G 的「跳转到行」对话框。
 *
 * 平台的 Go to Line 对话框（`GotoLineNumberDialog` 系列）只按原生 [com.intellij.openapi.editor.Editor]
 * 的偏移跳转，且被标注为 `@ApiStatus.Internal`，所以这里用 [DialogWrapper] 复刻同样的交互：
 * 输入行号后回车确认，行号非数字或越界时在输入框下方提示合法范围。
 */
internal class MarkTextGotoLineDialog(project: Project, private val lineCount: Int) :
    DialogWrapper(project, true) {

    private val field = JBTextField()

    /** 用户确认的行号（从 1 开始）；取消或校验失败时为 null。 */
    var lineNumber: Int? = null
        private set

    init {
        title = "Go to Line"
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent("Line number (1-$lineCount):", field, true)
        .panel

    override fun doValidate(): ValidationInfo? {
        val line = field.text.trim().toIntOrNull()
            ?: return ValidationInfo("Enter a line number between 1 and $lineCount", field)
        if (line < 1 || line > lineCount) {
            return ValidationInfo("Line number must be between 1 and $lineCount", field)
        }
        return null
    }

    override fun doOKAction() {
        lineNumber = field.text.trim().toIntOrNull()
        super.doOKAction()
    }
}
