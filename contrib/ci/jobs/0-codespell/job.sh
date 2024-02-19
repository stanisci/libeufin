#!/bin/bash
set -exuo pipefail

job_dir=$(dirname "${BASH_SOURCE[0]}")

skip=$(cat <<EOF
ABOUT-NLS
configure
config.guess
configure~
*/debian/*
*/debian/.debhelper/*
*/doc/prebuilt/*
*/.git/*
*/contrib/ci/*
*/contrib/wallet-core/*
*/contrib/frontend/*
*/build/*
*/*.xsd
*/*.xml
*/ebics/src/test/kotlin/EbicsOrderUtilTest.kt
EOF
);

echo Current directory: `pwd`

codespell -I "${job_dir}"/dictionary.txt -S ${skip//$'\n'/,}
