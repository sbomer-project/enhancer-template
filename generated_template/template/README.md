# {{ project_name }}

The **{{ project_name }}** is a dedicated microservice within the SBOMer NextGen architecture. Its primary responsibility is to consume target CycloneDX SBOMs from the orchestration pipeline, apply specific enhancements, and re-upload the modified assets back to the central storage layer.

---

## Business Logic

> **TODO for Developers:**
> Describe the specific business logic of the {{ project_name }} here. 
> * What specific data does it fetch, analyze, or mutate?
> * Does it integrate with any external APIs or databases (e.g., vulnerability scanners, Red Hat build systems)?
> * What exact CycloneDX fields/properties does it append to the document?

---

## Getting Started (Local Development)

This component is built on Quarkus and is designed to run seamlessly alongside the wider SBOMer ecosystem locally using Kubernetes (Minikube) and Helm.

### 1. Start the Infrastructure

If you haven't already spun up the core SBOMer infrastructure (Kafka, Apicurio Registry, Manifest Storage, and OpenTelemetry), run the setup script from the root of this repository to configure your Minikube environment:

```bash
./hack/setup-local-dev.sh
```

### 2. Build and Deploy the Enhancer

To compile the Java code, build the local container image, and deploy the {{ project_name }} into your cluster via its Helm chart, run:

```bash
./hack/run-helm-with-local-build.sh
```

Once deployed, this service will automatically:
- Connect to the local sbomer-release-kafka broker.
- Listen for enhancement.created events specifically targeting the {{ project_slug }} component.
- Stream its traces, metrics, and logs to the local OpenTelemetry LGTM stack.