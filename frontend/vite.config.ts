import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// Each `/svc/<name>` prefix maps to that service's controller base path so the
// client can call clean relative URLs (e.g. `/svc/fx/rates/USD/INR`).
const svc = (prefix: string, port: number, base: string) => ({
  target: `http://localhost:${port}`,
  changeOrigin: true,
  rewrite: (p: string) => p.replace(new RegExp(`^/svc/${prefix}`), base),
})

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/svc/wallet': svc('wallet', 8081, '/api/v1'), // /wallets + /currencies
      '/svc/fx': svc('fx', 8082, '/api/v1/fx'),
      '/svc/orchestrator': svc('orchestrator', 8083, '/api/v1/conversions'),
      '/svc/merchant': svc('merchant', 8084, '/api/v1/merchant-payments'),
      '/svc/ledger': svc('ledger', 8085, '/api/v1/ledger'),
    },
  },
})
