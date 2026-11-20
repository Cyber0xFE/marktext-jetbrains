const MIN_SCALE = 0.1
const MAX_SCALE = 16
const ZOOM_STEP = 1.2
const ZOOM_RESPONSE_MS = 80
const TOOLBAR_HIDE_DELAY_MS = 2400

export class ImagePreview {
  private readonly root: HTMLDivElement
  private readonly stage: HTMLDivElement
  private readonly image: HTMLImageElement
  private readonly toolbar: HTMLDivElement
  private readonly zoomLabel: HTMLButtonElement
  private scale = 1
  private translateX = 0
  private translateY = 0
  private zoomFrame: number | null = null
  private zoomTarget = 1
  private toolbarHideTimer: number | null = null
  private dragging = false
  private dragStartX = 0
  private dragStartY = 0
  private dragOriginX = 0
  private dragOriginY = 0

  constructor() {
    this.root = document.createElement('div')
    this.root.className = 'marktext-image-preview'
    this.root.setAttribute('role', 'dialog')
    this.root.setAttribute('aria-modal', 'true')
    this.root.setAttribute('aria-label', 'Image preview')
    this.root.tabIndex = -1
    this.root.hidden = true

    const closeButton = this.createButton('×', 'Close image preview', 'close')
    closeButton.classList.add('marktext-image-preview-close')
    this.toolbar = document.createElement('div')
    this.toolbar.className = 'marktext-image-preview-toolbar'
    this.toolbar.append(
      this.createButton('−', 'Zoom out', 'zoom-out'),
      this.zoomLabel = this.createButton('100%', 'Reset image size', 'actual-size'),
      this.createButton('+', 'Zoom in', 'zoom-in'),
      this.createButton('Fit', 'Fit image to window', 'fit')
    )

    this.stage = document.createElement('div')
    this.stage.className = 'marktext-image-preview-stage'
    this.image = document.createElement('img')
    this.image.alt = ''
    this.image.draggable = false
    this.stage.appendChild(this.image)
    this.root.append(closeButton, this.toolbar, this.stage)
    document.body.appendChild(this.root)

    closeButton.addEventListener('click', () => this.close())
    this.root.addEventListener('click', (event) => {
      if (event.target === this.root || event.target === this.stage) this.close()
    })
    this.root.addEventListener('keydown', (event) => {
      event.stopPropagation()
      if (event.key === 'Escape') {
        event.preventDefault()
        this.close()
      }
    })
    this.root.addEventListener('wheel', (event) => {
      event.preventDefault()
      this.showToolbar()
      const factor = event.deltaY < 0 ? ZOOM_STEP : 1 / ZOOM_STEP
      this.zoomTarget = Math.min(MAX_SCALE, Math.max(MIN_SCALE, this.zoomTarget * factor))
      this.animateZoom()
    }, { passive: false })
    this.root.addEventListener('pointerdown', (event) => this.startDrag(event))
    window.addEventListener('pointermove', (event) => this.moveDrag(event))
    window.addEventListener('pointerup', () => this.stopDrag())
    this.image.addEventListener('load', () => this.fit())

    this.toolbar.addEventListener('click', (event) => {
      event.stopPropagation()
      const target = event.target
      if (!(target instanceof HTMLButtonElement)) return
      this.showToolbar()
      switch (target.dataset.action) {
        case 'zoom-out':
          this.zoom(1 / 1.25)
          break
        case 'zoom-in':
          this.zoom(1.25)
          break
        case 'actual-size':
          this.setTransform(1, 0, 0)
          this.zoomTarget = 1
          break
        case 'fit':
          this.fit()
          break
      }
    })
  }

  open(src: string): void {
    if (!src) return
    this.image.src = src
    this.root.hidden = false
    this.hideToolbar()
    this.setTransform(1, 0, 0)
    this.zoomTarget = 1
    this.root.focus()
    requestAnimationFrame(() => this.fit())
  }

  close(): void {
    this.root.hidden = true
    this.hideToolbar()
    this.image.removeAttribute('src')
    if (this.zoomFrame !== null) window.cancelAnimationFrame(this.zoomFrame)
    this.zoomFrame = null
    this.stopDrag()
  }

  destroy(): void {
    this.hideToolbar()
    this.stopDrag()
    this.root.remove()
  }

  private createButton(text: string, label: string, action: string): HTMLButtonElement {
    const button = document.createElement('button')
    button.type = 'button'
    button.textContent = text
    button.title = label
    button.setAttribute('aria-label', label)
    button.dataset.action = action
    return button
  }

  private showToolbar(): void {
    this.root.classList.add('is-zooming')
    if (this.toolbarHideTimer !== null) window.clearTimeout(this.toolbarHideTimer)
    this.toolbarHideTimer = window.setTimeout(() => {
      this.toolbarHideTimer = null
      this.root.classList.remove('is-zooming')
    }, TOOLBAR_HIDE_DELAY_MS)
  }

  private hideToolbar(): void {
    if (this.toolbarHideTimer !== null) window.clearTimeout(this.toolbarHideTimer)
    this.toolbarHideTimer = null
    this.root.classList.remove('is-zooming')
  }

  private zoom(factor: number): void {
    this.setTransform(this.scale * factor, this.translateX, this.translateY)
    this.zoomTarget = this.scale
  }

  private fit(): void {
    if (!this.image.naturalWidth || !this.image.naturalHeight) return
    const width = this.stage.clientWidth - 48
    const height = this.stage.clientHeight - 48
    const scale = Math.min(1, width / this.image.naturalWidth, height / this.image.naturalHeight)
    this.setTransform(scale, 0, 0)
    this.zoomTarget = scale
  }

  private setTransform(scale: number, translateX: number, translateY: number): void {
    this.scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, scale))
    this.translateX = translateX
    this.translateY = translateY
    this.image.style.transform = `translate(${translateX}px, ${translateY}px) scale(${this.scale})`
    this.zoomLabel.textContent = `${Math.round(this.scale * 100)}%`
  }

  private animateZoom(): void {
    if (this.zoomFrame !== null) return
    let previousTime = performance.now()

    const animate = (now: number): void => {
      const elapsed = Math.max(0, now - previousTime)
      previousTime = now
      const remaining = this.zoomTarget - this.scale
      const nextScale = Math.abs(remaining) <= 0.001
        ? this.zoomTarget
        : this.scale + remaining * (1 - Math.exp(-elapsed / ZOOM_RESPONSE_MS))
      this.setTransform(nextScale, this.translateX, this.translateY)

      if (Math.abs(this.zoomTarget - nextScale) <= 0.001) {
        this.setTransform(this.zoomTarget, this.translateX, this.translateY)
        this.zoomFrame = null
        return
      }
      this.zoomFrame = window.requestAnimationFrame(animate)
    }

    this.zoomFrame = window.requestAnimationFrame(animate)
  }

  private startDrag(event: PointerEvent): void {
    if (event.button !== 0 || event.target !== this.image) return
    this.dragging = true
    this.dragStartX = event.clientX
    this.dragStartY = event.clientY
    this.dragOriginX = this.translateX
    this.dragOriginY = this.translateY
    this.stage.classList.add('is-dragging')
    this.image.setPointerCapture?.(event.pointerId)
    event.preventDefault()
  }

  private moveDrag(event: PointerEvent): void {
    if (!this.dragging) return
    this.setTransform(
      this.scale,
      this.dragOriginX + event.clientX - this.dragStartX,
      this.dragOriginY + event.clientY - this.dragStartY
    )
  }

  private stopDrag(): void {
    this.dragging = false
    this.stage.classList.remove('is-dragging')
  }
}
