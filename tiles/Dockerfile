# syntax=docker/dockerfile:1

# Base map builder (ADR-0016): a one-off admin process (12-factor XII) that builds malaga.pmtiles
# and the map style. It is run by Compose, never started with `up`:
#   docker compose --profile tiles run --rm tiles-builder
# What it does at run time is in build-tiles.sh.
#
# Every input is pinned (12-factor II): the base images by tag, and Planetiler, the style and the
# fonts by release and checksum. To update one, change its version and its checksum together.

# The style, sprites and fonts are prepared when the image is built, so the run downloads only the
# map data.
FROM alpine:3.22 AS style
RUN apk add --no-cache jq
# OSM Bright, an OpenMapTiles style, so it matches the tiles of Planetiler's default profile.
ADD --checksum=sha256:8eea49634d5a99125657162e4ddd4e4fba15b75f67512b9cc66ea7453d386d1b \
    https://github.com/openmaptiles/osm-bright-gl-style/releases/download/v1.11/v1.11.zip /tmp/osm-bright.zip
# Glyphs of the three fonts that OSM Bright uses (Noto Sans Regular, Bold and Italic).
ADD --checksum=sha256:d117316544b43a5dde7ee761b36e17701e9f85574e181d76a74814240fdbaf34 \
    https://github.com/openmaptiles/fonts/releases/download/v2.0/noto-sans.zip /tmp/noto-sans.zip
COPY style.jq /tmp/style.jq
RUN mkdir -p /style/fonts /tmp/osm-bright \
    && unzip -q /tmp/osm-bright.zip -d /tmp/osm-bright \
    && cp /tmp/osm-bright/sprite.json /tmp/osm-bright/sprite.png \
        /tmp/osm-bright/sprite@2x.json /tmp/osm-bright/sprite@2x.png /style/ \
    && unzip -q /tmp/noto-sans.zip -d /style/fonts \
    && jq -f /tmp/style.jq /tmp/osm-bright/style-local.json > /style/style.json.template

# Same Java base image as the backend (backend/Dockerfile).
FROM eclipse-temurin:25.0.4.1_1-jre-noble
LABEL org.opencontainers.image.source="https://github.com/Dalibex/urban-incidence-manager"
# pmtiles CLI, for `pmtiles verify` before the new file replaces the old one.
COPY --from=protomaps/go-pmtiles:v1.31.2 /go-pmtiles /usr/local/bin/pmtiles
ADD --checksum=sha256:f310bd0413e2e4512b27f4046d418664e8e1d3bf31603c2a70e23de06c167e4d \
    https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar /opt/planetiler/planetiler.jar
COPY --from=style /style /opt/style
COPY --chmod=755 build-tiles.sh /usr/local/bin/build-tiles
WORKDIR /data
# Runs as root on purpose: it writes into a folder of the developer's checkout (a bind mount) and
# gives the files back to that folder's owner at the end. It opens no port and runs only on demand.
ENTRYPOINT ["/usr/local/bin/build-tiles"]
