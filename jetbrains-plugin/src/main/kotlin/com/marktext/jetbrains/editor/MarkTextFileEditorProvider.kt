package com.marktext.jetbrains.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile
import com.marktext.jetbrains.settings.MarkTextSettings

internal class MarkTextFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!MarkTextSettings.get().useAsDefaultMarkdownEditor()) return false
        if (file.isDirectory) return false
        return SUPPORTED_EXTENSIONS.contains(file.extension?.lowercase())
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return MarkTextFileEditor(project, file)
    }

    override fun disposeEditor(editor: FileEditor) {
        editor.dispose()
    }

    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    private companion object {
        const val EDITOR_TYPE_ID = "marktext-muya-editor"
        val SUPPORTED_EXTENSIONS = setOf(
            "markdown",
            "mdown",
            "mkdn",
            "md",
            "mkd",
            "mdwn",
            "mdtxt",
            "mdtext",
            "mdx",
            "text",
            "txt",
        )
    }
}
