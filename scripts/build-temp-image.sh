#!/bin/bash

# Build Script for Temporary FRA Report Generator
# Self-contained image for manual report generation

set -e

# Configuration
IMAGE_NAME="petekofod/fra-report-temp"
VERSION="v1.0.0"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
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

# Check prerequisites
check_prerequisites() {
    log "Checking prerequisites..."

    if ! command -v docker &> /dev/null; then
        error "Docker is not installed"
    fi

    if ! docker info &> /dev/null; then
        error "Docker daemon is not running"
    fi

    # Check if JAR exists
    if ! ls target/fra-report-generator-*-jar-with-dependencies.jar &> /dev/null; then
        warn "JAR file not found. Building application first..."
        mvn clean package -DskipTests
    fi

    # Check for Excel templates
    if [ ! -d "build" ] || ! ls build/*.xlsx &> /dev/null; then
        warn "Excel templates not found in build/ directory"
        warn "Reports may fail without proper templates"
    fi

    log "Prerequisites check passed"
}

# Build the Docker image
build_image() {
    log "Building temporary FRA report generator image..."

    docker build -f Dockerfile.temp \
        -t "${IMAGE_NAME}:${VERSION}" \
        -t "${IMAGE_NAME}:latest" \
        .

    log "Docker image built successfully"
}

# Show usage instructions
show_usage() {
    log ""
    log "Temporary FRA Report Generator Built Successfully!"
    log "================================================"
    log ""
    log "Prerequisites:"
    log "1. MongoDB running locally on port 27017"
    log "2. Database: amtk_reports"
    log "3. Collection: amtk_messages"
    log "4. AWS credentials configured or available"
    log ""
    log "Usage Examples:"
    log ""
    log "# Interactive mode (recommended for setup):"
    log "docker run -it --rm ${IMAGE_NAME}:${VERSION}"
    log ""
    log "# Direct report generation:"
    log "docker run -it --rm \\"
    log "  -e AWS_ACCESS_KEY_ID=your_key \\"
    log "  -e AWS_SECRET_ACCESS_KEY=your_secret \\"
    log "  -e AWS_DEFAULT_REGION=us-east-1 \\"
    log "  ${IMAGE_NAME}:${VERSION} /app/run-reports.sh"
    log ""
    log "# With AWS credentials file mounted:"
    log "docker run -it --rm \\"
    log "  -v ~/.aws:/home/appuser/.aws:ro \\"
    log "  ${IMAGE_NAME}:${VERSION} /app/run-reports.sh"
    log ""
    log "Inside the container:"
    log "- ./run-reports.sh          - Generate all reports"
    log "- ./setup-aws.sh            - Configure AWS credentials"
    log "- nano config.properties    - Edit MongoDB/S3 settings"
    log "- aws s3 ls s3://rwn.amtk.reports/  - Check uploaded reports"
    log ""
    log "The container will:"
    log "1. Connect to local MongoDB via host.docker.internal:27017"
    log "2. Generate reports for the previous period"
    log "3. Upload reports to S3 bucket: rwn.amtk.reports"
    log "4. Show success/failure status"
}

# Main execution
main() {
    log "Building temporary FRA report generator..."

    check_prerequisites
    build_image
    show_usage

    log "Build completed successfully!"
}

# Run if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi