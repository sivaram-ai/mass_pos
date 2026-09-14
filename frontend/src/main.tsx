import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import { blockBrowserKeys } from './shortcuts'
import './index.css'

blockBrowserKeys()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
