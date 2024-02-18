#!/bin/bash
set -exuo pipefail

./bootstrap
./configure --prefix=/usr
make
