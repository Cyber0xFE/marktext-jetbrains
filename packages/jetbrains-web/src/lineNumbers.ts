import type { IMuyaOptions } from '@muyajs/core'
import { lexBlock } from '@muyajs/core/utils/marked'

/** 顶层块携带的标记类：用于把块变成行号的定位容器。 */
const BLOCK_CLASS = 'marktext-line-numbered'
/** 行号元素类名。 */
const LABEL_CLASS = 'marktext-line-number'

export interface LineNumberController {
  /**
   * 按当前 markdown 源码重新标记每个顶层块的起始行号。
   *
   * @param markdown 与 IDE 文档一致的 markdown 源码
   * @param options 当前 Muya 选项，用于与编辑器保持同样的块解析规则
   */
  refresh: (markdown: string, options: IMuyaOptions) => void
  setVisible: (visible: boolean) => void
  dispose: () => void
}

/**
 * 计算顶层块在 markdown 源码中的起始行号（从 1 开始）。
 *
 * Muya 的 state 不保存源码位置，这里用与 Muya 相同的 lexer 重新切分顶层块，
 * 再按顺序在源码里定位每个 token 的 raw 文本。token 顺序与渲染出的顶层块顺序一致，
 * 因此行号可以逐个对上。
 */
export function blockStartLines(markdown: string, options: IMuyaOptions): number[] {
  const lineStarts = [0]
  for (let index = 0; index < markdown.length; index += 1) {
    if (markdown.charCodeAt(index) === 10) lineStarts.push(index + 1)
  }

  const lineAt = (offset: number): number => {
    let low = 0
    let high = lineStarts.length - 1
    while (low < high) {
      const middle = Math.ceil((low + high) / 2)
      if (lineStarts[middle] <= offset) low = middle
      else high = middle - 1
    }
    return low + 1
  }

  const tokens = lexBlock(markdown, {
    footnote: options.footnote,
    texMathDollars: options.texMathDollars,
    texMathGfm: options.texMathGfm,
    texMathSingleBackslash: options.texMathSingleBackslash,
    texMathDoubleBackslash: options.texMathDoubleBackslash,
    frontMatter: options.frontMatter,
    multilineBlockquote: options.multilineBlockquote,
    githubAlerts: options.githubAlerts,
    fancyLists: options.fancyLists
  })

  const starts: number[] = []
  let searchFrom = 0
  for (const token of tokens) {
    const raw = typeof token.raw === 'string' ? token.raw : ''
    let offset = searchFrom
    if (raw) {
      const found = markdown.indexOf(raw, searchFrom)
      if (found >= 0) {
        offset = found
        searchFrom = found + raw.length
      }
    }
    // `space` token 只是块之间的空行，Muya 会丢弃它们，不对应任何渲染块。
    if (token.type !== 'space') starts.push(lineAt(offset))
  }
  return starts
}

/**
 * 收集 `.mu-container` 的直接子块，即渲染视图里的顶层块。
 *
 * 顶层块与 markdown 的顶层 token 一一对应，行号与「跳转到行」都依赖这个顺序。
 */
export function topLevelBlocks(domNode: HTMLElement): HTMLElement[] {
  const root = domNode.querySelector<HTMLElement>('.mu-container')
  if (!root) return []
  return Array.from(root.children).filter((child): child is HTMLElement => child instanceof HTMLElement)
}

/**
 * 在编辑器左侧渲染 markdown 源码行号。
 *
 * 行号以绝对定位的 span 插入每个顶层块，随块一起滚动，不需要在滚动时重算位置。
 * 只在顶层的 `.mu-container` 直接子块上标记，列表/引用内部不重复显示。
 */
export function installLineNumbers(domNode: HTMLElement): LineNumberController {
  let visible = true
  let markdown = ''
  let options: IMuyaOptions | null = null

  const container = (): HTMLElement | null => domNode.querySelector<HTMLElement>('.mu-container')

  const clear = (): void => {
    const root = container()
    if (!root) return
    root.querySelectorAll<HTMLElement>(`:scope > .${LABEL_CLASS}`).forEach((label) => label.remove())
    topLevelBlocks(domNode).forEach((block) => {
      block.classList.remove(BLOCK_CLASS)
      block.querySelectorAll<HTMLElement>(`:scope > .${LABEL_CLASS}`).forEach((label) => label.remove())
    })
  }

  const apply = (): void => {
    clear()
    if (!visible || !options || markdown === '') return

    const blocks = topLevelBlocks(domNode)
    if (blocks.length === 0) return

    const starts = blockStartLines(markdown, options)
    const count = Math.min(starts.length, blocks.length)
    for (let index = 0; index < count; index += 1) {
      const block = blocks[index]
      const line = starts[index]
      const label = document.createElement('span')
      label.className = LABEL_CLASS
      label.textContent = String(line)
      label.setAttribute('aria-hidden', 'true')
      label.setAttribute('contenteditable', 'false')
      block.classList.add(BLOCK_CLASS)
      block.appendChild(label)
    }
  }

  const refresh = (nextMarkdown: string, nextOptions: IMuyaOptions): void => {
    markdown = nextMarkdown
    options = nextOptions
    apply()
  }

  const setVisible = (nextVisible: boolean): void => {
    visible = nextVisible
    apply()
  }

  const dispose = (): void => {
    markdown = ''
    options = null
    clear()
  }

  return { refresh, setVisible, dispose }
}
