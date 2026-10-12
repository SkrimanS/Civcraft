#!/usr/bin/env bash
set -euo pipefail

# CI only: isolated offline server with no exposed Minecraft or RCON ports.
mkdir -p smoke-data/plugins smoke-output
JAR="$(find smoke-artifact -type f -name 'civcraft-leaf-*.jar' ! -name 'original-*' | head -n 1)"
test -n "$JAR"
cp "$JAR" smoke-data/plugins/CivCraft.jar

docker run -d --name civcraft-smoke \
  -e EULA=TRUE -e TYPE=PAPER -e VERSION=1.21.11 \
  -e MEMORY=2G -e ONLINE_MODE=FALSE -e ENFORCE_SECURE_PROFILE=FALSE \
  -e DIFFICULTY=peaceful -e VIEW_DISTANCE=3 -e SIMULATION_DISTANCE=3 \
  -e RCON_PASSWORD=ci-only-civcraft-check \
  -e MAX_PLAYERS=2 -e SPAWN_PROTECTION=0 \
  -v "$PWD/smoke-data:/data" \
  itzg/minecraft-server:java21 > smoke-output/container.id

wait_for_plugin() {
  local response=""
  for i in $(seq 1 150); do
    if ! docker inspect --format '{{.State.Running}}' civcraft-smoke 2>/dev/null | grep -qx true; then
      echo "Server container stopped unexpectedly" >&2
      return 1
    fi
    response="$(docker exec civcraft-smoke rcon-cli "version CivCraft" 2>/dev/null || true)"
    if [[ "$response" == *"CivCraft version"* ]]; then
      echo "$response"
      return 0
    fi
    sleep 2
  done
  echo "CivCraft plugin was not enabled within 300 seconds" >&2
  return 1
}

echo "Checking first boot..."
wait_for_plugin
docker exec civcraft-smoke rcon-cli "civadmin integrations" > smoke-output/integrations.txt
if grep -qi "только игроку" smoke-output/integrations.txt; then
  echo "Admin integrations command still rejects server console" >&2
  exit 1
fi
if ! grep -q "CivCraft" smoke-output/integrations.txt; then
  echo "Admin integrations command did not return integration details" >&2
  cat smoke-output/integrations.txt
  exit 1
fi
docker exec civcraft-smoke rcon-cli "civadmin reload" > smoke-output/reload.txt
if grep -qi "только игроку" smoke-output/reload.txt; then
  echo "Admin reload command still rejects server console" >&2
  exit 1
fi
test -s smoke-data/plugins/CivCraft/config.yml
test -s smoke-data/plugins/CivCraft/civcraft/data/techs.yml
test -s smoke-data/plugins/CivCraft/civcraft/data/structures.yml
test -s smoke-data/plugins/CivCraft/civcraft.db
python3 - <<'PY'
import sqlite3
with sqlite3.connect('smoke-data/plugins/CivCraft/civcraft.db') as db:
    tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    required = {'residents', 'camps', 'towns', 'civilizations'}
    missing = required - tables
    if missing:
        raise SystemExit('Missing SQLite tables: ' + ', '.join(sorted(missing)))
    print('SQLite schema verified:', ', '.join(sorted(required)))
PY

echo "Stopping to verify persistent state across server restart..."
docker exec civcraft-smoke rcon-cli stop >/dev/null
for i in $(seq 1 45); do
  if [[ "$(docker inspect --format '{{.State.Running}}' civcraft-smoke)" == "false" ]]; then
    break
  fi
  sleep 2
done
test "$(docker inspect --format '{{.State.Running}}' civcraft-smoke)" = false
docker start civcraft-smoke >/dev/null
wait_for_plugin
test -s smoke-data/plugins/CivCraft/civcraft.db
echo "Paper 1.21.11 smoke test passed, including restart."
