/**
 * Installs the freshly built plugin ZIP into every JetBrains IDE found on this machine.
 *
 * The update is queued through each IDE's own `StartupActionScriptManager` (the same
 * mechanism the IDE's "Install Plugin from Disk" uses): a delete + unzip pair is appended to
 * `<system>/plugins/action.script`, which the IDE executes while starting up. That works for
 * running IDEs too — nothing is overwritten while the JARs are locked — and the new version goes
 * live on the next restart.
 *
 * When an IDE cannot be queued (no bundled JBR, no launched-once system directory), the plugin
 * is unpacked into `<config>/plugins/marktext-jetbrains` directly, provided the IDE is not running.
 *
 * Usage:
 *   node scripts/install-plugin.mjs [--dry-run] [--force]
 *
 *   --dry-run  only report the detected IDEs and the compatibility verdict
 *   --force    install even into IDEs whose build is older than `since-build`
 *
 * Set MARKTEXT_SKIP_INSTALL=1 to skip installation entirely.
 */
import { spawnSync } from 'node:child_process'
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync
} from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const pluginProject = join(root, 'jetbrains-plugin')
const distributions = join(pluginProject, 'build', 'distributions')
const queueToolSource = join(pluginProject, 'tools', 'plugin-update', 'QueuePluginUpdateTool.java')
const queueToolClasses = join(pluginProject, 'build', 'queue-plugin-update-tool')
const pluginFolder = 'marktext-jetbrains'
const isWindows = process.platform === 'win32'

const argv = process.argv.slice(2)
const dryRun = argv.includes('--dry-run')
const force = argv.includes('--force')

const IDE_NAME_PATTERN = 'IntelliJ IDEA|PyCharm|WebStorm|GoLand|Rider|CLion|PhpStorm|RubyMine|DataGrip|RustRover|Aqua|AppCode|JetBrains'
const NON_IDE_DIRS = new Set(['Daemon', 'PrivacyPolicy', 'consentOptions', 'discovery', 'Toolbox'])

function log(message) {
  process.stdout.write(`${message}\n`)
}

function fail(message) {
  process.stderr.write(`install-plugin: ${message}\n`)
  process.exit(1)
}

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' })
  if (result.error) throw result.error
  if (result.status !== 0) {
    throw new Error(`${command} exited with ${result.status}: ${(result.stderr || '').trim()}`)
  }
  return result.stdout
}

/** PowerShell needs `-EncodedCommand` here: backslashes survive shell/argv mangling only that way. */
function runPowerShell(script) {
  const encoded = Buffer.from(script, 'utf16le').toString('base64')
  return run('powershell', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded])
}

function readSinceBuild() {
  const xmlPath = join(pluginProject, 'src', 'main', 'resources', 'META-INF', 'plugin.xml')
  if (!existsSync(xmlPath)) return null
  const match = readFileSync(xmlPath, 'utf8').match(/since-build="(\d+)"/)
  return match ? Number(match[1]) : null
}

function latestPluginZip() {
  if (!existsSync(distributions)) return null
  const zips = readdirSync(distributions)
    .filter((name) => name.endsWith('.zip'))
    .map((name) => join(distributions, name))
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
  return zips[0] ?? null
}

function readProductInfo(dir) {
  const file = join(dir, 'product-info.json')
  if (!existsSync(file)) return null
  try {
    const info = JSON.parse(readFileSync(file, 'utf8'))
    if (!info?.dataDirectoryName) return null
    return {
      name: info.name ?? basename(dir),
      version: info.version ?? 'unknown',
      build: info.buildNumber ?? null,
      configDirName: info.dataDirectoryName,
      installDir: dir
    }
  } catch {
    return null
  }
}

/** Directories of every JetBrains IDE installation found on Windows. */
function windowsInstallDirs() {
  const script = String.raw`
$locations = @()
$keys = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*", "HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*"
$locations += Get-ItemProperty $keys -ErrorAction SilentlyContinue |
  Where-Object { $_.InstallLocation -and $_.DisplayName -match "` + IDE_NAME_PATTERN + String.raw`" } |
  ForEach-Object { $_.InstallLocation }

$common = @(
  (Join-Path $env:ProgramFiles "JetBrains"),
  (Join-Path $env:LOCALAPPDATA "Programs"),
  (Join-Path $env:LOCALAPPDATA "JetBrains\Toolbox\apps")
)
$x86 = [Environment]::GetEnvironmentVariable("ProgramFiles(x86)")
if ($x86) { $common += Join-Path $x86 "JetBrains" }
foreach ($dir in $common) {
  if ($dir -and (Test-Path -LiteralPath $dir)) {
    $locations += Get-ChildItem -LiteralPath $dir -Recurse -Depth 4 -Filter "product-info.json" -File -ErrorAction SilentlyContinue |
      ForEach-Object { $_.DirectoryName }
  }
}

# IDE installs can live on any drive outside the default folders.
foreach ($drive in Get-PSDrive -PSProvider FileSystem) {
  if (-not (Test-Path -LiteralPath $drive.Root)) { continue }
  $locations += Get-ChildItem -LiteralPath $drive.Root -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "product-info.json") } |
    ForEach-Object { $_.FullName }
}

$locations | Where-Object { $_ } | Sort-Object -Unique | ConvertTo-Json -Compress
`
  const output = runPowerShell(script).trim()
  if (!output) return []
  const parsed = JSON.parse(output)
  return Array.isArray(parsed) ? parsed : [parsed]
}

function unixInstallDirs() {
  if (process.platform === 'darwin') {
    if (!existsSync('/Applications')) return []
    return readdirSync('/Applications')
      .filter((entry) => entry.endsWith('.app'))
      .map((entry) => join('/Applications', entry, 'Contents', 'Resources'))
  }
  return ['/opt', '/usr/share', join(homedir(), '.local', 'share')]
    .filter((dir) => existsSync(dir))
    .flatMap((dir) =>
      readdirSync(dir)
        .map((entry) => join(dir, entry))
        .filter((entry) => existsSync(join(entry, 'product-info.json')))
    )
}

function configRoots() {
  if (isWindows) {
    return [join(process.env.APPDATA ?? join(homedir(), 'AppData', 'Roaming'), 'JetBrains')]
  }
  if (process.platform === 'darwin') {
    return [join(homedir(), 'Library', 'Application Support', 'JetBrains')]
  }
  return [join(homedir(), '.config', 'JetBrains'), join(homedir(), '.local', 'share', 'JetBrains')]
}

/** `<LOCALAPPDATA>/JetBrains`, the per-IDE system cache directories live here. */
function systemRoot() {
  if (isWindows) {
    return join(process.env.LOCALAPPDATA ?? join(homedir(), 'AppData', 'Local'), 'JetBrains')
  }
  if (process.platform === 'darwin') {
    return join(homedir(), 'Library', 'Caches', 'JetBrains')
  }
  return join(homedir(), '.cache', 'JetBrains')
}

/** Existing IDE config directories, keyed by their data directory name. */
function existingConfigDirs() {
  const found = new Map()
  for (const configRoot of configRoots()) {
    if (!existsSync(configRoot)) continue
    for (const entry of readdirSync(configRoot)) {
      if (NON_IDE_DIRS.has(entry)) continue
      const dir = join(configRoot, entry)
      const hasIdeState = existsSync(join(dir, 'plugins')) || existsSync(join(dir, 'options'))
      if (hasIdeState) found.set(entry, dir)
    }
  }
  return found
}

/**
 * Returns every IDE on this machine as
 * `{ name, version, build, configDir }`, plus stale config directories that no
 * installed IDE claims.
 */
function detectIdes() {
  const configs = existingConfigDirs()
  const ides = []
  const claimed = new Set()

  const installDirs = isWindows ? windowsInstallDirs() : unixInstallDirs()
  for (const dir of installDirs) {
    const info = readProductInfo(dir)
    if (!info || claimed.has(info.configDirName)) continue
    claimed.add(info.configDirName)
    const configDir = configs.get(info.configDirName)
      ?? join(configRoots()[0], info.configDirName)
    ides.push({ ...info, configDir })
  }

  const stale = [...configs]
    .filter(([name]) => !claimed.has(name))
    .map(([name, dir]) => ({ name, configDir: dir }))

  return { ides: ides.sort((a, b) => a.configDirName.localeCompare(b.configDirName)), stale }
}

function buildBranch(build) {
  const match = String(build ?? '').match(/^(\d{3})/)
  return match ? Number(match[1]) : null
}

function isCompatible(ide, sinceBuild) {
  if (!sinceBuild) return true
  const branch = buildBranch(ide.build)
  return branch === null ? null : branch >= sinceBuild
}

/** Compiles the queue helper with `javac`; reused across builds unless the source changed. */
function ensureQueueTool() {
  const classFile = join(queueToolClasses, 'QueuePluginUpdateTool.class')
  if (existsSync(classFile) && statSync(classFile).mtimeMs >= statSync(queueToolSource).mtimeMs) {
    return queueToolClasses
  }
  mkdirSync(queueToolClasses, { recursive: true })
  const javaHome = process.env.JAVA_HOME
  const javac = javaHome
    ? join(javaHome, 'bin', isWindows ? 'javac.exe' : 'javac')
    : 'javac'
  run(javac, ['--release', '21', '-d', queueToolClasses, queueToolSource])
  return queueToolClasses
}

/** IDE installation root, preferring the `.home` record written by the IDE itself. */
function ideHomeFor(ide) {
  const homeFile = join(systemRoot(), ide.configDirName, '.home')
  if (existsSync(homeFile)) {
    const dir = readFileSync(homeFile, 'utf8').trim()
    if (dir && existsSync(dir)) return dir
  }
  return ide.installDir ?? null
}

/** The ZIP snapshot already registered in a pending startup action, if any. */
function pendingSnapshot(systemDir) {
  const pluginsDir = join(systemDir, 'plugins')
  if (!existsSync(pluginsDir)) return null
  const snapshots = readdirSync(pluginsDir)
    .filter((entry) => entry.startsWith('marktext-local-update-') && entry.endsWith('.zip'))
    .map((entry) => join(pluginsDir, entry))
  return snapshots[0] ?? null
}

/**
 * Appends a delete+unzip pair to the IDE's startup action script. Works while the IDE is
 * running because the IDE performs the replacement during its next startup.
 */
function queueUpdate(zipPath, ide, pluginsDir) {
  const ideHome = ideHomeFor(ide)
  if (!ideHome) throw new Error('cannot locate the IDE installation directory')
  const jbr = join(ideHome, 'jbr', 'bin', isWindows ? 'java.exe' : 'java')
  const libDir = join(ideHome, 'lib')
  const systemDir = join(systemRoot(), ide.configDirName)
  if (!existsSync(jbr)) throw new Error(`no bundled JBR at ${jbr}`)
  if (!existsSync(libDir)) throw new Error(`no IDE libraries at ${libDir}`)
  if (!existsSync(systemDir)) throw new Error(`the IDE has never been launched (missing ${systemDir})`)

  // Reuse the queued snapshot so repeated builds do not pile up 6 MB ZIPs in the IDE cache.
  const snapshot = pendingSnapshot(systemDir)
  if (snapshot) {
    copyFileSync(zipPath, snapshot)
    return `already queued; refreshed ${basename(snapshot)}. Restart this IDE to apply it.`
  }

  const classes = ensureQueueTool()
  return run(jbr, ['-cp', classes, 'QueuePluginUpdateTool', libDir, systemDir, pluginsDir, zipPath, pluginFolder]).trim()
}

/** Full image paths of all running processes (null when unavailable). */
function runningProcessPaths() {
  try {
    if (isWindows) {
      const script = String.raw`
$result = @()
foreach ($p in (Get-Process -ErrorAction SilentlyContinue)) {
  try { if ($p.Path) { $result += $p.Path } } catch { }
}
$result | Sort-Object -Unique | ConvertTo-Json -Compress
`
      const output = runPowerShell(script).trim()
      if (!output) return []
      const parsed = JSON.parse(output)
      return Array.isArray(parsed) ? parsed : [parsed]
    }
    return run('ps', ['-Ao', 'comm'])
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter(Boolean)
  } catch {
    return null
  }
}

/** Whether a process was launched from the given IDE installation. */
function isIdeRunning(installDir, processPaths) {
  if (!installDir || !processPaths) return null
  const target = installDir.toLowerCase()
  const appRoot = target.includes('.app/contents/') ? target.slice(0, target.indexOf('.app/contents/') + 5) : null
  return processPaths.some((path) => {
    const lower = path.toLowerCase()
    return lower.startsWith(target) || (appRoot !== null && lower.startsWith(appRoot))
  })
}

/** Unpacks the ZIP and copies it over the existing plugin folder. */
function installInto(zipPath, pluginsDir) {
  const staging = mkdtempSync(join(tmpdir(), 'marktext-plugin-'))
  try {
    if (isWindows) {
      runPowerShell(`Expand-Archive -LiteralPath "${zipPath.replace(/"/g, '""')}" -DestinationPath "${staging.replace(/"/g, '""')}" -Force`)
    } else {
      run('unzip', ['-oq', zipPath, '-d', staging])
    }
    copyTree(join(staging, pluginFolder), join(pluginsDir, pluginFolder))
  } finally {
    rmSync(staging, { recursive: true, force: true })
  }
}

/** Copies files over the existing installation, keeping unrelated files intact. */
function copyTree(from, to) {
  if (!existsSync(from)) fail(`plugin archive is missing the ${pluginFolder}/ directory`)
  mkdirSync(to, { recursive: true })
  for (const entry of readdirSync(from, { withFileTypes: true })) {
    const source = join(from, entry.name)
    const target = join(to, entry.name)
    if (entry.isDirectory()) {
      copyTree(source, target)
      continue
    }
    copyFileSync(source, target)
  }
}

function main() {
  if (process.env.MARKTEXT_SKIP_INSTALL === '1') {
    log('install-plugin: skipped (MARKTEXT_SKIP_INSTALL=1)')
    return
  }

  const zipPath = latestPluginZip()
  if (!zipPath) fail('no plugin ZIP found; run the Gradle buildPlugin task first')

  const sinceBuild = readSinceBuild()
  const { ides, stale } = detectIdes()
  log(`install-plugin: ${basename(zipPath)} (since-build ${sinceBuild ?? 'unset'})`)

  if (ides.length === 0) {
    log('install-plugin: no JetBrains IDE found, nothing to install')
    return
  }

  const processPaths = dryRun ? null : runningProcessPaths()
  const skipped = []
  const queued = []
  const copied = []
  const failed = []

  for (const ide of ides) {
    const label = `${ide.name} ${ide.version} (build ${ide.build ?? 'unknown'})`
    if (isCompatible(ide, sinceBuild) === false && !force) {
      skipped.push(`${label}: build branch ${buildBranch(ide.build)} < since-build ${sinceBuild}`)
      continue
    }

    const pluginsDir = join(ide.configDir, 'plugins')
    if (dryRun) {
      log(`install-plugin: would queue ${label} -> ${join(pluginsDir, pluginFolder)}`)
      continue
    }

    try {
      const message = queueUpdate(zipPath, ide, pluginsDir)
      queued.push(`${label}: ${message}`)
      continue
    } catch (queueError) {
      // Fall back to a direct copy when the IDE is closed and cannot be queued.
      if (isIdeRunning(ideHomeFor(ide) ?? ide.installDir, processPaths) === true) {
        failed.push(`${label}: ${queueError.message} (close this IDE and rerun)`)
        continue
      }
      try {
        installInto(zipPath, pluginsDir)
        copied.push(`${label}: ${join(pluginsDir, pluginFolder)} (copied directly)`)
      } catch (copyError) {
        failed.push(`${label}: ${queueError.message}; direct copy failed: ${copyError.message}`)
      }
    }
  }

  for (const message of queued) log(`install-plugin: queued ${message}`)
  for (const message of copied) log(`install-plugin: installed ${message}`)
  for (const message of skipped) log(`install-plugin: skipped ${message} (use --force to override)`)
  for (const message of failed) log(`install-plugin: FAILED ${message}`)
  for (const entry of stale) log(`install-plugin: ignored leftover config directory ${entry.name} (no matching IDE installation)`)
  if (queued.length > 0) log('install-plugin: restart the queued IDEs to apply the update')
  if (failed.length > 0) process.exitCode = 1
}

main()
