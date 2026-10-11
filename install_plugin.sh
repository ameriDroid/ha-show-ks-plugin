#!/bin/bash
# Build a Kiosk Satellite plugin and install + enable it through KS's remote admin API.
#   ./install_plugin.sh <kiosk-ip> <remote-admin-password> [port]
# Needs JDK 17, an Android SDK (ANDROID_HOME) and python3. The admin API is POST /api/login -> bearer token,
# then POST /api/commands/<command> (see the Plugin Manager docs in kiosk-satellite-plugin-hello-world).
set -euo pipefail
cd "$(dirname "$0")"
PLUGIN="."; IP="${1:?kiosk ip}"; PASS="${2:?remote admin password}"; PORT="${3:-2324}"
B="http://$IP:$PORT"

python3 tools/build.py "$PLUGIN" | tail -3
ZIP=$(ls -t "$PLUGIN"/dist/*.zip | head -1)
ID=$(python3 -c "import json,sys; print(json.load(open('$PLUGIN/kiosk-satellite-plugin.json'))['id'])")

TOKEN=$(curl -sf -m10 -X POST "$B/api/login" -d "{\"password\":\"$PASS\"}" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
cmd() { curl -sf -m30 -X POST "$B/api/commands/$1" -H "Authorization: Bearer $TOKEN" -d "$2"; }

cmd setPluginsEnabled '{"enabled":true}' >/dev/null
DATA=$(base64 < "$ZIP" | tr -d '\n')
cmd installPlugin "{\"data\":\"$DATA\",\"trusted\":true}" >/dev/null
cmd enablePlugin "{\"id\":\"$ID\"}" >/dev/null
sleep 3
cmd getPluginState '{}' | python3 -c "
import sys,json
for p in json.load(sys.stdin)['data']['plugins']:
    if p['id']=='$ID': print('$ID: enabled=%s running=%s status=%s' % (p['enabled'], p['running'], p.get('status')))"
