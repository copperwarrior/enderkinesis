#!/usr/bin/env bash
# Starts a production dedicated server with the built jar and fails if it does not reach "Done".
# Usage: server-smoke-test.sh <fabric|forge> <mod jar> <server dir>
set -euo pipefail

LOADER="$1"
MOD_JAR="$(realpath "$2")"
SERVER_DIR="$3"
PROPS="$(realpath "$(dirname "$0")/../gradle.properties")"
START_TIMEOUT=600

prop() { grep -E "^$1\s*=" "$PROPS" | head -n1 | cut -d= -f2- | tr -d '[:space:]'; }

MC="$(prop minecraft_version)"
VS2="$(prop vs2_version)"
VS_MAVEN="https://maven.valkyrienskies.org/org/valkyrienskies"

rm -rf "$SERVER_DIR"
mkdir -p "$SERVER_DIR/mods"
cd "$SERVER_DIR"
echo "eula=true" > eula.txt
cp "$MOD_JAR" mods/

fetch() { curl -sSfL --retry 3 -o "$2" "$1"; }

case "$LOADER" in
  fabric)
    LOADER_VERSION="$(prop fabric_loader_version)"
    FABRIC_API="$(prop fabric_api_version)"
    fetch "https://meta.fabricmc.net/v2/versions/loader/$MC/$LOADER_VERSION/1.0.1/server/jar" server.jar
    fetch "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FABRIC_API/fabric-api-$FABRIC_API.jar" mods/fabric-api.jar
    # VS2 Fabric bundles Fabric Language Kotlin; Architectury is bundled in our jar.
    fetch "$VS_MAVEN/valkyrienskies-120-fabric/$VS2/valkyrienskies-120-fabric-$VS2.jar" mods/valkyrienskies.jar
    CMD=(java -Xmx3G -jar server.jar nogui)
    ;;
  forge)
    FORGE="$(prop forge_version)"
    KFF="$(prop kotlin_forge)"
    fetch "https://maven.minecraftforge.net/net/minecraftforge/forge/$MC-$FORGE/forge-$MC-$FORGE-installer.jar" installer.jar
    java -jar installer.jar --installServer > installer.log 2>&1 || { tail -n 50 installer.log; exit 1; }
    rm -f installer.jar run.bat
    echo "-Xmx3G" >> user_jvm_args.txt
    fetch "https://thedarkcolour.github.io/KotlinForForge/thedarkcolour/kotlinforforge/$KFF/kotlinforforge-$KFF-all.jar" mods/kotlinforforge.jar
    fetch "$VS_MAVEN/valkyrienskies-120-forge/$VS2/valkyrienskies-120-forge-$VS2.jar" mods/valkyrienskies.jar
    CMD=(./run.sh nogui)
    ;;
  *)
    echo "Unknown loader: $LOADER" >&2
    exit 2
    ;;
esac

# Keep stdin open through a FIFO so "stop" can go in after the server is ready.
mkfifo console
exec 3<>console
# setsid gives the server its own process group, so a kill also reaches the java child of run.sh.
setsid "${CMD[@]}" <console > server.out 2>&1 &
PID=$!

RESULT=1
for ((i = 0; i < START_TIMEOUT; i += 2)); do
  if grep -q 'Done (' server.out; then
    RESULT=0
    break
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    break
  fi
  sleep 2
done

if [ "$RESULT" -eq 0 ]; then
  echo "stop" >&3
  timeout 120 tail --pid="$PID" -f /dev/null || kill -9 -- "-$PID" 2>/dev/null || true
  echo "$LOADER server started."
else
  kill -9 -- "-$PID" 2>/dev/null || true
  echo "$LOADER server did not start. Last log lines:"
  tail -n 80 server.out
fi
exit "$RESULT"
