#!/bin/bash

# Build Script for DocumentDB to MongoDB Migration Image
# Builds the migration container for copying monthly data and cleanup

set -e

# Configuration
IMAGE_NAME="petekofod/mongo-migrator"
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
TEST=false

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
        --test)
            TEST=true
            shift
            ;;
        --version)
            VERSION="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo "Build DocumentDB to MongoDB migration image"
            echo ""
            echo "Options:"
            echo "  --push        Push image to Docker Hub after building"
            echo "  --clean       Clean up intermediate images after build"
            echo "  --no-cache    Build without using cache"
            echo "  --test        Run test after building"
            echo "  --version V   Set version tag (default: v1.0.0)"
            echo "  --help        Show this help message"
            echo ""
            echo "Examples:"
            echo "  $0                    # Basic build"
            echo "  $0 --test --push      # Build, test, and push"
            echo "  $0 --version v2.0.0   # Build with custom version"
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

    if ! docker info &> /dev/null; then
        error "Docker daemon is not running"
    fi

    # Check for migration script
    if [ ! -f "scripts/mongo_migration.py" ]; then
        error "Migration script not found: scripts/mongo_migration.py"
    fi

    log "Prerequisites check passed"
}

# Build the Docker image
build_image() {
    log "Building MongoDB migration image: ${IMAGE_NAME}:${VERSION}"

    local build_args=""
    if [ "$NO_CACHE" = true ]; then
        build_args="--no-cache"
    fi

    # Build with version tag using Dockerfile.mongo
    docker build $build_args \
        -f Dockerfile.mongo \
        -t "${IMAGE_NAME}:${VERSION}" \
        -t "${IMAGE_NAME}:${LATEST_TAG}" \
        .

    log "Docker image built successfully"
}

# Test the Docker image
test_image() {
    if [ "$TEST" = true ]; then
        log "Testing MongoDB migration image..."

        # Test image can start and show help
        debug "Testing image startup..."
        if docker run --rm "${IMAGE_NAME}:${VERSION}" --help &>/dev/null; then
            log "Image startup test passed"
        else
            warn "Image startup test failed (this may be expected if help is not implemented)"
        fi

        # Test health check
        debug "Testing health check..."
        local container_id=$(docker run -d "${IMAGE_NAME}:${VERSION}" sleep 30)
        sleep 5

        local health_status=$(docker inspect --format='{{.State.Health.Status}}' "$container_id" 2>/dev/null || echo "unknown")
        docker rm -f "$container_id" &>/dev/null

        if [ "$health_status" = "healthy" ]; then
            log "Health check test passed"
        else
            warn "Health check status: $health_status"
        fi

        # Test Python dependencies
        debug "Testing Python dependencies..."
        if docker run --rm "${IMAGE_NAME}:${VERSION}" python -c "import pymongo; print('Dependencies OK')" &>/dev/null; then
            log "Dependencies test passed"
        else
            error "Dependencies test failed"
        fi

        log "All tests completed"
    fi
}

# Push to Docker Hub
push_image() {
    if [ "$PUSH" = true ]; then
        log "Pushing image to Docker Hub..."

        # Check if logged in to Docker Hub
        if ! docker info | grep -q "Username:" 2>/dev/null; then
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
        docker image prune -f
        log "Cleanup completed"
    fi
}

# Display usage examples
show_usage() {
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
    log ""
    log "# Basic migration (previous month + cleanup):"
    log "docker run --rm \\"
    log "  -e DOCUMENTDB_URI='mongodb://user:pass@docdb-cluster:27017/?authSource=admin' \\"
    log "  -e MONGODB_URI='mongodb://user:pass@mongo-host:27017/' \\"
    log "  ${IMAGE_NAME}:${VERSION}"
    log ""
    log "# Custom configuration:"
    log "docker run --rm \\"
    log "  -e DOCUMENTDB_URI='mongodb://...' \\"
    log "  -e MONGODB_URI='mongodb://...' \\"
    log "  -e SOURCE_DATABASE='reports' \\"
    log "  -e TARGET_DATABASE='reports' \\"
    log "  -e COLLECTION_NAME='messages' \\"
    log "  -e BATCH_SIZE='2000' \\"
    log "  ${IMAGE_NAME}:${VERSION}"
    log ""
    log "# Dry run mode:"
    log "docker run --rm \\"
    log "  -e DOCUMENTDB_URI='mongodb://...' \\"
    log "  -e MONGODB_URI='mongodb://...' \\"
    log "  ${IMAGE_NAME}:${VERSION} --dry-run"
    log ""
    log "# Run with AWS Batch/Fargate:"
    log "# Set environment variables in job definition"
    log "# Image will automatically process previous month and cleanup"
}

# Main execution
main() {
    log "Starting MongoDB migration image build..."

    check_prerequisites
    build_image
    test_image
    push_image
    cleanup
    show_usage

    log "MongoDB migration image build completed successfully!"
}

# Run if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi