#!/usr/bin/env bash
# Checks the egress proxy of the deployment Compose (egress-proxy/, ADR-0011, Amendments): the
# backend has no route to the internet, and this proxy is its only way out, for the hosts of
# squid.conf alone.
#
# The proxy runs on a network with the subnet and addresses of the internal network of
# docker-compose.yaml, and a client container asks it for tunnels from the backend's address and
# from others. The proxy resolves api.open-meteo.com to a documentation address (192.0.2.10) that
# answers nothing, so the check needs no internet: a refused tunnel is TCP_DENIED/403 at once, and
# the allowed one gets past the rules and fails to connect. That an allowed tunnel does reach
# Open-Meteo is checked by hand on a deployment (docs/deployment.md, Networks).
#
# Usage, from the repository root, with Docker running:
#   bash .github/scripts/check_egress_proxy.sh <egress proxy image>
# The CI runs it in the build job. Locally, build the image first, from egress-proxy/:
#   docker build -t urbanpulse-egress-proxy .
set -euo pipefail

image=${1:?usage: check_egress_proxy.sh <egress proxy image>}
client_image=curlimages/curl:8.11.1
prefix="egress-check-$$"
network="$prefix"
proxy="$prefix-proxy"

# The addresses of docker-compose.yaml (the lint job checks that squid.conf allows the backend's).
subnet=10.203.217.0/28
ip_range=10.203.217.0/29
backend_ip=10.203.217.11
proxy_ip=10.203.217.12
web_ip=10.203.217.10
other_ip=10.203.217.5
# Squid also fills its reverse DNS cache from /etc/hosts, so this address names api.open-meteo.com
# both ways: the case a tunnel to an IP address must not pass.
allowed_host=api.open-meteo.com
allowed_host_ip=192.0.2.10

# Git Bash on Windows would turn the paths of the commands into Windows paths.
export MSYS_NO_PATHCONV=1

cleanup() {
    docker rm -f "$proxy" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if ! docker run --rm --entrypoint squid "$image" -k parse -f /etc/squid/squid.conf; then
    echo "::error file=egress-proxy/squid.conf,title=Egress proxy::squid.conf is not valid; the lines above say where."
    exit 1
fi

docker network create --internal --subnet "$subnet" --ip-range "$ip_range" "$network" >/dev/null
# The same hardening as docker-compose.yaml, so a change that breaks it fails here.
docker run -d --name "$proxy" --network "$network" --ip "$proxy_ip" \
    --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    --tmpfs /run/squid:uid=31,gid=31 --tmpfs /var/cache/squid:uid=31,gid=31 \
    --tmpfs /var/log/squid:uid=31,gid=31 \
    --add-host "$allowed_host:$allowed_host_ip" \
    "$image" >/dev/null

for _ in $(seq 1 15); do
    if docker logs "$proxy" 2>&1 | grep -q 'Accepting HTTP Socket connections'; then
        break
    fi
    sleep 1
done
if ! docker logs "$proxy" 2>&1 | grep -q 'Accepting HTTP Socket connections'; then
    echo "::error title=Egress proxy::Squid did not start in 15 s."
    docker logs "$proxy"
    exit 1
fi

uid=$(docker exec "$proxy" id -u)
if [ "$uid" = "0" ]; then
    echo "::error file=egress-proxy/Dockerfile,title=Egress proxy::Squid runs as root."
    exit 1
fi

# Prints the status code the proxy gives to a request from the address given.
status_from() {
    local ip=$1 url=$2 tunnel=$3
    local -a args=(-s -o /dev/null -w '%{http_code}' --max-time 10 -x "http://$proxy_ip:3128")
    if [ "$tunnel" = "tunnel" ]; then
        args+=(-p)
    fi
    # curl answers 000 when the proxy refuses the CONNECT; the proxy log says 403 then.
    docker run --rm --network "$network" --ip "$ip" "$client_image" "${args[@]}" "$url" || true
}

failed=0
expect_refused() {
    local what=$1 ip=$2 url=$3 tunnel=$4 target=$5
    local code
    code=$(status_from "$ip" "$url" "$tunnel")
    # The log in a variable, not piped into grep -q: with pipefail, grep -q stopping early could make
    # docker logs fail on a closed pipe.
    local log
    log=$(docker logs "$proxy" 2>&1)
    if grep -qE "^[0-9.]+ +[0-9]+ ${ip//./\\.} TCP_DENIED/403 [0-9]+ [A-Z]+ ${target//./\\.}" <<<"$log"; then
        echo "Refused, as it must be: $what."
    else
        echo "::error file=egress-proxy/squid.conf,title=Egress proxy::Not refused: $what (curl answered $code)."
        failed=1
    fi
}

expect_refused "the backend asking for another host" \
    "$backend_ip" https://example.com/ tunnel "example.com:443"
expect_refused "the backend asking for Open-Meteo without TLS" \
    "$backend_ip" "http://api.open-meteo.com/v1/forecast" plain "http://api.open-meteo.com/v1/forecast"
expect_refused "the backend asking for a tunnel to Open-Meteo on another port" \
    "$backend_ip" "http://api.open-meteo.com:80/" tunnel "api.open-meteo.com:80"
expect_refused "another address asking for Open-Meteo" \
    "$other_ip" https://api.open-meteo.com/ tunnel "api.open-meteo.com:443"
expect_refused "nginx's address asking for Open-Meteo" \
    "$web_ip" https://api.open-meteo.com/ tunnel "api.open-meteo.com:443"
expect_refused "the backend asking for the IP address of Open-Meteo, whose reverse DNS names it" \
    "$backend_ip" "https://$allowed_host_ip/" tunnel "$allowed_host_ip:443"
expect_refused "the backend asking for an IPv6 address" \
    "$backend_ip" "https://[2001:db8::1]/" tunnel "\[2001:db8::1\]:443"

# The allowed tunnel must get past the rules (a squid.conf that refuses everything would pass the
# cases above). It cannot connect to the documentation address, so Squid logs it without
# TCP_DENIED.
status_from "$backend_ip" "https://$allowed_host/" tunnel >/dev/null
allowed=$(docker logs "$proxy" 2>&1 \
    | grep -E "^[0-9.]+ +[0-9]+ ${backend_ip//./\\.} [A-Z_]+/[0-9]+ [0-9]+ CONNECT ${allowed_host//./\\.}:443" || true)
if [ -n "$allowed" ] && ! grep -q 'TCP_DENIED' <<<"$allowed"; then
    echo "Allowed, as it must be: the backend asking for $allowed_host."
else
    echo "::error file=egress-proxy/squid.conf,title=Egress proxy::The backend's tunnel to $allowed_host was refused or not logged."
    failed=1
fi

docker stop --time 10 "$proxy" >/dev/null
code=$(docker inspect -f '{{.State.ExitCode}}' "$proxy")
if [ "$code" = "137" ]; then
    echo "::error title=12F-IX Disposability::Squid did not stop 10 s after docker stop and was killed."
    failed=1
fi

if [ "$failed" -ne 0 ]; then
    docker logs "$proxy" 2>&1 | tail -20
    exit 1
fi
echo "The egress proxy runs as UID $uid, refuses every tunnel but the allowed ones and stops on SIGTERM." \
    | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
