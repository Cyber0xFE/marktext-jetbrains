const WHEEL_MULTIPLIER = 2.5
const RESPONSE_MS = 80
const EDGE_TOLERANCE = 1

export interface SmoothWheelScrollController {
  setSensitivity: (sensitivity: number) => void
  dispose: () => void
}

function position(element: HTMLElement): number {
  return element.scrollTop
}

function limit(element: HTMLElement): number {
  return Math.max(0, element.scrollHeight - element.clientHeight)
}

function scrollableSurface(root: HTMLElement, target: EventTarget | null, delta: number): HTMLElement | null {
  let element = target instanceof HTMLElement
    ? target
    : target instanceof Element ? target.parentElement : root

  while (element && element !== root) {
    if (element.scrollHeight > element.clientHeight) {
      const overflowY = window.getComputedStyle(element).overflowY
      if (/^(auto|scroll)$/.test(overflowY)) {
        const offset = position(element)
        const hasRoom = delta < 0
          ? offset > EDGE_TOLERANCE
          : limit(element) - offset > EDGE_TOLERANCE
        if (hasRoom) return element
      }
    }
    element = element.parentElement
  }

  return limit(root) > 0 ? root : null
}

export function installSmoothWheelScroll(root: HTMLElement, initialSensitivity = 1): SmoothWheelScrollController {
  let sensitivity = Math.max(0.25, Math.min(4, initialSensitivity))
  let frame: number | null = null
  let surface: HTMLElement | null = null
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

    if (Math.abs(position(activeSurface) - lastPosition) > EDGE_TOLERANCE) {
      stop()
      return
    }

    const remaining = target - current
    const elapsed = Math.max(0, now - lastTime)
    lastTime = now
    current = Math.abs(remaining) <= 0.5
      ? target
      : current + remaining * (1 - Math.exp(-elapsed / RESPONSE_MS))
    activeSurface.scrollTop = current
    lastPosition = position(activeSurface)

    if (Math.abs(target - current) <= 0.5) {
      activeSurface.scrollTop = target
      frame = null
    } else {
      frame = window.requestAnimationFrame(animate)
    }
  }

  const wheel = (event: WheelEvent): void => {
    if (event.defaultPrevented || !event.cancelable || event.ctrlKey || event.metaKey || event.deltaY === 0) {
      stop()
      return
    }

    const nextSurface = scrollableSurface(root, event.target, event.deltaY)
    if (!nextSurface) {
      stop()
      return
    }

    const nextDirection = Math.sign(event.deltaY)
    if (
      frame === null
      || nextSurface !== surface
      || nextDirection !== direction
      || Math.abs(position(nextSurface) - lastPosition) > EDGE_TOLERANCE
    ) {
      stop()
      surface = nextSurface
      target = position(nextSurface)
      current = target
    }

    direction = nextDirection
    const unit = event.deltaMode === WheelEvent.DOM_DELTA_LINE
      ? 16
      : event.deltaMode === WheelEvent.DOM_DELTA_PAGE
        ? nextSurface.clientHeight
        : 1
    target = Math.max(0, Math.min(target + event.deltaY * unit * WHEEL_MULTIPLIER * sensitivity, limit(nextSurface)))
    if (target === position(nextSurface) && frame === null) return

    event.preventDefault()
    if (frame === null) {
      lastPosition = position(nextSurface)
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
