#!/bin/bash

# Docker Build Script for FRA Report Generator
# Builds and optionally pushes the Docker image

set -e

# Configuration
IMAGE_NAME="petekofod/fra-report-generator"
VERSION="v1.0.0"
LATEST_TAG="latest"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

error() {
    echo -e "${RED}[ERROR]${NC} $1"
    exit 1
}

debug() {
    echo -e "${BLUE}[DEBUG]${NC} $1"
}

# Parse command line arguments
PUSH=false
CLEAN=false
NO_CACHE=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --push)
            PUSH=true
            shift
            ;;
        --clean)
            CLEAN=true
            shift
            ;;
        --no-cache)
            NO_CACHE=true
            shift
            ;;
        --version)
            VERSION="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo "Options:"
            echo "  --push        Push image to Docker Hub after building"
            echo "  --clean       Clean up intermediate images after build"
            echo "  --no-cache    Build without using cache"
            echo "  --version V   Set version tag (default: v1.0.0)"
            echo "  --help        Show this help message"
            exit 0
            ;;
        *)
            error "Unknown option: $1"
            ;;
    esac
done

# Check prerequisites
check_prerequisites() {
    log "Checking prerequisites..."

    if ! command -v docker &> /dev/null; then
        error "Docker is not installed or not in PATH"
    fi

    # Check Docker daemon is running
    if ! docker info &> /dev/null; then
        error "Docker daemon is not running"
    fi

    # Check if build directory exists
    if [ ! -d "build" ]; then
        warn "Build directory not found, creating it..."
        mkdir -p build
    fi

    # Check for Excel templates
    if ls build/*.xlsx &> /dev/null; then
        log "Found Excel templates in build/ directory"
    else
        warn "No Excel templates found in build/ directory"
        warn "Application will need templates provided at runtime"
    fi

    log "Prerequisites check passed"
}

# Build the Docker image
build_image() {
    log "Building Docker image: ${IMAGE_NAME}:${VERSION}"

    local build_args=""
    if [ "$NO_CACHE" = true ]; then
        build_args="--no-cache"
    fi

    # Build with version tag
    docker build $build_args \
        -t "${IMAGE_NAME}:${VERSION}" \
        -t "${IMAGE_NAME}:${LATEST_TAG}" \
        .

    log "Docker image built successfully"
}

# Test the Docker image
test_image() {
    log "Testing Docker image..."

    # Test image can start
    debug "Testing image startup..."
    local container_id=$(docker run -d \
        -e REPORTS_MONGO_URL="mongodb://test:test@localhost:27017/" \
        -e MESSAGES_MONGO_URL="mongodb://test:test@localhost:27017/" \
        -e AWS_REGION="us-east-1" \
        -e AWS_S3_BUCKET="test-bucket" \
        "${IMAGE_NAME}:${VERSION}" \
        echo "Container test" 2>/dev/null || echo "")

    if [ -n "$container_id" ]; then
        sleep 2
        local exit_code=$(docker inspect --format='{{.State.ExitCode}}' "$container_id" 2>/dev/null || echo "1")
        docker rm -f "$container_id" &>/dev/null || true

        if [ "$exit_code" = "0" ]; then
            log "Image test passed"
        else
            warn "Image test completed with exit code: $exit_code"
        fi
    else
        warn "Unable to test image startup"
    fi

    # Test image metadata
    debug "Checking image metadata..."
    docker inspect "${IMAGE_NAME}:${VERSION}" | grep -E '"(User|WorkingDir|Entrypoint)"' || true
}

# Push to Docker Hub
push_image() {
    if [ "$PUSH" = true ]; then
        log "Pushing image to Docker Hub..."

        # Check if logged in to Docker Hub
        if ! docker info | grep -q "Username:"; then
            warn "Not logged in to Docker Hub. Please run 'docker login' first"
            return 1
        fi

        docker push "${IMAGE_NAME}:${VERSION}"
        docker push "${IMAGE_NAME}:${LATEST_TAG}"

        log "Image pushed successfully to Docker Hub"
        log "Image available at: https://hub.docker.com/r/${IMAGE_NAME}"
    fi
}

# Clean up intermediate images
cleanup() {
    if [ "$CLEAN" = true ]; then
        log "Cleaning up intermediate images..."

        # Remove dangling images
        docker image prune -f

        log "Cleanup completed"
    fi
}

# Display build summary
show_summary() {
    log ""
    log "Build Summary:"
    log "=============="
    log "Image: ${IMAGE_NAME}:${VERSION}"
    log "Size: $(docker images --format "table {{.Size}}" "${IMAGE_NAME}:${VERSION}" | tail -1)"

    if [ "$PUSH" = true ]; then
        log "Status: Built and pushed to Docker Hub"
    else
        log "Status: Built locally"
    fi

    log ""
    log "Usage Examples:"
    log "==============="
    log "# Run locally with environment variables:"
    log "docker run -e REPORTS_MONGO_URL='mongodb://...' \\"
    log "           -e MESSAGES_MONGO_URL='mongodb://...' \\"
    log "           -e AWS_REGION=us-east-1 \\"
    log "           -e AWS_S3_BUCKET=rwn.amtk.reports \\"
    log "           ${IMAGE_NAME}:${VERSION}"
    log ""
    log "# Run with config file mounted:"
    log "docker run -v \$(pwd)/config.properties:/app/config.properties \\"
    log "           ${IMAGE_NAME}:${VERSION}"
    log ""
    log "# Tag for a specific version:"
    log "docker tag ${IMAGE_NAME}:${VERSION} ${IMAGE_NAME}:production"
}

# Main execution
main() {
    log "Starting Docker build for FRA Report Generator..."

    check_prerequisites
    build_image
    test_image
    push_image
    cleanup
    show_summary

    log "Docker build completed successfully!"
}

# Run if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi