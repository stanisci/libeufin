#!/bin/bash

# This script is in the public domain.  It copies
# SPA from where the bootstrap script places it to
# the debian directory.

if ! test -d .git; then
  echo Run this script from the repository top-level directory.
  exit 1
fi

cp contrib/wallet-core/bank/* debian/usr/share/libeufin/demobank-ui/
