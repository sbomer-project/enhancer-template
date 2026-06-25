#!/usr/bin/env bash

# This script builds the schema, then tears down and rebuilds
# the local helm development environment.
#
# It is intended to be run from the root of the project.

set -e

PROFILE=sbomer
NAMESPACE=sbomer-test
PLATFORM_REPO="https://github.com/sbomer-project/sbomer-platform.git"
PLATFORM_DIR="sbomer-platform"
LOCAL_CHART_PATH="helm/example-enhancer-chart"

echo "--- Checking Minikube status (Profile: $PROFILE) ---"

if ! minikube -p "$PROFILE" status > /dev/null 2>&1; then
    echo "❌ Error: Minikube cluster '$PROFILE' is NOT running."
    echo "Please run the setup script first to start the cluster and install dependencies:"
    echo "./hack/setup-minikube.sh"
    exit 1
fi

echo "--- Building local example-enhancer image inside of Minikube ---"
bash ./hack/build-local-example-enhancer-into-minikube.sh

echo "--- Setting up SBOMer Platform Chart ---"

if [ ! -d "$PLATFORM_DIR/.git" ]; then
    echo "sbomer-platform git tracking not found. Ensuring clean setup..."
    if [ -d "$PLATFORM_DIR" ]; then
        rm -rf "$PLATFORM_DIR"
    fi
    echo "Cloning sbomer-platform..."
    git clone "$PLATFORM_REPO" "$PLATFORM_DIR"
else
    echo "sbomer-platform directory exists and is a valid git repository, updating..."
    git -C "$PLATFORM_DIR" pull
fi

echo "--- Patching Chart.yaml to use Local Dependency ---"

# Setup Paths
ABS_CHART_PATH="file://$(pwd)/$LOCAL_CHART_PATH"
TARGET_CHART_FILE="$PLATFORM_DIR/Chart.yaml"
LOCAL_SOURCE_FILE="$LOCAL_CHART_PATH/Chart.yaml"

# Extract the Version from the LOCAL component chart
NEW_VERSION=$(grep "^version:" "$LOCAL_SOURCE_FILE" | head -n 1 | awk '{print $2}' | tr -d '"')

if [ -z "$NEW_VERSION" ]; then
    echo "❌ Error: Could not detect version from $LOCAL_SOURCE_FILE"
    exit 1
fi

echo "Detected Local Version: $NEW_VERSION"
echo "Targeting Chart file:   $TARGET_CHART_FILE"

# Apply the Upsert Patch using Python (Safe & Independent of PyYAML)
python3 -c "
import sys

chart_file = '$TARGET_CHART_FILE'
target_name = 'example-enhancer-chart'
new_repo = '$ABS_CHART_PATH'
new_version = '$NEW_VERSION'

with open(chart_file, 'r') as f:
    content = f.read()

lines = content.splitlines()

# If the dependency is completely missing, inject it at the top of the dependencies block
if f'- name: {target_name}' not in content:
    print(f'Dependency {target_name} not found. Injecting dependency entry...')
    dep_idx = -1
    for i, line in enumerate(lines):
        if line.strip() == 'dependencies:':
            dep_idx = i
            break

    if dep_idx != -1:
        lines.insert(dep_idx + 1, f'  - name: {target_name}')
        lines.insert(dep_idx + 2, f'    version: {new_version}')
        lines.insert(dep_idx + 3, f'    repository: \"{new_repo}\"')
    else:
        # Fallback if dependencies block doesn't exist at all
        lines.append('dependencies:')
        lines.append(f'  - name: {target_name}')
        lines.append(f'    version: {new_version}')
        lines.append(f'    repository: \"{new_repo}\"')
else:
    # If the dependency exists, update its version and repository path in place
    print(f'Dependency {target_name} found. Updating inline values...')
    in_block = False
    for i, line in enumerate(lines):
        if '- name:' in line:
            in_block = target_name in line
        if in_block:
            if 'repository:' in line:
                indent = line.split('repository:')[0]
                lines[i] = f'{indent}repository: \"{new_repo}\"'
            elif 'version:' in line:
                indent = line.split('version:')[0]
                lines[i] = f'{indent}version: {new_version}'

with open(chart_file, 'w') as f:
    f.write('\n'.join(lines) + '\n')
"

# Verify the patch
if grep -q "$ABS_CHART_PATH" "$TARGET_CHART_FILE"; then
    echo "✅ SUCCESS: Chart.yaml was patched and injected correctly."
else
    echo "❌ ERROR: Failed to patch Chart.yaml!"
    exit 1
fi

# Update dependencies to pull the local chart
echo "Updating Helm dependencies..."
helm dependency update "$PLATFORM_DIR"

# Install/Upgrade with overrides
echo "--- Deploying to Minikube ---"
helm upgrade --install sbomer-release "./$PLATFORM_DIR" \
    --namespace $NAMESPACE \
    --create-namespace \
    --set global.includeKafka=true \
    --set global.includeApicurio=true \
    --set global.includeApiGateway=true \
    --set global.includeS3=true \
    --set global.includeOtelLgtm=true \
    --set example-enhancer-chart.image.repository=localhost/example-enhancer \
    --set example-enhancer-chart.image.tag=latest \
    --set example-enhancer-chart.image.pullPolicy=Never \
    --set example-enhancer-chart.config.kafka.bootstrapServers=sbomer-release-kafka:9092 \
    --set example-enhancer-chart.config.kafka.schemaRegistryUrl=http://sbomer-release-apicurio:8080/apis/registry/v2 \
    --set example-enhancer-chart.config.storage.internalUrl=http://sbomer-release-manifest-storage-service-chart:8080 \
    --set example-enhancer-chart.config.otel.endpoint=http://sbomer-release-otel-lgtm:4317 \
    --set example-enhancer-chart.config.otel.protocol=grpc

echo "--- Forcing Rolling Restart to pick up new local image ---"
kubectl rollout restart deployment -n $NAMESPACE -l app.kubernetes.io/name=example-enhancer-chart || true

echo "--- Deployment Complete ---"
echo "You can check status with: kubectl get pods -n $NAMESPACE"
echo "You can port-forward with: kubectl port-forward svc/sbomer-release-gateway 8080:8080 -n $NAMESPACE"