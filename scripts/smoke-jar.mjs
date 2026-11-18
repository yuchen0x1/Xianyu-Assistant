import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { resolve, join } from 'node:path'
import { createServer } from 'node:net'
import { setTimeout as delay } from 'node:timers/promises'

const [jar, version] = process.argv.slice(2)
if (!jar || !/^\d+\.\d+\.\d+$/.test(version)) {
  throw new Error('Usage: node scripts/smoke-jar.mjs <jar> <version>')
}

const listener = createServer()
await new Promise((resolve, reject) => {
  listener.once('error', reject).listen(0, '127.0.0.1', resolve)
})
const port = listener.address().port
await new Promise((resolve, reject) => listener.close(error => error ? reject(error) : resolve()))

const directory = await mkdtemp(join(tmpdir(), 'xianyu-smoke-'))
const base = `http://127.0.0.1:${port}`
const java = spawn('java', [
  '--enable-native-access=ALL-UNNAMED', '-jar', resolve(jar),
  `--server.port=${port}`, '--server.address=127.0.0.1',
  '--app.self-update.auto-update=false', '--app.backup.enabled=false',
], { cwd: directory, stdio: ['ignore', 'pipe', 'pipe'] })
let output = ''
for (const stream of [java.stdout, java.stderr]) {
  stream.on('data', chunk => { output = (output + chunk.toString()).slice(-16000) })
}
const finished = new Promise(resolve => {
  java.once('error', resolve)
  java.once('close', resolve)
}).then(result => {
  if (result instanceof Error) output += `\n${result.message}`
  return true
})

async function get(path) {
  const response = await fetch(new URL(path, base), { signal: AbortSignal.timeout(3000) })
  if (!response.ok) throw new Error(`${path} returned HTTP ${response.status}`)
  return response
}

try {
  const deadline = Date.now() + 90000
  while (true) {
    try {
      const health = await (await get('/actuator/health')).json()
      if (health.status !== 'UP') throw new Error(`Health status ${health.status}`)
      break
    } catch (error) {
      if (!java.pid || java.exitCode !== null || Date.now() >= deadline) throw error
      await delay(1000)
    }
  }

  const response = await (await get('/api/system/version')).json()
  if (response.data !== version) throw new Error(`Expected version ${version}, got ${response.data}`)

  const html = await (await get('/')).text()
  const asset = html.match(/src="(\/assets\/[^"\s]+\.js)"/)?.[1]
  if (!asset) throw new Error('The packaged Web console has no JavaScript entry point')
  const script = await get(asset)
  if (!/javascript/.test(script.headers.get('content-type') ?? '')) {
    throw new Error('The JavaScript entry point returned an unexpected content type')
  }
  await script.arrayBuffer()
  console.log(`PASS Java 21 JAR ${version} (${process.platform}/${process.arch}): health, version, Web assets`)
} catch (error) {
  console.error(output)
  throw error
} finally {
  java.kill()
  await finished
  await rm(directory, { recursive: true, force: true, maxRetries: 5 })
}
