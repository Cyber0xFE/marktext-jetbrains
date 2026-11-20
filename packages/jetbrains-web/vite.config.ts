import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'

const __dirname = dirname(fileURLToPath(import.meta.url))

export default defineConfig({
  define: {
    'process.env.NODE_ENV': JSON.stringify('production')
  },
  json: {
    stringify: true
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
    emptyOutDir: true,
    lib: {
      entry: resolve(__dirname, 'src/main.ts'),
      name: 'MarkTextJetBrainsWeb',
      formats: ['iife'],
      fileName: () => 'marktext-web.js',
      cssFileName: 'marktext-web'
    }
  }
})
