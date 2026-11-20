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
}

interface HostMessage {
  type: 'ready' | 'documentChanged' | 'selectionChanged' | 'error' | 'status'
  markdown?: string
  selectedText?: string
  selection?: TSelectionState | null
  toc?: unknown
  message?: string
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
    focus: () => void
  }
}

const hostWindow = window as HostWindow
let editor: Muya | null = null
let applyingHostDocument = false
let imagePreview: ImagePreview | null = null
let smoothWheelScroll: SmoothWheelScrollController | null = null

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
      if (href) window.open(href, '_blank', 'noopener,noreferrer')
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

  if (payload.selection) editor.setSelection(payload.selection)
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
}

function start(): void {
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
