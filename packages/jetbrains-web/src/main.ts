import type { IMuyaOptions, TSelectionState } from '@muyajs/core'
import {
  CodeBlockLanguageSelector,
  EmojiSelector,
  FootnoteTool,
  ImageEditTool,
  ImageResizeBar,
  ImageToolBar,
  InlineFormatToolbar,
  LinkTools,
  Muya,
  ParagraphFrontButton,
  ParagraphFrontMenu,
  ParagraphQuickInsertMenu,
  PreviewToolBar,
  TableChessboard,
  TableColumnToolbar,
  TableDragBar,
  TableRowColumMenu,
  en
} from '@muyajs/core'

import { ImagePreview } from './imagePreview'
import { installSmoothWheelScroll, type SmoothWheelScrollController } from './smoothWheelScroll'
import './style.css'

interface DocumentPayload {
  markdown: string
  dirname?: string
  wheelSensitivity?: number
  editorBackgroundColor?: string | null
  editorBackgroundImageUrl?: string | null
  readOnly?: boolean
  options?: Partial<IMuyaOptions>
  selection?: TSelectionState | null
  scrollTop?: number
}

interface HostMessage {
  type:
    | 'ready'
    | 'documentChanged'
    | 'selectionChanged'
    | 'scrollPositionChanged'
    | 'viewportHeadingChanged'
    | 'error'
    | 'status'
    | 'openLink'
  markdown?: string
  selectedText?: string
  headingText?: string
  headingIndex?: number
  scrollTop?: number
  selection?: TSelectionState | null
  toc?: unknown
  message?: string
  href?: string
}

interface HostWindow extends Window {
  __marktextHostPostMessage?: (message: string) => void
  DIRNAME?: string
  marktextHost?: {
    loadDocument: (payload: DocumentPayload) => void
    setReadOnly: (readOnly: boolean) => void
    setOptions: (options: Partial<IMuyaOptions>) => void
    setWheelSensitivity: (sensitivity: number) => void
    setEditorBackground: (background: { color?: string | null; imageUrl?: string | null }) => void
    scrollToHeading: (text: string) => void
    history: (action: 'undo' | 'redo') => void
    focus: () => void
  }
}

const hostWindow = window as HostWindow
let editor: Muya | null = null
let applyingHostDocument = false
let imagePreview: ImagePreview | null = null
let smoothWheelScroll: SmoothWheelScrollController | null = null
let lastReportedScrollTop = -1
let scrollReportTimer: number | null = null
let restoringScroll = false

function sendMessage(message: HostMessage): void {
  const serialized = JSON.stringify(message)
  if (hostWindow.__marktextHostPostMessage) {
    hostWindow.__marktextHostPostMessage(serialized)
    return
  }

  window.parent.postMessage({ source: 'marktext-jetbrains-web', ...message }, '*')
}

function registerPlugins(): void {
  Muya.use(CodeBlockLanguageSelector)
  Muya.use(EmojiSelector)
  Muya.use(FootnoteTool)
  Muya.use(ImageEditTool, {
    imageAction: async ({ src }: { src: string }) => src
  })
  Muya.use(ImageResizeBar)
  Muya.use(ImageToolBar)
  Muya.use(InlineFormatToolbar)
  Muya.use(LinkTools, {
    jumpClick: (linkInfo: { href?: string } | null) => {
      const href = linkInfo?.href
      if (href) sendMessage({ type: 'openLink', href })
    }
  })
  Muya.use(ParagraphFrontButton)
  Muya.use(ParagraphFrontMenu)
  Muya.use(ParagraphQuickInsertMenu)
  Muya.use(PreviewToolBar)
  Muya.use(TableChessboard)
  Muya.use(TableColumnToolbar)
  Muya.use(TableDragBar)
  Muya.use(TableRowColumMenu)
}

function destroyEditor(): void {
  imagePreview?.close()
  smoothWheelScroll?.dispose()
  smoothWheelScroll = null
  if (scrollReportTimer !== null) {
    window.clearTimeout(scrollReportTimer)
    scrollReportTimer = null
  }
  lastReportedScrollTop = -1
  restoringScroll = false
  lastReportedKey = ''
  if (!editor) return
  editor.destroy()
  editor = null
}

function setEditorBackground(color?: string | null, imageUrl?: string | null): void {
  document.body.style.backgroundColor = color ?? ''
  document.body.style.backgroundImage = imageUrl ? `url("${imageUrl}")` : ''
  document.body.style.backgroundPosition = imageUrl ? 'center' : ''
  document.body.style.backgroundRepeat = imageUrl ? 'no-repeat' : ''
  document.body.style.backgroundSize = imageUrl ? 'cover' : ''
  document.body.style.backgroundAttachment = imageUrl ? 'fixed' : ''
}

function createEditor(payload: DocumentPayload): void {
  setEditorBackground(payload.editorBackgroundColor, payload.editorBackgroundImageUrl)
  hostWindow.DIRNAME = payload.dirname ?? ''
  const container = document.querySelector<HTMLElement>('#editor')
  if (!container) {
    sendMessage({ type: 'error', message: 'Muya container was not found' })
    return
  }

  destroyEditor()
  container.replaceChildren()
  container.classList.remove('muya-starting')
  registerPlugins()

  sendMessage({ type: 'status', message: 'Muya plugins registered' })

  const options: Partial<IMuyaOptions> = {
    ...payload.options,
    markdown: payload.markdown,
    readOnly: payload.readOnly === true,
    locale: en
  }

  try {
    editor = new Muya(container, options)
    editor.init({ focus: payload.readOnly !== true })
    smoothWheelScroll = installSmoothWheelScroll(editor.domNode, payload.wheelSensitivity)
    imagePreview ??= new ImagePreview()
    sendMessage({ type: 'status', message: 'Muya initialized' })
    // 打开文件时视口停在顶部，没有 scroll 事件，主动上报一次以完成初始同步。
    scheduleViewportHeadingReport()
  } catch (error) {
    destroyEditor()
    const detail = error instanceof Error ? error.stack ?? error.message : String(error)
    container.textContent = `MarkText could not initialize: ${detail}`
    container.classList.add('muya-startup-error')
    sendMessage({ type: 'error', message: detail })
    return
  }

  editor.on('json-change', () => {
    if (applyingHostDocument || !editor) return
    editor.flush()
    sendMessage({
      type: 'documentChanged',
      markdown: editor.getMarkdown(),
      selectedText: editor.getSelectedText(),
      selection: editor.getSelectionState(),
      toc: editor.getTOC()
    })
  })
  editor.on('preview-image', ({ data }: { data: string }) => {
    imagePreview?.open(data)
  })
  editor.on('selection-change', () => {
    if (!editor) return
    sendMessage({
      type: 'selectionChanged',
      selectedText: editor.getSelectedText(),
      selection: editor.getSelectionState()
    })
  })
  editor.on('format-click', (payload: { formatType?: string; data?: { href?: string | null } | string }) => {
    if (payload?.formatType !== 'link') return
    const data = payload.data
    const href = typeof data === 'object' && data !== null ? data.href : null
    if (href) sendMessage({ type: 'openLink', href })
  })

  if (payload.selection) editor.setSelection(payload.selection)
  else restoreScrollPosition(payload.scrollTop)
}

function loadDocument(payload: DocumentPayload): void {
  setEditorBackground(payload.editorBackgroundColor, payload.editorBackgroundImageUrl)
  hostWindow.DIRNAME = payload.dirname ?? ''
  if (!editor) {
    createEditor(payload)
    return
  }

  applyingHostDocument = true
  try {
    editor.setContent(payload.markdown)
    if (payload.selection) editor.setSelection(payload.selection)
    if (typeof payload.readOnly === 'boolean') editor.setReadOnly(payload.readOnly)
    if (payload.options) editor.setOptions(payload.options, true)
    if (typeof payload.wheelSensitivity === 'number') smoothWheelScroll?.setSensitivity(payload.wheelSensitivity)
  } finally {
    applyingHostDocument = false
  }

  if (!payload.selection) restoreScrollPosition(payload.scrollTop)
}

/**
 * 收集文档顶层的标题元素。
 *
 * 标题也可以出现在引用块/列表里，但 markdown 大纲只收录顶层标题，
 * 所以这里同样过滤掉嵌套容器内的标题，保证两侧标题集合一致，
 * 否则嵌套标题会抢走同步位置（甚至永远匹配不到）。
 */
function topLevelHeadingElements(): HTMLElement[] {
  if (!editor) return []
  return Array.from(editor.domNode.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6'))
    .filter((heading) => !heading.closest('.mu-block-quote, .mu-bullet-list, .mu-order-list'))
}

function scrollToHeading(text: string): void {
  const headings = topLevelHeadingElements()
  const normalize = (value: string) => value.trim().replace(/\s+/g, ' ').toLocaleLowerCase()
  const normalized = normalize(text)
  const exact = headings.find((element) => normalize(element.textContent ?? '') === normalized)
  const target = exact
    ?? headings.find((element) => normalize(element.textContent ?? '').includes(normalized))
  // 跳转后保持阅读进度一致，避免结构视图在目标标题与原文之间来回选中
  if (target) markReported(headings.indexOf(target), (target.textContent ?? '').trim())
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const VIEWPORT_REPORT_INTERVAL_MS = 120
const SCROLL_REPORT_INTERVAL_MS = 250
let viewportReportTimer: number | null = null
let viewportReportFrame: number | null = null
let lastReportedKey = ''

function markReported(index: number, text: string): void {
  lastReportedKey = `${index}:${text}`
}

/**
 * 找出当前视口顶部所在的标题：取最后一个顶部已越过激活线的标题。
 *
 * 激活线不能紧贴视口上沿（那样要等标题完全滚出去才切换，阅读时会滞后一整屏），
 * 而是放在视口靠上的位置：取视口高度的 20%（上限 120px），
 * 这样刚进入视口上部的标题就会成为当前章节。
 *
 * 同时返回顶层标题中的索引：两端解析规则差异（inline 语法、setext 等）
 * 可能导致文本不完全一致，索引可以用作兜底定位。
 */
function currentViewportHeading(): { index: number; text: string } {
  const headings = topLevelHeadingElements()
  if (!editor || headings.length === 0) return { index: -1, text: '' }

  const rootTop = Math.max(0, editor.domNode.getBoundingClientRect().top)
  const rootHeight = editor.domNode.clientHeight || window.innerHeight
  const activationLine = rootTop + Math.min(rootHeight * 0.2, 120)
  let current: HTMLElement | null = null
  for (const heading of headings) {
    if (heading.getBoundingClientRect().top <= activationLine) current = heading
    else break
  }
  if (!current) return { index: -1, text: '' }
  return { index: headings.indexOf(current), text: (current.textContent ?? '').trim() }
}

function reportViewportHeading(): void {
  const { index, text } = currentViewportHeading()
  if (index < 0 || text === '') return
  const key = `${index}:${text}`
  if (key === lastReportedKey) return
  lastReportedKey = key
  sendMessage({ type: 'viewportHeadingChanged', headingText: text, headingIndex: index })
}

/** 滚动事件非常密集，先合并到一帧，再按固定间隔节流后上报。 */
function scheduleViewportHeadingReport(): void {
  if (viewportReportFrame !== null || viewportReportTimer !== null) return
  viewportReportFrame = window.requestAnimationFrame(() => {
    viewportReportFrame = null
    viewportReportTimer = window.setTimeout(() => {
      viewportReportTimer = null
      reportViewportHeading()
    }, VIEWPORT_REPORT_INTERVAL_MS)
  })
}

/** 浏览器位置按像素保存：滚动容器就是 Muya 替换出来的 `.mu-editor` 节点。 */
function currentScrollTop(): number {
  if (!editor) return 0
  return Math.max(0, Math.round(editor.domNode.scrollTop))
}

/**
 * 恢复上次关闭文件时的滚动位置。
 *
 * `setContent` 会重建 DOM 并把视口拉回顶部，且 `.mu-editor` 的最终高度要等下一帧才稳定，
 * 所以放到帧回调里再设置；恢复期间抑制上报，避免把恢复动作当成新的浏览位置。
 */
function restoreScrollPosition(scrollTop: number | undefined): void {
  if (!editor || typeof scrollTop !== 'number' || !Number.isFinite(scrollTop) || scrollTop <= 0) return
  const container = editor.domNode
  const target = Math.round(scrollTop)
  let attempts = 0
  restoringScroll = true

  const apply = (): void => {
    if (!editor) {
      restoringScroll = false
      return
    }
    const limit = Math.max(0, container.scrollHeight - container.clientHeight)
    container.scrollTop = Math.min(target, limit)
    // 图片、字体等还没把高度撑到目标位置时，下一帧再试一次。
    if (container.scrollTop < target && attempts < 3) {
      attempts += 1
      window.setTimeout(apply, 150)
      return
    }
    lastReportedScrollTop = Math.round(container.scrollTop)
    restoringScroll = false
  }

  window.requestAnimationFrame(apply)
}

function reportScrollPosition(): void {
  if (!editor || restoringScroll) return
  const scrollTop = currentScrollTop()
  if (scrollTop === lastReportedScrollTop) return
  lastReportedScrollTop = scrollTop
  sendMessage({ type: 'scrollPositionChanged', scrollTop })
}

/** 滚动停下后再上报，避免滚动过程中频繁写回 IDE。 */
function scheduleScrollPositionReport(): void {
  if (scrollReportTimer !== null) return
  scrollReportTimer = window.setTimeout(() => {
    scrollReportTimer = null
    reportScrollPosition()
  }, SCROLL_REPORT_INTERVAL_MS)
}

function start(): void {
  // scroll 事件不冒泡，所以用捕获阶段统一监听所有滚动容器（Muya 内部滚动容器可能变化）。
  document.addEventListener('scroll', () => {
    scheduleViewportHeadingReport()
    scheduleScrollPositionReport()
  }, { capture: true, passive: true })
  document.addEventListener('click', (event) => {
    const target = event.target
    if (!(target instanceof Element)) return
    if (target.closest('.mu-image-icon-close, .mu-image-icon-success, .mu-image-icon-fail')) return
    const image = target.closest<HTMLImageElement>('img')
      ?? target.closest<HTMLElement>('.mu-inline-image')?.querySelector<HTMLImageElement>('img')
    if (!image || !target.closest('.mu-editor')) return
    const src = image.currentSrc || image.src
    if (!src) return
    event.preventDefault()
    event.stopImmediatePropagation()
    imagePreview?.open(src)
  }, true)

  hostWindow.marktextHost = {
    loadDocument,
    setReadOnly: (readOnly) => editor?.setReadOnly(readOnly),
    setOptions: (options) => editor?.setOptions(options, true),
    setWheelSensitivity: (sensitivity) => smoothWheelScroll?.setSensitivity(sensitivity),
    setEditorBackground: (background) => setEditorBackground(background.color, background.imageUrl),
    scrollToHeading,
    history: (action: 'undo' | 'redo') => {
      if (action === 'redo') editor?.redo()
      else if (action === 'undo') editor?.undo()
    },
    focus: () => editor?.focus()
  }

  sendMessage({ type: 'status', message: 'Web bundle started' })
  createEditor({ markdown: '# MarkText', readOnly: true })
  sendMessage({ type: 'ready' })
}

window.addEventListener('error', (event) => {
  sendMessage({ type: 'error', message: event.error?.stack ?? event.message })
})

window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  sendMessage({ type: 'error', message: reason instanceof Error ? reason.stack ?? reason.message : String(reason) })
})

start()
