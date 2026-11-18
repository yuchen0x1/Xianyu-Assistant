import { execFileSync, spawnSync } from 'node:child_process'

const options = ['audit', '--registry=https://registry.npmjs.org', '--audit-level=high']
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const settings = { encoding: 'utf8', shell: process.platform === 'win32', timeout: 120000 }

execFileSync(npm, [...options, '--omit=dev'], { ...settings, stdio: 'inherit' })
const result = spawnSync(npm, [...options, '--json'], settings)
if (result.error) throw result.error
const report = JSON.parse(result.stdout)
if (report.error || !report.vulnerabilities) throw new Error('npm audit did not return a vulnerability report')

const advisories = Object.values(report.vulnerabilities)
  .flatMap(entry => entry.via.filter(cause => typeof cause === 'object'))
const severe = advisories.filter(advisory => ['high', 'critical'].includes(advisory.severity))
const blocked = severe.filter(advisory => {
  // This unpatched advisory affects build glob tooling. The runtime audit above has no exception.
  return advisory.name !== 'picomatch'
    || advisory.url !== 'https://github.com/advisories/GHSA-fc5q-39xc-vjpf'
    || advisory.severity !== 'high'
})
if (blocked.length || (result.status !== 0 && severe.length === 0)) {
  console.error(result.stdout)
  throw new Error('Unresolved npm build dependency audit findings')
}
if (severe.length) console.warn('Build-only exception: GHSA-fc5q-39xc-vjpf (picomatch). Review when a fix is available.')
