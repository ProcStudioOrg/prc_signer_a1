import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'

// https://vite.dev/config/
export default defineConfig({
  plugins: [svelte()],
  server: {
    proxy: {
      '/api': {
        // LOCAL DEBUG: aponta pro backend rodando local (jar --api na 8081).
        // Reverter todo este bloco para { target: 'https://signer.procstudio.com.br',
        // changeOrigin: true, secure: true } antes de commitar.
        target: 'http://localhost:8081',
        changeOrigin: true,
        secure: false,
        // O backend restringe CORS às origens procstudio; o browser manda
        // Origin: 127.0.0.1:5173. Reescrevemos p/ uma origem permitida só no dev.
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('origin', 'https://signer.procstudio.com.br');
          });
        },
      },
    },
  },
})
