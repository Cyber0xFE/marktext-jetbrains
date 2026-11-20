import { spawnSync } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const wrapper = resolve(root, 'jetbrains-plugin', process.platform === 'win32' ? 'gradlew.bat' : 'gradlew')
const result = spawnSync(wrapper, process.argv.slice(2), {
  cwd: resolve(root, 'jetbrains-plugin'),
  stdio: 'inherit',
  shell: process.platform === 'win32'
})

if (result.error) throw result.error
process.exit(result.status ?? 1)
