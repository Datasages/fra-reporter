#!/bin/bash

# FRA Report Generator Build and Test Script
# Builds the application and runs tests before deployment

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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

# Check prerequisites
check_prerequisites() {
    log "Checking prerequisites..."

    if ! command -v java &> /dev/null; then
        error "Java is not installed or not in PATH"
    fi

    if ! command -v mvn &> /dev/null; then
        error "Maven is not installed or not in PATH"
    fi

    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | head -n1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt 17 ]; then
        error "Java 17+ is required, found Java $JAVA_VERSION"
    fi

    log "Prerequisites check passed (Java $JAVA_VERSION)"
}

# Clean previous builds
clean_build() {
    log "Cleaning previous builds..."
    mvn clean -q
    log "Clean complete"
}

# Compile the application
compile_application() {
    log "Compiling application..."
    mvn compile -q
    log "Compilation successful"
}

# Run unit tests
run_unit_tests() {
    log "Running unit tests..."
    mvn test -Dtest="*Test" -q
    local exit_code=$?

    if [ $exit_code -eq 0 ]; then
        log "Unit tests passed"
    else
        error "Unit tests failed with exit code $exit_code"
    fi
}

# Run integration tests
run_integration_tests() {
    log "Running integration tests..."

    # Check if Docker is available for Testcontainers
    if ! command -v docker &> /dev/null; then
        warn "Docker not available, skipping integration tests"
        return 0
    fi

    mvn test -Dtest="*IntegrationTest" -q
    local exit_code=$?

    if [ $exit_code -eq 0 ]; then
        log "Integration tests passed"
    else
        error "Integration tests failed with exit code $exit_code"
    fi
}

# Package the application
package_application() {
    log "Packaging application..."
    mvn package -DskipTests -q

    # Verify JAR was created
    JAR_FILE=$(find target -name "*-jar-with-dependencies.jar" | head -1)
    if [ -z "$JAR_FILE" ]; then
        error "JAR file not found after packaging"
    fi

    log "Packaging complete: $JAR_FILE"
}

# Verify templates exist
verify_templates() {
    log "Verifying Excel templates..."

    local templates=(
        "build/Enforcement_Report_template.xlsx"
        "build/Failed_Init_Report_template.xlsx"
        "build/Position_Report_template.xlsx"
    )

    for template in "${templates[@]}"; do
        if [ ! -f "$template" ]; then
            error "Required template not found: $template"
        fi
    done

    log "All templates verified"
}

# Verify configuration
verify_configuration() {
    log "Verifying configuration files..."

    if [ ! -f "build/config.properties.sample" ]; then
        error "Configuration sample not found: build/config.properties.sample"
    fi

    if [ ! -f "build/config.properties" ]; then
        warn "Production config not found, copying from sample"
        cp "build/config.properties.sample" "build/config.properties"
        warn "Please update build/config.properties with your settings"
    fi

    log "Configuration verified"
}

# Run security checks
run_security_checks() {
    log "Running security checks..."

    # Check for OWASP dependency check plugin
    if mvn help:describe -Dplugin=org.owasp:dependency-check-maven &> /dev/null; then
        mvn org.owasp:dependency-check-maven:check -q
        log "OWASP dependency check completed"
    else
        warn "OWASP dependency check not configured, skipping security scan"
    fi
}

# Generate build report
generate_build_report() {
    log "Generating build report..."

    local report_file="build-report-$(date +%Y%m%d-%H%M%S).txt"

    cat > "$report_file" << EOF
FRA Report Generator Build Report
Generated: $(date)
================================

Build Environment:
- Java Version: $(java -version 2>&1 | head -n1)
- Maven Version: $(mvn -version | head -n1)
- OS: $(uname -s) $(uname -r)

Build Artifacts:
- JAR File: $(find target -name "*-jar-with-dependencies.jar" | head -1)
- Size: $(du -h $(find target -name "*-jar-with-dependencies.jar" | head -1) | cut -f1)

Dependencies:
$(mvn dependency:tree | grep -E "^\[INFO\] [+\\\\]" | head -20)

Test Results:
- Unit Tests: $(mvn surefire-report:report -q &> /dev/null && echo "Available in target/site/surefire-report.html" || echo "No report generated")

Build Status: SUCCESS
EOF

    log "Build report generated: $report_file"
}

# Main execution
main() {
    log "Starting FRA Report Generator build and test..."

    check_prerequisites
    clean_build
    compile_application
    verify_templates
    verify_configuration
    run_unit_tests
    run_integration_tests
    run_security_checks
    package_application
    generate_build_report

    log ""
    log "Build and test completed successfully!"
    log ""
    log "Next steps:"
    log "1. Review build-report-*.txt for details"
    log "2. Run './scripts/deploy-aws-batch.sh' to deploy to AWS"
    log "3. Test locally with 'docker-compose up'"
}

# Run if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi