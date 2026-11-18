#!/usr/bin/env bash
set -euo pipefail
previous="$1"
[[ "$previous" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]
jar="XianYuSellerConsole-$previous.jar"
root="$(mktemp -d)"
pid=''
cleanup() {
  local result=$?
  if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  if (( result != 0 )); then tail -100 "$root"/boot*.log >&2 || true; fi
  rm -rf -- "$root"
}
trap cleanup EXIT
key="$(awk '/static final String OFFICIAL_RELEASE_PUBLIC_KEY =/{getline;gsub(/[^A-Za-z0-9+\/=]/, "");print}' src/main/java/com/feijimiao/xianyuassistant/service/update/SelfUpdateService.java)"
printf '%s\n%s\n%s\n' '-----BEGIN PUBLIC KEY-----' "$key" '-----END PUBLIC KEY-----' > "$root/public.pem"
base64 -d < "$jar.sig" > "$root/signature.bin"
openssl pkeyutl -verify -pubin -inkey "$root/public.pem" -rawin -in "$jar" -sigfile "$root/signature.bin"
baseline="$(sha256sum "$jar" | cut -d ' ' -f 1)"
cp -- "$jar" "$root/app.jar"
cd "$root"

port=12489
base="http://127.0.0.1:$port"
if curl --silent --max-time 2 "$base/actuator/health" > /dev/null; then
  echo "The smoke test port $port is in use" >&2
  exit 1
fi
start() {
  java --enable-native-access=ALL-UNNAMED -jar app.jar --server.address=127.0.0.1 --server.port="$port" \
    --app.self-update.auto-update=false --app.self-update.restart-mode=exit --app.backup.enabled=false > "$1" 2>&1 &
  pid=$!
  for ((attempt=0; attempt<75; attempt++)); do
    if curl -fsS --max-time 3 "$base/actuator/health" 2>/dev/null | jq -e '.status == "UP"' > /dev/null; then return; fi
    kill -0 "$pid"
    sleep 1
  done
  echo 'The Web service did not start' >&2
  return 1
}

start boot-old.log
curl -fsS --max-time 5 "$base/api/system/version" | jq -e --arg version "$previous" '.data == $version'
password="Smoke1!$(openssl rand -hex 12)"
login="$(jq -cn --arg password "$password" '{username:"upgrade-smoke",password:$password,confirmPassword:$password}')"
token="$(curl -fsS --max-time 5 -H 'Content-Type: application/json' -d "$login" "$base/api/login/register" | jq -er '.data.token')"
auth=(-H "Authorization: Bearer $token")
update="$(curl -fsS --max-time 25 "${auth[@]}" "$base/api/system/checkUpdate")"
target="$(jq -er 'select(.data.checkStatus == "available" and .data.hasUpdate) | .data.latestVersion' <<< "$update")"
[[ "$target" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && "$target" != "$previous" ]]
jq -en --arg target "$target" '{targetVersion:$target}' | curl -fsS --max-time 6 "${auth[@]}" \
  -H 'Content-Type: application/json' -d @- "$base/api/system/selfUpdate/apply" | jq -e '.code == 200'

phase=''
for ((attempt=0; attempt<120; attempt++)); do
  status="$(curl -fsS --max-time 6 "${auth[@]}" "$base/api/system/selfUpdate/status" | jq -ec 'select(.code == 200).data')"
  phase="$(jq -er .phase <<< "$status")"
  if [[ "$phase" == SWAPPED ]]; then break; fi
  if [[ "$phase" == FAILED || "$phase" == CANCELLED ]]; then jq -r .message <<< "$status" >&2; exit 1; fi
  sleep 3
done
[[ "$phase" == SWAPPED ]]
[[ "$(sha256sum app.jar.bak | cut -d ' ' -f 1)" == "$baseline" ]]
curl -fsS --max-time 6 "${auth[@]}" -X POST "$base/api/system/selfUpdate/restart" | jq -e '.code == 200'
for ((attempt=0; attempt<45; attempt++)); do
  if ! kill -0 "$pid" 2>/dev/null; then break; fi
  sleep 1
done
if kill -0 "$pid" 2>/dev/null; then
  echo 'The old process did not exit after the restart request' >&2
  exit 1
fi
wait "$pid"
pid=''

start boot-new.log
curl -fsS --max-time 5 "$base/api/system/version" | jq -e --arg target "$target" '.data == $target'
curl -fsS --max-time 5 -H 'Content-Type: application/json' -d "$login" "$base/api/login/login" \
  | jq -e '.code == 200 and (.data.token | type == "string")'
printf 'PASS GitHub Release upgrade %s → %s, restart, SQLite login, and previous JAR backup\n' "$previous" "$target"
