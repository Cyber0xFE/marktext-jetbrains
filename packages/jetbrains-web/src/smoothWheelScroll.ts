const WHEEL_MULTIPLIER = 2.5
const RESPONSE_MS = 80
const EDGE_TOLERANCE = 1

export interface SmoothWheelScrollController {
  setSensitivity: (sensitivity: number) => void
  dispose: () => void
}

type Axis = 'x' | 'y'

function offset(element: HTMLElement, axis: Axis): number {
  return axis === 'x' ? element.scrollLeft : element.scrollTop
}

function limit(element: HTMLElement, axis: Axis): number {
  const span = axis === 'x'
    ? element.scrollWidth - element.clientWidth
    : element.scrollHeight - element.clientHeight
  return Math.max(0, span)
}

function scrollableSurface(
  root: HTMLElement,
  target: EventTarget | null,
  delta: number,
  axis: Axis,
): HTMLElement | null {
  let element = target instanceof HTMLElement
    ? target
    : target instanceof Element ? target.parentElement : root

  while (element && element !== root) {
    const span = limit(element, axis)
    if (span > 0) {
      const style = window.getComputedStyle(element)
      const overflow = axis === 'x' ? style.overflowX : style.overflowY
      if (/^(auto|scroll)$/.test(overflow)) {
        const position = offset(element, axis)
        const hasRoom = delta < 0
          ? position > EDGE_TOLERANCE
          : span - position > EDGE_TOLERANCE
        if (hasRoom) return element
      }
    }
    element = element.parentElement
  }

  return limit(root, axis) > 0 ? root : null
}

export function installSmoothWheelScroll(root: HTMLElement, initialSensitivity = 1): SmoothWheelScrollController {
  let sensitivity = Math.max(0.25, Math.min(4, initialSensitivity))
  let frame: number | null = null
  let surface: HTMLElement | null = null
  let axis: Axis = 'y'
  let target = 0
  let current = 0
  let lastPosition = 0
  let lastTime = 0
  let direction = 0

  const stop = (): void => {
    if (frame !== null) window.cancelAnimationFrame(frame)
    frame = null
    surface = null
  }

  const animate = (now: number): void => {
    const activeSurface = surface
    if (!activeSurface) {
      frame = null
      return
    }

    if (Math.abs(offset(activeSurface, axis) - lastPosition) > EDGE_TOLERANCE) {
      stop()
      return
    }

    const remaining = target - current
    const elapsed = Math.max(0, now - lastTime)
    lastTime = now
    current = Math.abs(remaining) <= 0.5
      ? target
      : current + remaining * (1 - Math.exp(-elapsed / RESPONSE_MS))
    if (axis === 'x') {
      activeSurface.scrollLeft = current
    } else {
      activeSurface.scrollTop = current
    }
    lastPosition = offset(activeSurface, axis)

    if (Math.abs(target - current) <= 0.5) {
      if (axis === 'x') {
        activeSurface.scrollLeft = target
      } else {
        activeSurface.scrollTop = target
      }
      frame = null
    } else {
      frame = window.requestAnimationFrame(animate)
    }
  }

  const wheel = (event: WheelEvent): void => {
    if (event.defaultPrevented || !event.cancelable || event.ctrlKey || event.metaKey) {
      stop()
      return
    }

    // JCEF 在 Shift + 滚轮时只发送 deltaX（deltaY 为 0），并直接把 AWT 滚轮刻度
    // （每格约 3）当作像素量，所以横向位移几乎看不见；这里兼容 deltaX 事件，
    // 并把个位数整数刻度按“一行”放大到可感知的距离。
    const fromVerticalWheel = event.deltaY !== 0
    // Shift + 滚轮向下 = 向右，向上 = 向左；纯 deltaX 事件同样视为横向意图。
    // JCEF 在 Shift 时直接发送 AWT 刻度（其符号与 Chromium 的横向约定相反），
    // 因此带 Shift 的横向量取反；不带 Shift 的 deltaX（触控板）保持系统语义。
    const horizontal = event.shiftKey || !fromVerticalWheel
    const delta = fromVerticalWheel
      ? event.deltaY
      : (event.shiftKey ? -event.deltaX : event.deltaX)
    if (delta === 0) {
      stop()
      return
    }

    // Shift + 滚轮只做横向（包括滚到左右端点之后）：不回退到纵向滚动，
    // 也吞掉事件，避免浏览器把同一次滚轮手势锁定在已到端点的容器上。
    const nextAxis: Axis = horizontal ? 'x' : 'y'
    const nextSurface = scrollableSurface(root, event.target, delta, nextAxis)
    if (!nextSurface) {
      if (horizontal) event.preventDefault()
      stop()
      return
    }

    const nextDirection = Math.sign(delta)
    if (
      frame === null
      || nextSurface !== surface
      || nextAxis !== axis
      || nextDirection !== direction
      || Math.abs(offset(nextSurface, nextAxis) - lastPosition) > EDGE_TOLERANCE
    ) {
      stop()
      surface = nextSurface
      axis = nextAxis
      target = offset(nextSurface, nextAxis)
      current = target
    }

    direction = nextDirection
    // 设备滚轮一格通常只给几像素（JCEF）；触控板给小数或较大的像素量。
    const coarseTick = Number.isInteger(delta) && Math.abs(delta) <= 8
    const unit = event.deltaMode === WheelEvent.DOM_DELTA_LINE
      ? 16
      : event.deltaMode === WheelEvent.DOM_DELTA_PAGE
        ? (nextAxis === 'x' ? nextSurface.clientWidth : nextSurface.clientHeight)
        : coarseTick ? 16 : 1
    target = Math.max(0, Math.min(target + delta * unit * WHEEL_MULTIPLIER * sensitivity, limit(nextSurface, nextAxis)))
    if (target === offset(nextSurface, nextAxis) && frame === null) return

    event.preventDefault()
    if (frame === null) {
      lastPosition = offset(nextSurface, nextAxis)
      lastTime = performance.now()
      frame = window.requestAnimationFrame(animate)
    }
  }

  root.addEventListener('wheel', wheel, { passive: false })
  root.addEventListener('pointerdown', stop, true)
  root.addEventListener('keydown', stop, true)

  return {
    setSensitivity: (value) => {
      sensitivity = Math.max(0.25, Math.min(4, value))
    },
    dispose: () => {
      stop()
      root.removeEventListener('wheel', wheel)
      root.removeEventListener('pointerdown', stop, true)
      root.removeEventListener('keydown', stop, true)
    }
  }
}
