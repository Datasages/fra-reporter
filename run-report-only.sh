#!/usr/bin/env bash
set -euo pipefail

# FRA Report Generator - Report Only (No Sync)
# Use this when data is already present in MongoDB

BASE_DIR="${BASE_DIR:-/opt/fra-report-generator}"
CONFIG="${BASE_DIR}/config.properties"

# Database configuration
DOCDB_URI="${DOCDB_URI:-}"
MONGO_URI="${MONGO_URI:-mongodb://localhost:27017}"
DB_NAME="${DB_NAME:-amtk_reports}"

# AWS configuration
AWS_REGION="${AWS_REGION:-us-east-1}"
S3_BUCKET="${S3_BUCKET:-rwn-amtk-report-prod}"

echo "=== FRA Report Generator - Report Only ==="
echo "Date: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"

# Validate required configuration
if [[ -z "$DOCDB_URI" ]]; then
    echo "ERROR: DOCDB_URI environment variable is required"
    echo "This is the DocumentDB connection string for storing report metadata"
    exit 1
fi

echo "Messages MongoDB: $MONGO_URI"
echo "Reports DocumentDB: ${DOCDB_URI%%@*}@***" # Hide credentials in log

# Generate config.properties from environment variables
echo "Generating config.properties..."
cat > "$CONFIG" << EOF
init.time = 30

# Set to 0 for auto-detection of previous month (can be overridden by INIT_YEAR/INIT_MONTH env vars)
init.year = 0
init.month = 0

org.slf4j.simpleLogger.defaultLogLevel = info

reports.scac = amtk
reports.scac.cibos = amtk.b:cibos
reports.scac.gbos = "amtk.b:gb.nec", "amtk.b:gb.me"
reports.railroadname = Amtrak

reports.mongo.batchsize = 100000
# Report metadata storage (DocumentDB)
reports.mongo.url = ${DOCDB_URI}
reports.mongo.database = ${DB_NAME}
reports.mongo.collection = amtk_reports

# Message queries (local MongoDB with synced data)
messages.mongo.url = ${MONGO_URI}
messages.mongo.database = ${DB_NAME}
messages.mongo.collection = amtk_messages

aws.api.region = ${AWS_REGION}
aws.api.S3bucket = ${S3_BUCKET}
aws.s3bucket.report = ${S3_BUCKET}
aws.s3bucket.base.url = https://s3.amazonaws.com/${S3_BUCKET}/
EOF

echo "Config generated"

# Run report generator
echo ""
echo "=== Running FRA Report Generator ==="
cd "$BASE_DIR"
java $JAVA_OPTS -jar fra-report-generator.jar

echo ""
echo "=== Report generation complete ==="
echo "Finished: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
