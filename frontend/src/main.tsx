import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { blockBrowserKeys } from './shortcuts'
import { rememberCounterFromUrl } from './counter'
import { savedToken } from './api'
import { applyTheme, cachedTheme } from './theme'
import './index.css'

// Before anything asks the server who it is: the counter app puts the number on the address when
// it opens the window, and this is the one moment it is there to be read.
rememberCounterFromUrl()
blockBrowserKeys()

// Paint the shop's theme at once on a reload, before the server has been asked. Only for somebody
// already signed in: the sign-in screen is the brand's own.
if (savedToken()) {
  applyTheme(cachedTheme())
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
