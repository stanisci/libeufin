#!/bin/bash

set -exu

HOWMANY_SECONDS=30
NO_LONG_POLL=0

if test $NO_LONG_POLL = 1; then
  echo "Requesting Taler history WITHOUT any long-poll in infinite loop..."
  while true; do
    curl -u test-user:x \
      "http://localhost:5001/facades/test-facade/taler-wire-gateway/history/incoming?delta=5"
  done
  exit
fi

echo "Requesting Taler history with $HOWMANY_SECONDS second(s) timeout in infinite loop..."
while true; do
  curl -v -u test-user:x \
    "http://localhost:5001/facades/test-facade/taler-wire-gateway/history/incoming?delta=5&long_poll_ms=${HOWMANY_SECONDS}000"
done
