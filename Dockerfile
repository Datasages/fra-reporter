# Dockerfile for FRA Report Generator - Monthly Automated Runs
# Multi-stage build with MongoDB tools for sync

FROM eclipse-temurin:17-jdk AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y \
    maven \
    git \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy Maven files first for better layer caching
COPY pom.xml ./

# Download dependencies (this layer will be cached unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application (skip tests - they require external dependencies)
RUN mvn clean package -DskipTests -B

# Verify JAR was created
RUN ls -la target/ && \
    find target -name "*-jar-with-dependencies.jar" -exec echo "Built JAR: {}" \;

# Runtime stage
FROM eclipse-temurin:17-jre

# Install runtime dependencies including MongoDB tools for sync
# Download MongoDB tools directly since apt packages may not be available for all architectures
RUN apt-get update && apt-get install -y \
    curl \
    bash \
    tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && ARCH=$(dpkg --print-architecture) \
    && if [ "$ARCH" = "amd64" ]; then \
         curl -fsSL https://fastdl.mongodb.org/tools/db/mongodb-database-tools-ubuntu2204-x86_64-100.10.0.deb -o /tmp/mongodb-tools.deb; \
       elif [ "$ARCH" = "arm64" ]; then \
         curl -fsSL https://fastdl.mongodb.org/tools/db/mongodb-database-tools-ubuntu2204-arm64-100.10.0.deb -o /tmp/mongodb-tools.deb; \
       fi \
    && dpkg -i /tmp/mongodb-tools.deb \
    && rm /tmp/mongodb-tools.deb

# Create app user for security
RUN groupadd -g 1001 appgroup && \
    useradd -u 1001 -g appgroup -m appuser

# Set working directory
WORKDIR /opt/fra-report-generator

# Copy the built JAR from builder stage
COPY --from=builder /app/target/fra-report-generator-*-jar-with-dependencies.jar fra-report-generator.jar

# Copy Excel templates
COPY build/*.xlsx ./

# Copy configuration template
COPY build/config.properties.template config.properties

# Copy monthly startup script
COPY run-monthly.sh ./run-monthly.sh
RUN chmod +x ./run-monthly.sh

# Create a default log4j2.xml
RUN echo '<?xml version="1.0" encoding="UTF-8"?>\
<Configuration status="WARN">\
  <Appenders>\
    <Console name="Console" target="SYSTEM_OUT">\
      <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>\
    </Console>\
  </Appenders>\
  <Loggers>\
    <Root level="INFO">\
      <AppenderRef ref="Console"/>\
    </Root>\
  </Loggers>\
</Configuration>' > log4j2.xml

# Create temp directory for mongodump
RUN mkdir -p /tmp && chown -R appuser:appgroup /tmp

# Change ownership to app user
RUN chown -R appuser:appgroup /opt/fra-report-generator

# Switch to app user
USER appuser

# Set default JVM options optimized for containers
ENV JAVA_OPTS="-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseContainerSupport -Dlog4j.configurationFile=/opt/fra-report-generator/log4j2.xml"

# Set timezone
ENV TZ=UTC

# Environment variables for configuration (can be overridden at runtime)
ENV BASE_DIR=/opt/fra-report-generator
ENV DOCDB_URI=""
ENV MONGO_URI="mongodb://localhost:27017"
ENV DB_NAME="amtk_reports"
ENV MESSAGES_COLLECTION="amtk_messages"
ENV AWS_ENDPOINT_URL=""

# Entry point using monthly startup script
ENTRYPOINT ["/opt/fra-report-generator/run-monthly.sh"]

# Metadata
LABEL maintainer="Railway Network Team" \
      description="FRA Report Generator - Monthly Automated Runs" \
      version="monthly" \
      java.version="17"
