import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { blockBrowserKeys } from './shortcuts'
import { rememberCounterFromUrl } from './counter'
import './index.css'

// Before anything asks the server who it is: the counter app puts the number on the address when
// it opens the window, and this is the one moment it is there to be read.
rememberCounterFromUrl()
blockBrowserKeys()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
