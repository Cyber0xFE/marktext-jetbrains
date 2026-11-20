package com.marktext.jetbrains.editor.structure

/**
 * 从 markdown 文本解析出的标题大纲节点。
 */
internal data class MarkdownHeading(
    val level: Int,
    val text: String,
    val line: Int,
)

/**
 * 解析 markdown 文本中的标题层级（ATX `#` 与 Setext `===`/`---`），
 * 排除围栏代码块内的伪标题，供 Structure 视图使用。
 */
internal object MarkdownOutlineParser {

    private val atxRegex = Regex("""^ {0,3}(#{1,6})(?:[ \t]+(.*?))?[ \t]*$""")
    private val setextRegex = Regex("""^ {0,3}(=+|-+)[ \t]*$""")
    private val fenceRegex = Regex("""^ {0,3}(`{3,}|~{3,})""")

    fun parse(text: String): List<MarkdownHeading> {
        val headings = ArrayList<MarkdownHeading>()
        val lines = text.split('\n')
        var fenceChar: Char? = null
        var fenceLength = 0
        var paragraphLine: String? = null
        var paragraphIndex = -1

        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()

            if (fenceChar != null) {
                if (trimmed.isNotEmpty() && trimmed[0] == fenceChar
                    && trimmed.count { it == fenceChar } >= fenceLength
                ) {
                    fenceChar = null
                    fenceLength = 0
                }
                paragraphLine = null
                continue
            }

            val fence = fenceRegex.find(line)
            if (fence != null) {
                val token = fence.groupValues[1]
                fenceChar = token[0]
                fenceLength = token.length
                paragraphLine = null
                continue
            }

            val atx = atxRegex.matchEntire(trimmed)
            if (atx != null) {
                val level = atx.groupValues[1].length
                val content = atx.groupValues[2]
                headings.add(MarkdownHeading(level, cleanInline(content), index))
                paragraphLine = null
                continue
            }

            val setext = setextRegex.matchEntire(trimmed)
            if (setext != null) {
                val level = if (setext.groupValues[1][0] == '=') 1 else 2
                if (paragraphLine != null) {
                    headings.add(MarkdownHeading(level, cleanInline(paragraphLine), paragraphIndex))
                }
                paragraphLine = null
                continue
            }

            paragraphLine = if (isParagraphLine(trimmed)) trimmed else null
            if (paragraphLine != null) paragraphIndex = index
        }
        return headings
    }

    private fun isParagraphLine(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith(">")) return false
        if (trimmed.matches(Regex("""^[-+*](\s|$)"""))) return false
        if (trimmed.matches(Regex("""^\d+[.)](\s|$)"""))) return false
        return true
    }

    /**
     * 清洗标题中的 inline 语法，使其与 Muya 渲染后的标题纯文本一致，
     * 便于 Structure 视图点击节点时在 web 端定位到对应标题。
     */
    private fun cleanInline(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("""\s+#+\s*$"""), "")
        s = s.replace(Regex("""!\[([^\]\[]*)\]\([^)]*\)"""), "$1")
        s = s.replace(Regex("""\[([^\]\[]*)\]\([^)]*\)"""), "$1")
        s = s.replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
        s = s.replace(Regex("""\*([^*]+)\*"""), "$1")
        s = s.replace(Regex("""__([^_]+)__"""), "$1")
        s = s.replace(Regex("""_([^_]+)_"""), "$1")
        s = s.replace(Regex("""~~([^~]+)~~"""), "$1")
        s = s.replace(Regex("""`([^`]+)`"""), "$1")
        s = s.replace(Regex("""\\([\\`*_{}\[\]()#+\-.!~])"""), "$1")
        return s.trim()
    }
}
