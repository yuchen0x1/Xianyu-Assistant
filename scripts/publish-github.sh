#!/usr/bin/env bash
# Publish a verified tag from main. SKIP_BUILD=1 uses the JAR produced and tested by CI.
set -euo pipefail
cd "$(dirname "$0")/.."

repo="${GITHUB_REPO:-yuchen0x1/Xianyu-Assistant}"
[[ "$repo" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ && "$repo" != *..* ]]
official=false
[[ "${repo,,}" != 'yuchen0x1/xianyu-assistant' ]] || official=true
if [[ -n "${GITHUB_TOKEN:-}" ]]; then export GH_TOKEN="$GITHUB_TOKEN"; fi
export GH_PROMPT_DISABLED=1

version="$(node -p "require('./vue-code/package.json').version")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "${1:-$version}" == "$version" ]]
tag="v$version"
head="$(git rev-parse HEAD)"
[[ -z "$(git status --porcelain)" ]] || { echo 'Tracked and untracked changes must be committed before publishing.' >&2; exit 1; }
[[ "$(gh api "repos/$repo/commits/$tag" --jq .sha)" == "$head" ]] || { echo 'Remote tag must point at this commit.' >&2; exit 1; }
if "$official"; then
  [[ "$(gh api "repos/$repo/compare/$head...main" --jq .status)" =~ ^(ahead|identical)$ ]] || {
    echo 'The official release commit must be part of main.' >&2; exit 1;
  }
fi

releases="repos/$repo/releases"
lookup_release() {
  gh api --paginate "$releases?per_page=100" --jq ".[] | select(.tag_name == \"$tag\") | [.id,.draft,.target_commitish] | @tsv"
}

existing="$(lookup_release)"
if [[ -n "$existing" ]]; then
  IFS=$'\t' read -r release_id draft target <<< "$existing"
  [[ "$draft" == true && "$target" == "$head" ]] || {
    echo "Release $tag already exists or its draft refers to a different target." >&2; exit 1;
  }
fi

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  (cd vue-code && npm ci && node ../scripts/audit-frontend.mjs && npm test && npm run lint:check && npm run build)
  ./mvnw --batch-mode --no-transfer-progress clean package
  env -u RELEASE_SIGN_PRIVATE_KEY -u RELEASE_SIGN_KEY_FILE -u GH_TOKEN -u GITHUB_TOKEN \
    node scripts/smoke-jar.mjs "target/XianYuSellerConsole-$version.jar" "$version"
fi

jar="target/XianYuSellerConsole-$version.jar"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
jar_name="$(basename "$jar")"
cp "$jar" "$work/$jar_name"
sha256sum "$work/$jar_name" | awk -v name="$jar_name" '{print $1 "  " name}' > "$work/$jar_name.sha256"
notes="$work/notes.md"
awk -v heading="# Version $version" '{sub(/\r$/, "")} $0==heading {p=1;next} /^# Version /&&p{exit} p{print}' CHANGELOG.md > "$notes"
grep -q '[^[:space:]]' "$notes" || { echo "CHANGELOG.md requires an entry for $version." >&2; exit 1; }

assets=("$work/$jar_name" "$work/$jar_name.sha256")
signed=false
if [[ -n "${RELEASE_SIGN_KEY_FILE:-}${RELEASE_SIGN_PRIVATE_KEY:-}" ]]; then
  bash scripts/sign-release.sh sign "$work/$jar_name"
  signed=true
  assets+=("$work/$jar_name.sig")
elif "$official"; then
  echo 'Official releases require an Ed25519 signing key.' >&2
  exit 1
fi

if [[ "${SPLIT_JAR:-0}" == 1 ]]; then
  split -b 95m -d -a 2 "$work/$jar_name" "$work/$jar_name.split-"
  assets=("${assets[@]:1}" "$work/$jar_name".split-*)
fi

metadata="$work/update-manifest.json"
node --input-type=module - "$version" "$repo" "$head" "$work/$jar_name" "$notes" "$signed" "${assets[@]}" > "$metadata" <<'JS'
import { basename } from 'node:path'
import { readFileSync, statSync } from 'node:fs'
const [version, repository, commit, jar, notes, signed, ...paths] = process.argv.slice(2)
const changelog = readFileSync(notes, 'utf8').trim()
const base = `https://github.com/${repository}/releases/download/v${version}`
const digest = readFileSync(`${jar}.sha256`, 'utf8').split(/\s/)[0]
const assets = paths.map(path => ({ name: basename(path), size: statSync(path).size, url: `${base}/${basename(path)}` }))
console.log(JSON.stringify({
  schemaVersion: 1, version, tag: `v${version}`, commit, channel: 'stable', repository,
  platform: 'java', architecture: 'any', minimumJava: 21, changelog,
  mandatory: /<!--\s*mandatory\s*-->/i.test(changelog), securityFix: /<!--\s*security-fix\s*-->/i.test(changelog),
  jar: { name: basename(jar), size: statSync(jar).size, sha256: digest,
    signature: signed === 'true' ? `${base}/${basename(jar)}.sig` : null }, assets,
}, null, 2))
JS
assets+=("$metadata")
if "$signed"; then
  bash scripts/sign-release.sh sign "$metadata"
  assets+=("$metadata.sig")
fi

if "$official"; then
  verifier='src/main/java/com/feijimiao/xianyuassistant/service/update/SelfUpdateService.java'
  key="$(awk '/static final String OFFICIAL_RELEASE_PUBLIC_KEY =/{getline;gsub(/[^A-Za-z0-9+\/=]/, "");print}' "$verifier")"
  [[ -n "$key" ]]
  printf '%s\n%s\n%s\n' '-----BEGIN PUBLIC KEY-----' "$key" '-----END PUBLIC KEY-----' > "$work/public.pem"
  for file in "$work/$jar_name" "$metadata"; do
    base64 -d < "$file.sig" > "$work/signature.bin"
    openssl pkeyutl -verify -pubin -inkey "$work/public.pem" -rawin -in "$file" -sigfile "$work/signature.bin"
  done
fi

if [[ -z "$existing" ]]; then
  gh release create "$tag" --repo "$repo" --verify-tag --draft --target "$head" --title "$tag" --notes-file "$notes"
  IFS=$'\t' read -r release_id draft target <<< "$(lookup_release)"
  [[ "$draft" == true && "$target" == "$head" ]]
fi

# Retry only against a draft whose tag and commit were verified above.
gh release upload "$tag" --repo "$repo" --clobber "${assets[@]}"
release_json="$work/release.json"
gh api "$releases/$release_id" > "$release_json"
node --input-type=module - "$release_json" "$tag" "$head" "${assets[@]}" <<'JS'
import { createHash } from 'node:crypto'
import { createReadStream, readFileSync, statSync } from 'node:fs'
import { basename } from 'node:path'
const [json, tag, commit, ...files] = process.argv.slice(2)
const release = JSON.parse(readFileSync(json, 'utf8'))
if (!release.draft || release.tag_name !== tag || release.target_commitish !== commit || release.assets.length !== files.length) {
  throw new Error('The draft tag, target or asset count does not match the staged release')
}
for (const file of files) {
  const name = basename(file)
  const asset = release.assets.find(candidate => candidate.name === name)
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk)
  const digest = `sha256:${hash.digest('hex')}`
  if (asset?.state !== 'uploaded' || asset.size !== statSync(file).size || asset.digest !== digest) {
    throw new Error(`The uploaded asset did not match the verified local file: ${name}`)
  }
  console.log(`${digest} ${name}`)
}
JS

gh release edit "$tag" --repo "$repo" --draft=false --latest
gh api "$releases/latest" --jq ".tag_name == \"$tag\" and .draft == false and .prerelease == false" | grep -Fx true
