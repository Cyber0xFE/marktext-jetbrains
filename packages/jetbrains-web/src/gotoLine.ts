import type { Muya } from '@muyajs/core'
import { blockStartLines, topLevelBlocks } from './lineNumbers'

/**
 * 跳转到 Markdown 源码的指定行（从 1 开始）。
 *
 * Muya 渲染的是与 IDE 文档完全相同的 markdown，行号栏展示的也是源码行号，
 * 因此直接按源码行定位：先把光标落到该行行首，再把该行所属的顶层块滚到视口中央。
 *
 * @returns 光标是否成功落位；即使落位失败（例如只读文档），视图仍会滚到目标块。
 */
export function gotoSourceLine(editor: Muya, line: number): boolean {
  if (!Number.isFinite(line)) return false

  const markdown = editor.getMarkdown()
  // 与 IDE 的 Document.getLineCount() 一致：末尾的换行也占一行。
  const lineCount = markdown.split('\n').length
  const targetLine = Math.min(Math.max(Math.round(line), 1), lineCount)

  const applied = editor.setCursorByOffset({
    anchor: { line: targetLine - 1, ch: 0 },
    focus: { line: targetLine - 1, ch: 0 }
  })

  scrollToLine(editor, markdown, targetLine)
  return applied
}

/**
 * 目标行可能落在块内部（空行、段落的续行、代码块的中间行），
 * 因此取起始行不超过它的最后一个顶层块。
 */
function scrollToLine(editor: Muya, markdown: string, line: number): void {
  const blocks = topLevelBlocks(editor.domNode)
  const starts = blockStartLines(markdown, editor.options)
  const count = Math.min(starts.length, blocks.length)
  if (count === 0) return

  let index = 0
  for (let i = 1; i < count; i += 1) {
    if (starts[i] <= line) index = i
  }
  blocks[index]?.scrollIntoView({ block: 'center' })
}
