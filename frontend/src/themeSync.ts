import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import { applyTheme, cacheTheme } from './theme'
import type { ThemeView } from './theme'

/** How often a screen asks whether the shop's theme has changed: a few bytes, so this can be quick. */
const POLL_MS = 4000

/**
 * The shop's theme on this screen, kept in step with every other counter.
 *
 * <p>The theme lives in the server's database, so it is the same wherever somebody signs in. A change
 * is noticed within a few seconds: the screen asks for the theme's revision number on a short timer
 * (and the moment the window is brought back to the front), and fetches the theme only when the number
 * has moved. Nothing is pushed, which keeps it working through a sleeping laptop, a dropped Wi-Fi or a
 * restarted server without any reconnection logic.
 *
 * @param signedIn the theme is read and applied only once somebody has signed in; the sign-in screen
 *                 is the brand's own and is never restyled
 */
export function useThemeSync(signedIn: boolean) {
  const [view, setView] = useState<ThemeView | null>(null)
  const revision = useRef<number | null>(null)

  /** Takes a theme from the server, or from an administrator who has just set one. */
  const take = useCallback((next: ThemeView) => {
    revision.current = next.revision
    setView(next)
    applyTheme(next.active)
    cacheTheme(next.active)
  }, [])

  const load = useCallback(() => {
    api.get<ThemeView>('/api/theme').then(take).catch(() => {
      // Keep wearing the last theme; the next tick tries again.
    })
  }, [take])

  useEffect(() => {
    if (!signedIn) {
      revision.current = null
      setView(null)
      applyTheme(null)
      return
    }
    load()
    const check = () => {
      api.get<{ revision: number }>('/api/theme/revision').then((answer) => {
        if (answer.revision !== revision.current) {
          load()
        }
      }).catch(() => {
        // A till that is briefly unreachable keeps its look.
      })
    }
    const timer = setInterval(check, POLL_MS)
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        check()
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', check)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', check)
    }
  }, [signedIn, load])

  return { view, take }
}
