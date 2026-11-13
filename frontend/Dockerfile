# syntax=docker/dockerfile:1

# Web image (ADR-0017): nginx serves the production build of apps/web and the base map, and
# proxies /api/ to the backend. In the deployment Compose it is the only service that publishes a
# port. The build context is frontend/:
#   docker build -t urbanpulse-web .
# What nginx does with each path is in nginx.conf.
#
# Node is the version of .nvmrc and pnpm the one of packageManager in package.json, the same as the
# CI. When .nvmrc changes, change the Node tag too.

FROM node:24.22.2-alpine3.24 AS build
# Corepack installs the pnpm of packageManager without asking.
ENV COREPACK_ENABLE_DOWNLOAD_PROMPT=0
RUN corepack enable
WORKDIR /workspace
# The manifests first, so a change in the code does not download the dependencies again. The
# manifest of the mobile app is only there because the lockfile lists it.
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/web/package.json apps/web/
COPY apps/mobile/package.json apps/mobile/
COPY packages/api-client/package.json packages/api-client/
COPY packages/domain/package.json packages/domain/
COPY packages/ui/package.json packages/ui/
# Only the web app and the packages it uses: the dependencies of the mobile app stay out.
# --frozen-lockfile fails if package.json and pnpm-lock.yaml disagree, as in the CI.
RUN --mount=type=cache,id=pnpm-store,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile --filter "@urbanpulse/web..."
COPY tsconfig.base.json ./
COPY packages packages
COPY apps/web apps/web
# The bundle calls the relative /api/v1, so the same build works behind any host (12-factor III).
RUN pnpm --filter @urbanpulse/web build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24 AS runtime
LABEL org.opencontainers.image.source="https://github.com/Dalibex/urban-incidence-manager"
# Replaces the server of the image. Its own nginx.conf includes this file inside http {}, writes
# the logs to stdout and stderr and keeps its temporary files in /tmp.
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Owned by root, so the process can read the files but not change them.
COPY --from=build /workspace/apps/web/dist /usr/share/nginx/html
# The base map is not in the image: the deployment Compose mounts the tiles volume on /srv/tiles.
# The process never runs as root (UID 101 of the base image), so it listens on 8080, not on 80.
USER nginx
EXPOSE 8080
