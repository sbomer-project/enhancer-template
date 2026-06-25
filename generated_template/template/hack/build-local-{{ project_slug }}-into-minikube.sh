#!/bin/bash

# Exit immediately if a command fails
set -e

# Variables for minikube profile and {{ project_slug }}
SBOM_SERVICE_IMAGE="{{ project_slug }}:latest"
PROFILE="sbomer"
TAR_FILE="{{ project_slug }}.tar"

echo "--- Building and inserting {{ project_slug }} image into Minikube registry ---"

bash ./hack/build-with-schemas.sh prod

podman build --format docker -t "$SBOM_SERVICE_IMAGE" -f src/main/docker/Dockerfile.jvm .

echo "--- Exporting {{ project_slug }} image to archive ---"
if [ -f "$TAR_FILE" ]; then
    rm "$TAR_FILE"
fi
podman save -o "$TAR_FILE" "$SBOM_SERVICE_IMAGE"

echo "--- Loading {{ project_slug }} into Minikube ---"
# This sends the file to Minikube
minikube -p "$PROFILE" image load "$TAR_FILE"

echo "--- Cleanup ---"
rm "$TAR_FILE"

echo "Done! Image '$SBOM_SERVICE_IMAGE' is ready in cluster '$PROFILE'."