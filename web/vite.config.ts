import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server. Every same-origin path the console calls is proxied to the dev stack's edge (Caddy on :8088, see
// docker-compose.dev.yml), which routes it exactly as production does: /rest, /files and /agent/ws to the server,
// /update and /recovery to the supervisor. Override the target with VITE_DEV_PROXY_TARGET.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_');
  const target = env.VITE_DEV_PROXY_TARGET || 'http://localhost:8088';
  // The server is session-cookie based (JSESSIONID), so cookies must be forwarded. /agent/ws is a WebSocket.
  const route = (ws = false) => ({ target, changeOrigin: true, cookieDomainRewrite: '', ws });

  return {
    plugins: [react()],
    server: {
      host: true,
      port: 5173,
      allowedHosts: true,
      proxy: {
        '/rest': route(),
        '/files': route(),
        '/agent/ws': route(true),
        '/update': route(),
        '/recovery': route(),
      },
    },
  };
});
