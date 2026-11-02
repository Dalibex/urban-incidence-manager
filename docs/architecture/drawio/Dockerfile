# syntax=docker/dockerfile:1

# Generator of UrbanPulse-C4.drawio from workspace.dsl. Run it with Compose, from the repository
# root:
#   docker compose -f docs/architecture/compose.yaml run --rm drawio
# What it does is in generate.mjs.
#
# Everything is pinned, so the same DSL gives the same file on every machine and in CI: the images
# by tag and digest, the Alpine packages by version. The digest wins over the tag, which an image
# can move. When Alpine drops a pinned version the build fails; update the versions together,
# regenerate the file and commit both.

# Structurizr parses the DSL and exports the workspace as JSON. Its image has no shell, so the
# generator copies the application and runs it with its own Java.
FROM structurizr/structurizr:2026.09.19@sha256:721136283c2f9cf1ba69037bc9de136c579d66fdcb2d771cb60546ec68def1a5 AS structurizr

# Node is the version of frontend/.nvmrc.
FROM node:24.16.0-alpine3.22@sha256:191c9f0080fcbbc6547a85dc0ff7988072214a355aabdc1d2ec55a7dae5eea8a
# Graphviz computes where each box and arrow goes; DejaVu gives it the same font metrics on every
# machine. Structurizr 2026 needs Java 21, the version of its own image.
RUN apk add --no-cache \
    graphviz=12.2.1-r0 \
    font-dejavu=2.37-r6 \
    openjdk21-jre-headless=21.0.11_p10-r0
COPY --from=structurizr /usr/local/structurizr.war /opt/structurizr/structurizr.war
COPY generate.mjs /opt/drawio/generate.mjs
WORKDIR /workspace
ENTRYPOINT ["node", "/opt/drawio/generate.mjs"]
