#!/usr/bin/env bash
# The bench's server: vanilla 1.21.11, flat creative world, offline logins, RCON on 127.0.0.1.
# Put server.jar (1.21.11) and a server.properties made from server.properties.example into
# $BENCH_SERVER_DIR (default ./server), accept the EULA there, set rcon.password and keep the same
# password in rcon.secret here (or RCON_PASSWORD). JAVA: a Java 21 (default: java on PATH).
cd "${BENCH_SERVER_DIR:-$(dirname "$0")/server}" || exit 1
exec "${JAVA:-java}" -Xms1G -Xmx2G -jar server.jar nogui
