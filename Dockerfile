# Multi-stage build for FRA Report Generator
FROM eclipse-temurin:17-jdk-alpine AS builder

# Install build dependencies
RUN apk add --no-cache \
    maven \
    git \
    && rm -rf /var/cache/apk/*

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
FROM eclipse-temurin:17-jre-alpine

# Install runtime dependencies
RUN apk add --no-cache \
    curl \
    bash \
    tzdata \
    && rm -rf /var/cache/apk/*

# Create app user for security
RUN addgroup -g 1001 appgroup && \
    adduser -D -u 1001 -G appgroup appuser

# Set working directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/target/fra-report-generator-*-jar-with-dependencies.jar app.jar

# Copy Excel templates from host build directory
COPY build/ ./build/

# Create a default log4j2.xml if it doesn't exist
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

# Copy default configuration as template
COPY --from=builder /app/src/main/resources/config.properties ./config.properties.template

# Create startup script that handles configuration
RUN echo '#!/bin/bash\n\
set -e\n\
\n\
# Use environment variables to create config if not provided\n\
if [ ! -f "/app/config.properties" ]; then\n\
  echo "Creating config.properties from environment variables..."\n\
  cat > /app/config.properties << EOF\n\
reports.mongo.url=${REPORTS_MONGO_URL:-mongodb://localhost:27017/}\n\
reports.mongo.database=${REPORTS_MONGO_DATABASE:-reportMetaData}\n\
reports.mongo.collection=${REPORTS_MONGO_COLLECTION:-reports}\n\
\n\
messages.mongo.url=${MESSAGES_MONGO_URL:-mongodb://localhost:27017/}\n\
messages.mongo.database=${MESSAGES_MONGO_DATABASE:-reports}\n\
messages.mongo.collection=${MESSAGES_MONGO_COLLECTION:-messages}\n\
\n\
aws.region=${AWS_REGION:-us-east-1}\n\
aws.api.S3bucket=${AWS_S3_BUCKET:-rwn.amtk.reports}\n\
EOF\n\
fi\n\
\n\
echo "Starting FRA Report Generator..."\n\
echo "Java version: $(java -version 2>&1 | head -n1)"\n\
echo "Available memory: $(free -h | grep Mem | awk '\''{print $2}'\'')" || echo "Memory info not available"\n\
echo "Java options: $JAVA_OPTS"\n\
\n\
# Start the application\n\
exec java $JAVA_OPTS -jar app.jar "$@"' > /app/start.sh

# Make startup script executable
RUN chmod +x /app/start.sh

# Change ownership to app user
RUN chown -R appuser:appgroup /app

# Switch to app user
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD pgrep -f "java.*app.jar" > /dev/null || exit 1

# Set default JVM options optimized for containers
ENV JAVA_OPTS="-Xmx3g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseContainerSupport -Dlog4j.configurationFile=/app/log4j2.xml"

# Set timezone
ENV TZ=UTC

# Entry point using startup script
ENTRYPOINT ["/app/start.sh"]

# Metadata
LABEL maintainer="Railway Network Team" \
      description="FRA Report Generator - Railway Regulatory Reports Generator" \
      version="1.0.0" \
      java.version="17" \
      build.date="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"