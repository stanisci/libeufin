#!/bin/bash
set -exvuo pipefail

# Requires podman
# Fails if not found in PATH
OCI_RUNTIME=$(which podman)
REPO_NAME=$(basename "${PWD}")
JOB_NAME="${1}"
JOB_ARCH=$((grep CONTAINER_ARCH contrib/ci/jobs/${JOB_NAME}/config.ini | cut -d' ' -f 3) || echo "${2:-amd64}")
JOB_CONTAINER=$((grep CONTAINER_NAME contrib/ci/jobs/${JOB_NAME}/config.ini | cut -d' ' -f 3) || echo "localhost/${REPO_NAME}:${JOB_ARCH}")
CONTAINER_BUILD=$((grep CONTAINER_BUILD contrib/ci/jobs/${JOB_NAME}/config.ini | cut -d' ' -f 3) || echo "True")

echo "Image name: ${JOB_CONTAINER}"

if [ "${CONTAINER_BUILD}" = "True" ] ; then
	"${OCI_RUNTIME}" build \
		--arch "${JOB_ARCH}" \
		-t "${JOB_CONTAINER}" \
		-f contrib/ci/Containerfile .
fi

"${OCI_RUNTIME}" run \
	--rm \
	-ti \
	--arch "${JOB_ARCH}" \
	--env CI_COMMIT_REF="$(git rev-parse HEAD)" \
	--volume "${PWD}":/workdir \
	--workdir /workdir \
	"${JOB_CONTAINER}" \
	contrib/ci/jobs/"${JOB_NAME}"/job.sh

top_dir=$(dirname "${BASH_SOURCE[0]}")

#"${top_dir}"/build.sh
