#!/bin/sh
set -eu

NATS_URL="tls://nats:4222"
NATS_CREDS="/run/secrets/nats-stream-admin.creds"
NATS_CA="/run/secrets/nats-ca.crt"
STREAM_CONFIG="/config/cookie-events.json"
STREAM_NAME="COOKIE_EVENTS"

nats_cli() {
  nats \
    --server="${NATS_URL}" \
    --creds="${NATS_CREDS}" \
    --tlsca="${NATS_CA}" \
    --timeout=5s \
    "$@"
}

if nats_cli stream info "${STREAM_NAME}" >/dev/null 2>&1; then
  echo "Converging existing ${STREAM_NAME} stream"
  nats_cli stream edit "${STREAM_NAME}" --config="${STREAM_CONFIG}" --force
else
  echo "Creating ${STREAM_NAME} stream"
  nats_cli stream add --config="${STREAM_CONFIG}"
fi

# Confirm authenticated access to the stream before Identity is restarted.
nats_cli stream info "${STREAM_NAME}" >/dev/null
echo "${STREAM_NAME} is ready"
