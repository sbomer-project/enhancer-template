#!/usr/bin/env bash

set -e

# ==========================================
# Dependency Check
# ==========================================
if ! command -v copier &> /dev/null; then
    echo "⚠️ ERROR: copier is not installed!"
    echo ""
    echo "Please install copier using pip:"
    echo "  pip install -r requirements.txt"
    echo ""
    echo "Or manually:"
    echo "  pip install copier"
    echo ""
    echo "For more information, see: https://github.com/copier-org/copier"
    exit 1
fi

# ==========================================
# Dynamic Directory Resolution
# ==========================================
# Since the script is in the root, the script's directory IS the root directory
ROOT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

OUTPUT_ROOT="$ROOT_DIR/generated_template"
TEMPLATE_DIR="$OUTPUT_ROOT/template"
JAVA_SRC_DIR="$ROOT_DIR/java-project"
GENERATED_PROJECT_DIR="$ROOT_DIR/generated-test-project"

# Configuration for the test run
TEST_PROJECT_NAME="Local Test Enhancer"
TEST_PROJECT_SLUG="test-enhancer"

echo "--- 🧹 Cleaning up old generated template and test project ---"
rm -rf "$OUTPUT_ROOT"
mkdir -p "$TEMPLATE_DIR"

rm -rf "$GENERATED_PROJECT_DIR"
mkdir -p "$GENERATED_PROJECT_DIR"

echo "--- 🧹 Cleaning up temporary repositories in java-project ---"
# Remove platform and contracts folders if they exist in the source directory
rm -rf "$JAVA_SRC_DIR/sbomer-contracts"
rm -rf "$JAVA_SRC_DIR/sbomer-platform"

echo "--- 📄 Setting up copier.yml ---"
if [ -f "$ROOT_DIR/copier.yml" ]; then
    cp "$ROOT_DIR/copier.yml" "$OUTPUT_ROOT/"
else
    echo "⚠️ ERROR: copier.yml not found in the root directory ($ROOT_DIR)!"
    exit 1
fi

echo "--- 📦 Copying source code from java-project ---"
# Sync exclusively from the java-project folder
# Added explicit excludes for the sub-repos as a failsafe
rsync -a --exclude=".git" \
         --exclude="target" \
         --exclude=".idea" \
         --exclude=".vscode" \
         --exclude="*.iml" \
         --exclude="sbomer-contracts" \
         --exclude="sbomer-platform" \
         "$JAVA_SRC_DIR/" "$TEMPLATE_DIR/"

echo "--- 📝 Injecting Copier variables into files ---"
find "$TEMPLATE_DIR" -type f -exec sed -i.bak "s/Example Enhancer/{{ project_name }}/g" {} +
find "$TEMPLATE_DIR" -type f -exec sed -i.bak "s/example-enhancer/{{ project_slug }}/g" {} +
find "$TEMPLATE_DIR" -type f -exec sed -i.bak "s/exampleenhancer/{{ project_slug | replace('-', '') }}/g" {} +

# Clean up the backup files created by sed
find "$TEMPLATE_DIR" -name "*.bak" -type f -delete

echo "--- 📂 Renaming files and directories ---"
find "$TEMPLATE_DIR" -depth -name "*example-enhancer*" | while read -r item; do
    new_item=$(echo "$item" | sed "s/example-enhancer/{{ project_slug }}/g")
    mv "$item" "$new_item"
done

find "$TEMPLATE_DIR" -depth -name "*exampleenhancer*" | while read -r item; do
    new_item=$(echo "$item" | sed "s/exampleenhancer/{{ project_slug | replace('-', '') }}/g")
    mv "$item" "$new_item"
done

echo "--- ✅ Template generation complete! ---"
echo "Your Copier template is ready inside: $OUTPUT_ROOT"

echo "--- 🏗️ Rendering Test Project with Copier ---"
copier copy "$OUTPUT_ROOT" "$GENERATED_PROJECT_DIR" \
    -d project_name="$TEST_PROJECT_NAME" \
    -d project_slug="$TEST_PROJECT_SLUG" \
    --defaults \
    --trust

echo "--- ✅ Test Project generation complete! ---"
echo "Your test project is ready inside: $GENERATED_PROJECT_DIR"
echo ""
echo "To deploy to Minikube, simply run:"
echo "  cd generated-test-project"
echo "  ./hack/run-helm-with-local-build.sh"