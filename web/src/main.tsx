import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { initializeAuth } from './auth'

async function bootstrap() {
  try {
    await initializeAuth()
  } catch (error) {
    console.error('Entra authentication initialization failed', error)
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}

void bootstrap()
