#!/bin/bash
set -exuo pipefail

apt-get update -yq
apt-get upgrade -yq

job_dir=$(dirname "${BASH_SOURCE[0]}")

"${job_dir}"/build.sh
