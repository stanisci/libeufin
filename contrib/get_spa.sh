#!/bin/bash

# This script is in the public domain.  To be
# invoked by "make dl-spa".

if ! test -d .git; then
  echo Make sure that CWD is the repository top-level dir.
  exit 1
fi

if ls debian/usr/share/libeufin/demobank-ui/index.{html,css,js} &> /dev/null; then
  echo SPA download already, run 'make clean-spa' to remove it.
  exit 0
fi

if ! wget --version &> /dev/null; then
  echo wget not found, aborting.
  exit 1
fi

cp contrib/wallet-core/demobank/* debian/usr/share/libeufin/demobank-ui/
