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
*/.gradle/*
*/contrib/ci/*
*/contrib/wallet-core/*
*/frontend/*
*/build/*
*/*.xsd
*/*.xml
*/ebics/src/test/kotlin/EbicsOrderUtilTest.kt
*/common/src/main/kotlin/TalerErrorCode.kt
EOF
);

echo Current directory: `pwd`

codespell -I "${job_dir}"/dictionary.txt -S ${skip//$'\n'/,}
