package com.marktext.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.SearchableConfigurable
import java.awt.Color
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.JColorChooser
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.JTextField
import javax.swing.filechooser.FileNameExtensionFilter

class MarkTextSettingsConfigurable : SearchableConfigurable {
    private var panel: JPanel? = null
    private var wheelSensitivitySpinner: JSpinner? = null
    private var defaultEditorCheckBox: JCheckBox? = null
    private var backgroundColor: Color? = null
    private var backgroundColorButton: JButton? = null
    private var backgroundImageField: JTextField? = null

    override fun getId(): String = "com.marktext.jetbrains.settings"

    override fun getDisplayName(): String = "MarkText"

    override fun createComponent(): JComponent {
        val spinner = JSpinner(
            SpinnerNumberModel(
                MarkTextSettings.DEFAULT_WHEEL_SENSITIVITY,
                MarkTextSettings.MIN_WHEEL_SENSITIVITY,
                MarkTextSettings.MAX_WHEEL_SENSITIVITY,
                0.25,
            ),
        )
        spinner.editor = JSpinner.NumberEditor(spinner, "0.##")
        wheelSensitivitySpinner = spinner
        val defaultEditor = JCheckBox("Use MarkText as the default Markdown editor")
        defaultEditor.isSelected = MarkTextSettings.get().useAsDefaultMarkdownEditor()
        defaultEditorCheckBox = defaultEditor
        backgroundColor = MarkTextSettings.get().editorBackgroundColor()?.let(Color::decode)
        val colorButton = JButton()
        backgroundColorButton = colorButton
        updateColorButton()
        colorButton.addActionListener {
            val selected = JColorChooser.showDialog(panel, "Editor background color", backgroundColor)
            if (selected != null) {
                backgroundColor = Color(selected.red, selected.green, selected.blue)
                updateColorButton()
            }
        }
        val clearColorButton = JButton("Default")
        clearColorButton.addActionListener {
            backgroundColor = null
            updateColorButton()
        }
        val imageField = JTextField(MarkTextSettings.get().editorBackgroundImage().orEmpty(), 24)
        backgroundImageField = imageField
        val browseImageButton = JButton("Browse...")
        browseImageButton.addActionListener {
            val chooser = JFileChooser().apply {
                dialogTitle = "Choose editor background image"
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "webp", "bmp")
                MarkTextSettings.get().editorBackgroundImage()?.let { current -> selectedFile = File(current) }
            }
            if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                imageField.text = chooser.selectedFile.absolutePath
            }
        }
        val clearImageButton = JButton("Clear")
        clearImageButton.addActionListener { imageField.text = "" }

        return JPanel(BorderLayout()).also { root ->
            root.add(JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                add(defaultEditor)
                add(JPanel(FlowLayout(FlowLayout.LEFT)).also { row ->
                    row.add(JLabel("Mouse wheel sensitivity:"))
                    row.add(spinner)
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT)).also { row ->
                    row.add(JLabel("Editor background color:"))
                    row.add(colorButton)
                    row.add(clearColorButton)
                })
                add(JPanel(FlowLayout(FlowLayout.LEFT)).also { row ->
                    row.add(JLabel("Editor background image:"))
                    row.add(imageField)
                    row.add(browseImageButton)
                    row.add(clearImageButton)
                })
            }, BorderLayout.NORTH)
            panel = root
        }
    }

    override fun isModified(): Boolean {
        val spinner = wheelSensitivitySpinner ?: return false
        val settings = MarkTextSettings.get()
        return spinnerValue(spinner) != settings.wheelSensitivity() ||
            defaultEditorCheckBox?.isSelected != settings.useAsDefaultMarkdownEditor() ||
            selectedBackgroundColor() != settings.editorBackgroundColor() ||
            backgroundImageField?.text?.trim().orEmpty() != settings.editorBackgroundImage().orEmpty()
    }

    override fun apply() {
        val spinner = wheelSensitivitySpinner ?: return
        val value = MarkTextSettings.clampSensitivity(spinnerValue(spinner))
        MarkTextSettings.get().setWheelSensitivity(value)
        defaultEditorCheckBox?.let {
            MarkTextSettings.get().setUseAsDefaultMarkdownEditor(it.isSelected)
        }
        MarkTextSettings.get().setEditorBackgroundColor(selectedBackgroundColor())
        MarkTextSettings.get().setEditorBackgroundImage(backgroundImageField?.text)
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(MarkTextSettings.TOPIC)
            .wheelSensitivityChanged(value)
        ApplicationManager.getApplication()
            .messageBus
            .syncPublisher(MarkTextSettings.TOPIC)
            .backgroundChanged(
                MarkTextSettings.get().editorBackgroundColor(),
                MarkTextSettings.get().editorBackgroundImageUrl(),
            )
    }

    override fun reset() {
        wheelSensitivitySpinner?.value = MarkTextSettings.get().wheelSensitivity()
        defaultEditorCheckBox?.isSelected = MarkTextSettings.get().useAsDefaultMarkdownEditor()
        backgroundColor = MarkTextSettings.get().editorBackgroundColor()?.let(Color::decode)
        updateColorButton()
        backgroundImageField?.text = MarkTextSettings.get().editorBackgroundImage().orEmpty()
    }

    override fun disposeUIResources() {
        wheelSensitivitySpinner = null
        defaultEditorCheckBox = null
        backgroundColorButton = null
        backgroundImageField = null
        backgroundColor = null
        panel = null
    }

    private fun spinnerValue(spinner: JSpinner): Double =
        (spinner.value as Number).toDouble()

    private fun selectedBackgroundColor(): String? = backgroundColor?.let { color ->
        "#%02X%02X%02X".format(color.red, color.green, color.blue)
    }

    private fun updateColorButton() {
        backgroundColorButton?.apply {
            text = backgroundColor?.let { "#%02X%02X%02X".format(it.red, it.green, it.blue) } ?: "Choose..."
            background = backgroundColor ?: Color(238, 238, 238)
            isOpaque = true
        }
    }
}
