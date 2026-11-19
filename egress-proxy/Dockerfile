# syntax=docker/dockerfile:1
# The only way out to the internet in the deployment Compose (ADR-0011, Amendments): a Squid
# forward proxy that lets the backend open a tunnel to the hosts of squid.conf and to nothing else.
# Alpine 3.24 like the web image, and the package pinned to an exact version, so the image is the
# same on every build. Alpine keeps only the latest revision of a package: when it publishes a
# security fix of squid, this build fails until the version here is updated, on purpose.
FROM alpine:3.24

RUN apk add --no-cache squid=7.6-r0

COPY --chown=root:root --chmod=0444 squid.conf /etc/squid/squid.conf

# The package's own unprivileged user, which owns /run/squid, /var/cache/squid and /var/log/squid.
USER squid
EXPOSE 3128

# -N: in the foreground, one process, so Docker sees it stop and its logs go to stdout and stderr.
ENTRYPOINT ["squid", "-N", "-f", "/etc/squid/squid.conf"]

LABEL org.opencontainers.image.source="https://github.com/Dalibex/urban-incidence-manager"
