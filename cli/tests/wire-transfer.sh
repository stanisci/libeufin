#!/bin/bash

set -eux

# Pays the www Sandbox user, using one reserve pub
# as the subject -- _in case_ Taler is being tested.
RESERVE_PUB=$(gnunet-ecc -g1 /tmp/www &> /dev/null && gnunet-ecc -p /tmp/www)
# Must match the one from launch_services.sh
export LIBEUFIN_SANDBOX_DB_CONNECTION="jdbc:postgresql://localhost:5432/libeufincheck?user=$(whoami)"
libeufin-sandbox \
  make-transaction \
    --credit-account=sandbox-user \
    --debit-account=admin MANA:2 \
   $RESERVE_PUB
