#!/usr/bin/bash

set -e

./test-ebics-backup.py
./test-ebics-highlevel.py
./test-ebics.py
./test-sandbox.py
./test-taler-facade.py
./test-bankConnection.py
./test-ebics-double-payment-submission.py
echo "All tests passed."
