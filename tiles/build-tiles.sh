#!/bin/sh
# Builds the base map of Málaga (ADR-0016) into /tiles:
#   malaga.pmtiles   vector tiles, zoom 0-14, OpenMapTiles schema
#   style.json       OSM Bright, pointing at TILES_BASE_URL
#   sprite*, fonts/  the sprites and glyphs that the style uses
#
# The new tiles are written next to the old ones and replace them only if Planetiler succeeds,
# `pmtiles verify` accepts the file and it has a minimum size. If any step fails, the files in /tiles
# stay as they were and the process exits with an error.
set -eu

OUT_DIR=/tiles
FINAL="$OUT_DIR/malaga.pmtiles"
# Same folder as FINAL, so that the replacement is a rename and never leaves half a file.
NEW="$OUT_DIR/.malaga.new.pmtiles"
# Province of Málaga, as min longitude, min latitude, max longitude, max latitude.
BOUNDS="-5.62,36.28,-3.75,37.30"
# A clip that went wrong (wrong bounds, empty extract) gives a file far smaller than this.
MIN_BYTES=5000000
BASE_URL="${TILES_BASE_URL:?TILES_BASE_URL is not set}"
BASE_URL="${BASE_URL%/}"

log() { echo "tiles-builder: $*"; }

fail() {
    echo "tiles-builder: ERROR: $*. $FINAL and the style were not changed." >&2
    exit 1
}

# Removes the partial file on any exit, including a failure or Ctrl+C.
trap 'rm -f "$NEW" "$OUT_DIR/.style.new.json"' EXIT
rm -f "$NEW"

# Downloads the Andalucía extract from Geofabrik and the other sources of the OpenMapTiles profile
# (water polygons, Natural Earth, lake centerlines) into /data/sources, a volume kept between runs.
# refresh_sources downloads a source again only when it changed, so every run uses current OSM data.
log "building $NEW (Andalucía extract clipped to $BOUNDS)"
java -XX:MaxRAMPercentage=75 -jar /opt/planetiler/planetiler.jar \
    --area=andalucia \
    --bounds="$BOUNDS" \
    --download \
    --refresh_sources \
    --download_dir=/data/sources \
    --tmpdir=/data/tmp \
    --languages=es,en \
    --output="$NEW" \
    || fail "Planetiler failed"

log "checking $NEW"
pmtiles verify "$NEW" || fail "pmtiles verify rejected the new file"
size=$(wc -c < "$NEW")
[ "$size" -ge "$MIN_BYTES" ] || fail "the new file has $size bytes, less than the minimum of $MIN_BYTES"

mv -f "$NEW" "$FINAL"
log "replaced $FINAL ($size bytes)"

cp /opt/style/sprite* "$OUT_DIR/"
cp -R /opt/style/fonts "$OUT_DIR/"
sed "s|__TILES_BASE_URL__|$BASE_URL|g" /opt/style/style.json.template > "$OUT_DIR/.style.new.json"
mv -f "$OUT_DIR/.style.new.json" "$OUT_DIR/style.json"
log "wrote $OUT_DIR/style.json for $BASE_URL"

# The container runs as root: hand the files back to the owner of the folder, so the developer can
# delete them without sudo. On Docker Desktop the bind mount ignores it.
chown -R "$(stat -c '%u:%g' "$OUT_DIR")" "$OUT_DIR" 2>/dev/null || true
