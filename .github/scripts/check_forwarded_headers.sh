#!/usr/bin/env bash
# Checks that the web image never lets a client's own X-Forwarded-* headers reach the backend
# (#200, T13 of #144). The backend trusts those headers in the deployment, for the rate limits and
# the Location of a 201, so nginx must write every one of them itself on every proxied path.
#
# First, any proxy_set_header outside the server level fails at once: in a location, nginx then
# drops every proxy_set_header of the server on that path. This covers the locations added later.
#
# Then the web image runs in front of a stub called "backend" that answers with the headers it receives.
# The stub and the client are the same image, so the check needs no other one. A client container
# sends forged headers to each kind of proxied path, and the check fails unless the stub received
# the values of nginx: the client's own address, the host and port of its Host header and http,
# and no Forwarded or X-Forwarded-Prefix at all.
#
# Usage, from the repository root, with Docker running:
#   bash .github/scripts/check_forwarded_headers.sh <web image>
# The CI runs it in the build job. Locally, build the image first, from frontend/:
#   docker build -t urbanpulse-web .
set -euo pipefail

image=${1:?usage: check_forwarded_headers.sh <web image>}
prefix="forwarded-check-$$"
network="$prefix"

# Git Bash on Windows would turn the paths inside the stub configuration into Windows paths.
export MSYS_NO_PATHCONV=1

# A whole nginx configuration, because the default.conf of the web image belongs to root. The
# temporary paths and the pid go to /tmp, the only place the unprivileged user can write.
stub_conf='pid /tmp/stub.pid;
events {}
http {
    client_body_temp_path /tmp/client_temp;
    proxy_temp_path /tmp/proxy_temp;
    fastcgi_temp_path /tmp/fastcgi_temp;
    uwsgi_temp_path /tmp/uwsgi_temp;
    scgi_temp_path /tmp/scgi_temp;
    server {
        listen 8080;
        location / {
            default_type text/plain;
            return 200 "for=$http_x_forwarded_for\nhost=$http_x_forwarded_host\nport=$http_x_forwarded_port\nproto=$http_x_forwarded_proto\nhost_header=$http_host\nforwarded=$http_forwarded\nprefix=$http_x_forwarded_prefix\n";
        }
    }
}'

cleanup() {
    docker rm -f "$prefix-web" "$prefix-backend" >/dev/null 2>&1 || true
    docker network rm "$network" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# The configuration the image really runs (nginx -T), one file after another. The scan keeps the
# stack of open blocks and prints every proxy_set_header whose innermost block is not server or
# http: inside a location, an if or any other block, however its braces are laid out. Comments
# and quoted values are skipped, so a brace or a semicolon inside them does not count.
in_location=$(docker run --rm "$image" nginx -T 2>/dev/null | awk '
    {
        for (i = 1; i <= length($0); i++) {
            c = substr($0, i, 1)
            if (quote != "") {
                if (c == "\\") i++
                else if (c == quote) quote = ""
                continue
            }
            if (c == "#") break
            if (c == "\"" || c == "\047") { quote = c; continue }
            if (c == "{") {
                split(statement, words, " ")
                block[++depth] = words[1]
                statement = ""
                continue
            }
            if (c == "}") { depth--; statement = ""; continue }
            if (c == ";") {
                if (statement ~ /^[[:space:]]*proxy_set_header[[:space:]]/ && depth > 0 \
                    && block[depth] != "server" && block[depth] != "http") {
                    gsub(/[[:space:]]+/, " ", statement)
                    print "inside " block[depth] ":" statement
                }
                statement = ""
                continue
            }
            statement = statement c
        }
        statement = statement " "
    }
')
if [ -n "$in_location" ]; then
    echo "::error file=frontend/nginx.conf,title=nginx forwarded headers::A proxy_set_header outside the server level, such as inside a location, makes nginx drop the X-Forwarded-* headers of the server there, so the client's own values reach the backend. Move it to the server level:"
    echo "$in_location"
    exit 1
fi

docker network create "$network" >/dev/null
docker run -d --name "$prefix-backend" --network "$network" --network-alias backend \
    -e STUB_CONF="$stub_conf" --entrypoint sh "$image" \
    -c 'printf "%s" "$STUB_CONF" > /tmp/stub.conf && exec nginx -c /tmp/stub.conf -g "daemon off;"' >/dev/null
docker run -d --name "$prefix-web" --network "$network" "$image" >/dev/null

# Runs curl from a client container on the same network. Its first output line is the address of
# the client, which nginx must pass on as X-Forwarded-For.
client() {
    docker run --rm --network "$network" --entrypoint sh "$image" -c \
        'hostname -i | cut -d" " -f1 && curl -sS --max-time 10 "$@"' sh "$@"
}

ready=false
for _ in $(seq 1 15); do
    # The output is kept first: grep -q in a pipe could close it while docker run still writes.
    answer=$(client "http://$prefix-web:8080/api/v1/incidents" 2>/dev/null || true)
    if echo "$answer" | grep -q '^for='; then
        ready=true
        break
    fi
    sleep 2
done
if [ "$ready" != true ]; then
    echo "::error title=nginx forwarded headers::The stub backend did not answer through nginx after 15 attempts."
    docker logs "$prefix-web" || true
    docker logs "$prefix-backend" || true
    exit 1
fi

forged=(
    -H "X-Forwarded-For: 1.1.1.1"
    -H "X-Forwarded-Host: evil.example"
    -H "X-Forwarded-Port: 9999"
    -H "X-Forwarded-Proto: https"
    -H "X-Forwarded-Prefix: /evil"
    -H "Forwarded: for=1.1.1.1;host=evil.example;proto=https"
)
failures=0

# $1 method, $2 path, $3 Host header, $4 expected host, $5 expected port.
check() {
    local method=$1 path=$2 host=$3 expected_host=$4 expected_port=$5
    local output address received
    output=$(client -X "$method" -H "Host: $host" "${forged[@]}" "http://$prefix-web:8080$path")
    address=$(echo "$output" | head -n 1)
    received=$(echo "$output" | tail -n +2)

    local expected="for=$address
host=$expected_host
port=$expected_port
proto=http
host_header=$expected_host
forwarded=
prefix="

    if [ "$received" = "$expected" ]; then
        echo "$method $path with Host: $host → the backend got the values of nginx."
    else
        echo "::error file=frontend/nginx.conf,title=nginx forwarded headers::$method $path with Host: $host let the client's headers reach the backend. Keep every proxy_set_header at the server level of nginx.conf, never inside a location."
        echo "Expected:"
        echo "$expected"
        echo "Received:"
        echo "$received"
        failures=$((failures + 1))
    fi
}

# One path of each proxied location: the identity operations, the rest of the API and the API
# documentation. A Host without a port means the default port of http.
check POST /api/v1/sessions "localhost:8000" localhost 8000
check GET /api/v1/incidents "localhost:8000" localhost 8000
check GET /v3/api-docs "localhost:8000" localhost 8000
check GET /api/v1/incidents "urbanpulse.example" urbanpulse.example 80

if [ "$failures" -gt 0 ]; then
    exit 1
fi
echo "nginx replaces the X-Forwarded-* headers of the client on every proxied path." | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
