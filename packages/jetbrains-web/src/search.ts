import type { Muya } from '@muyajs/core'
import type { Search } from '@muyajs/core/search'

/** 由 IDE 内置查找栏下发的检索条件，对应 FindModel 的大小写 / 全词 / 正则开关。 */
export interface SearchRequest {
  query?: string
  caseSensitive?: boolean
  wholeWord?: boolean
  regexp?: boolean
}

export interface SearchBridge {
  /** 执行一次检索（查询为空时清除高亮）。 */
  search: (request: SearchRequest) => void
  findNext: () => void
  findPrevious: () => void
  /** 关闭查找栏：清除高亮并丢弃 Muya 的查询状态。 */
  clear: () => void
  dispose: () => void
}

/**
 * IDE 内置查找栏（SearchReplaceComponent）在宿主侧的检索实现。
 *
 * 查找栏 UI 完全由 IDE 提供，这里只负责把 FindModel 转成 Muya 的检索条件，
 * 并把匹配数量 / 当前序号回传给宿主用于显示 "n / total"。
 */
export function installSearchBridge(
  editor: Muya,
  report: (total: number, index: number) => void
): SearchBridge {
  let active = false
  let query = ''
  let caseSensitive = false
  let wholeWord = false
  let regexp = false
  let matchCount = 0
  let activeIndex = -1

  const searchOptions = (highlightIndex?: number): Parameters<Muya['search']>[1] => ({
    isCaseSensitive: caseSensitive,
    isWholeWord: wholeWord,
    isRegexp: regexp,
    // 显式定位到目标匹配：查找栏的焦点在原生控件里，Muya 的 DOM 选区不一定可信，
    // 只用 find() 的选区推断会让"下一个"原地不动。
    selectHighlight: true,
    ...(typeof highlightIndex === 'number' ? { highlightIndex } : {})
  })

  /**
   * 把视口滚到当前匹配。
   *
   * Muya 只渲染高亮、不负责滚动：搜索命中的 active 项带 `mu-highlight` 类
   * （其余匹配是 `mu-selection`），据此定位到视口中央。
   */
  const revealActiveMatch = (): void => {
    const active = editor.domNode.querySelector<HTMLElement>('span.mu-highlight')
    if (!active?.isConnected) return
    active.scrollIntoView({ block: 'center', inline: 'nearest' })
  }

  const reportResult = (result: Search): void => {
    matchCount = result.matches.length
    activeIndex = result.index
    if (active) report(matchCount, activeIndex)
  }

  const runSearch = (): void => {
    if (!query) {
      editor.search('')
      matchCount = 0
      activeIndex = -1
      report(0, -1)
      return
    }
    reportResult(editor.search(query, searchOptions()))
    revealActiveMatch()
  }

  const search = (request: SearchRequest): void => {
    active = true
    query = request.query ?? ''
    caseSensitive = request.caseSensitive === true
    wholeWord = request.wholeWord === true
    regexp = request.regexp === true
    runSearch()
  }

  /** 按序号偏移跳转（首尾循环）；每次重新检索以便编辑后序号仍然准确。 */
  const step = (delta: number): void => {
    if (!active || !query || matchCount === 0) return
    const from = activeIndex >= 0 ? activeIndex : 0
    const target = (from + delta + matchCount) % matchCount
    reportResult(editor.search(query, searchOptions(target)))
    revealActiveMatch()
  }

  const findNext = (): void => step(1)

  const findPrevious = (): void => step(-1)

  const clear = (): void => {
    active = false
    editor.search('')
  }

  // 查找栏打开期间编辑文档，Muya 会重算匹配；把新的数量 / 序号同步回查找栏。
  const onSearchRefreshed = (result: Search): void => {
    reportResult(result)
  }
  editor.on('search-refreshed', onSearchRefreshed)

  const dispose = (): void => {
    active = false
    editor.off('search-refreshed', onSearchRefreshed)
  }

  return { search, findNext, findPrevious, clear, dispose }
}
