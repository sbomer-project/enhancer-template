# SBOMer Enhancer Template

This repository is a [Copier](https://copier.readthedocs.io/) template for generating SBOMer Enhancer microservices. Enhancers are Quarkus-based event-driven applications that consume SBOM URLs from Kafka `enhancement.created` events, mutate or append metadata (e.g. using CycloneDX), and upload the results back to SBOMer's manifest storage platform.

## Repository Structure

To prevent Jinja templating errors in IDEs and to keep the Java codebase compilable.

* `java-project/`: **Code Here.** The compilable Java source code and Helm charts. Use this directory for all active development. **Do not change the "Example Enhancer" and "example-enhancer" references in the project. These are used to inject template texts later upon generation.**
* `generate-template.sh`: The build script that injects Jinja variables into the source code and outputs the final Copier template.
* `copier.yml`: The configuration file containing questions and variables for Copier.
* `generated_template/`: *(Auto-generated)* The final, pure Copier skeleton. Do not edit files here directly.
* `generated-test-project/`: *(Auto-generated)* A live, functioning test project (`test-enhancer`) stamped out by the script for immediate Minikube deployment.

## 🛠️ Prerequisites

To develop or generate projects from this template, you need:
* **Python 3.8+** & **Copier** (`pip install copier`)
* **Java 17+** & **Maven**
* **Docker** or **Podman**
* **Minikube** & **Helm** (for local cluster testing)

## 🏗️ Template Development Workflow

For modifying the base template (e.g., adding new Java classes, updating the Helm chart, changing adapter logic), we follow this loop:

1. **Write Code:** Make the changes inside the `java-project/` directory. Our IDE will work perfectly here.
2. **Build the Template & Test Sandbox:** Run the generation script from the repository root:
   ```bash
   ./generate-template.sh
   ```
3. **Deploy to Minikube:** Move into the newly generated test sandbox and trigger the local deployment script:
   ```bash
   cd generated-test-project
   ./hack/run-helm-with-local-build.sh
   ```
Note: This script will automatically build the enhancer image inside the Minikube registry, upsert the dependency into the sbomer-platform umbrella chart, and perform a rolling restart.

## Creating a New Enhancer

The actual Copier template lives inside the `generated_template` folder. When stamping out a brand-new, permanent Enhancer repository, we must point Copier directly to that subdirectory.

1. **Ensure the template is up to date:**
   Run the generation script to make sure `generated_template` has the latest changes from `java-project`.
   ```bash
   ./generate-template.sh
   ```
2. **Run Copier:** Point the Copier CLI to the generated_template directory and specify your target destination folder:
   ```bash
   # If we are inside this repository's root:
   copier copy ./generated_template ../my-new-enhancer
   ```
3. **Answer the Prompts:** The CLI will ask us for our project's human-readable name and machine-readable slug.
4. **Start Coding:** We then navigate to the my-new-enhancer directory, initialize a new Git repository, and begin our business logic implementation.