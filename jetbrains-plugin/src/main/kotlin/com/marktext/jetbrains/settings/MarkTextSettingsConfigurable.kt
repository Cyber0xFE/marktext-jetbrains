package com.marktext.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JColorChooser
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.JTextField

class MarkTextSettingsConfigurable : SearchableConfigurable {
    private var panel: JComponent? = null
    private var wheelSensitivitySpinner: JSpinner? = null
    private var defaultEditorCheckBox: JCheckBox? = null
    private var backgroundColor: Color? = null
    private var backgroundColorButton: JButton? = null
    private var backgroundImageField: JTextField? = null

    override fun getId(): String = "com.marktext.jetbrains.settings"

    override fun getDisplayName(): String = "MarkText"

    override fun createComponent(): JComponent {
        val settings = MarkTextSettings.get()
        backgroundColor = settings.editorBackgroundColor()?.let(Color::decode)
        val imageDescriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "webp", "bmp")
            .apply { title = "Choose Editor Background Image" }

        val root = panel {
            group("Editor") {
                row {
                    checkBox("Use MarkText as the default Markdown editor")
                        .applyToComponent { isSelected = settings.useAsDefaultMarkdownEditor() }
                        .comment("Open .md files in the MarkText editor instead of the plain text editor.")
                        .component
                        .also { defaultEditorCheckBox = it }
                }
            }
            group("Appearance") {
                row("Mouse wheel sensitivity") {
                    spinner(
                        MarkTextSettings.MIN_WHEEL_SENSITIVITY..MarkTextSettings.MAX_WHEEL_SENSITIVITY,
                        WHEEL_SENSITIVITY_STEP,
                    )
                        .applyToComponent {
                            editor = JSpinner.NumberEditor(this, "0.##")
                            value = settings.wheelSensitivity()
                        }
                        .comment("Zoom multiplier for the mouse wheel. Default 1, range 0.25 – 4.")
                        .component
                        .also { wheelSensitivitySpinner = it }
                }
                row("Background color") {
                    button("Choose...") { chooseBackgroundColor() }
                        .applyToComponent { backgroundColorButton = this }
                        .comment("Follows the IDE theme when unset.")
                    button("Default") {
                        backgroundColor = null
                        updateColorButton()
                    }
                }
                row("Background image") {
                    textFieldWithBrowseButton(imageDescriptor, null) { it.path }
                        .applyToComponent {
                            text = settings.editorBackgroundImage().orEmpty()
                            backgroundImageField = textField
                        }
                        .resizableColumn()
                        .comment("PNG, JPEG, GIF, WebP or BMP.")
                    button("Clear") { backgroundImageField?.text = "" }
                }
            }
        }

        updateColorButton()
        panel = root
        return root
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

    private fun chooseBackgroundColor() {
        val selected = JColorChooser.showDialog(panel, "Editor background color", backgroundColor) ?: return
        backgroundColor = Color(selected.red, selected.green, selected.blue)
        updateColorButton()
    }

    private fun updateColorButton() {
        val color = backgroundColor
        backgroundColorButton?.apply {
            icon = color?.let(::ColorSwatchIcon)
            toolTipText = color?.let { "Current color: ${colorToHex(it)}" } ?: "No custom background color"
            revalidate()
            repaint()
        }
    }

    private fun colorToHex(color: Color): String =
        "#%02X%02X%02X".format(color.red, color.green, color.blue)

    private class ColorSwatchIcon(private val color: Color) : Icon {
        private val size: Int get() = JBUI.scale(14)

        override fun getIconWidth(): Int = size

        override fun getIconHeight(): Int = size

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            g.setColor(color)
            g.fillRect(x, y, size - 1, size - 1)
            g.setColor(JBColor.border())
            g.drawRect(x, y, size - 1, size - 1)
        }
    }

    private companion object {
        const val WHEEL_SENSITIVITY_STEP = 0.25
    }
}
