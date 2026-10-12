#!/usr/bin/env bash
set -euo pipefail

# CI only: isolated offline server with no exposed Minecraft or RCON ports.
mkdir -p smoke-data/plugins smoke-output
JAR="$(find smoke-artifact -type f -name 'civcraft-leaf-*.jar' ! -name 'original-*' | head -n 1)"
test -n "$JAR"
cp "$JAR" smoke-data/plugins/CivCraft.jar

# Test-only cheap town and claims; these values never reach production defaults.
mkdir -p smoke-data/plugins/CivCraft
cp civcraft-leaf/src/main/resources/config.yml smoke-data/plugins/CivCraft/config.yml
sed -i 's/^  cost: 10000$/  cost: 0/' smoke-data/plugins/CivCraft/config.yml
sed -i 's/^  claim-cost: 250$/  claim-cost: 5/' smoke-data/plugins/CivCraft/config.yml

docker run -d --name civcraft-smoke \
  -e EULA=TRUE -e TYPE=PAPER -e VERSION=1.21.11 \
  -e MEMORY=2G -e ONLINE_MODE=FALSE -e ENFORCE_SECURE_PROFILE=FALSE \
  -e DIFFICULTY=peaceful -e VIEW_DISTANCE=3 -e SIMULATION_DISTANCE=3 \
  -e RCON_PASSWORD=ci-only-civcraft-check \
  -e MAX_PLAYERS=2 -e SPAWN_PROTECTION=0 \
  -v "$PWD/smoke-data:/data" \
  -p 127.0.0.1:25565:25565 \
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

echo "Running real player commands through the Minecraft protocol..."
npm install --prefix "$RUNNER_TEMP/civcraft-smoke-node" --silent --no-audit --no-fund mineflayer@4
NODE_PATH="$RUNNER_TEMP/civcraft-smoke-node/node_modules" node .github/scripts/smoke-player-1.21.11.cjs

python3 - <<'PY'
import sqlite3
with sqlite3.connect('smoke-data/plugins/CivCraft/civcraft.db') as db:
    civs = db.execute("SELECT COUNT(*) FROM civilizations WHERE name = ?", ('CivSmoke',)).fetchone()[0]
    towns = db.execute("SELECT COUNT(*) FROM towns WHERE name = ?", ('TownSmoke',)).fetchone()[0]
    claims = db.execute("SELECT COUNT(*) FROM town_claims").fetchone()[0]
    residents = db.execute("SELECT COUNT(*) FROM residents WHERE name = ?", ('CivSmokeBot',)).fetchone()[0]
    coins = db.execute("SELECT coins FROM residents WHERE name = ?", ('CivSmokeBot',)).fetchone()[0]
    print(f'Player test: residents={residents} civilizations={civs} towns={towns} claims={claims} coins={coins}')
    if (residents, civs, towns, claims, coins) != (1, 1, 1, 1, 245):
        raise SystemExit('Expected one civilization, town, claim and exactly one 5-coin charge')
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
python3 - <<'PY'
import sqlite3
with sqlite3.connect('smoke-data/plugins/CivCraft/civcraft.db') as db:
    coins = db.execute("SELECT coins FROM residents WHERE name = 'CivSmokeBot'").fetchone()[0]
    civs = db.execute("SELECT COUNT(*) FROM civilizations WHERE name = 'CivSmoke'").fetchone()[0]
    towns = db.execute("SELECT COUNT(*) FROM towns WHERE name = 'TownSmoke'").fetchone()[0]
    claims = db.execute("SELECT COUNT(*) FROM town_claims").fetchone()[0]
    assert (coins, civs, towns, claims) == (245, 1, 1, 1), (coins, civs, towns, claims)
    print('Persistent game state survived restart: 1 civilization, 1 town, 1 claim, 245 coins')
PY
echo "Paper 1.21.11 smoke test passed, including restart."
