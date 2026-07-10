import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.jsx'
import { AuthProvider } from './context/AuthContext.jsx'
import './index.css'

// This is the one place the whole app gets wired together:
//   BrowserRouter -> gives every component access to routing (useNavigate, <Link>, etc.)
//   AuthProvider  -> gives every component access to auth state (see context/AuthContext.jsx)
//   App           -> decides which page to actually show, based on the current URL
createRoot(document.getElementById('root')).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
