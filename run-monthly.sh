#!/usr/bin/env bash
set -euo pipefail

# FRA Report Generator - Monthly Automated Run
# This script syncs messages from DocumentDB to MongoDB, then generates reports

BASE_DIR="${BASE_DIR:-/opt/fra-report-generator}"
CONFIG="${BASE_DIR}/config.properties"
JAR="${BASE_DIR}/fra-report-generator.jar"

# Database configuration (can be overridden by environment variables)
DOCDB_URI="${DOCDB_URI:-}"
MONGO_URI="${MONGO_URI:-mongodb://localhost:27017}"
DB_NAME="${DB_NAME:-amtk_reports}"
MESSAGES_COLLECTION="${MESSAGES_COLLECTION:-amtk_messages}"

# AWS configuration for LocalStack testing
AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-}"

echo "=== FRA Report Generator - Monthly Run ==="
echo "Date: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"

# Calculate previous month's time range (epoch seconds)
# When run on 2nd of month, sync previous month's data
if [[ "$(uname)" == "Darwin" ]]; then
    # macOS date syntax
    PREV_MONTH_START=$(date -v-1m -v1d -v0H -v0M -v0S +%s 2>/dev/null || echo "0")
    PREV_MONTH_END=$(date -v1d -v0H -v0M -v0S +%s 2>/dev/null || echo "0")
else
    # GNU date syntax (Linux)
    PREV_MONTH_START=$(date -d "$(date +%Y-%m-01) -1 month" +%s)
    PREV_MONTH_END=$(date -d "$(date +%Y-%m-01)" +%s)
fi

# Convert to human-readable for logging
if [[ "$(uname)" == "Darwin" ]]; then
    PREV_MONTH_START_HR=$(date -r "$PREV_MONTH_START" '+%Y-%m-%d' 2>/dev/null || echo "unknown")
    PREV_MONTH_END_HR=$(date -r "$PREV_MONTH_END" '+%Y-%m-%d' 2>/dev/null || echo "unknown")
else
    PREV_MONTH_START_HR=$(date -d "@$PREV_MONTH_START" '+%Y-%m-%d')
    PREV_MONTH_END_HR=$(date -d "@$PREV_MONTH_END" '+%Y-%m-%d')
fi

echo "Previous month range: $PREV_MONTH_START_HR to $PREV_MONTH_END_HR"
echo "Epoch range: $PREV_MONTH_START to $PREV_MONTH_END"

# Step 1: Sync messages from DocumentDB to MongoDB (if DOCDB_URI is set)
if [[ -n "$DOCDB_URI" ]]; then
    echo ""
    echo "=== Step 1: Syncing messages from DocumentDB ==="

    DUMP_DIR="/tmp/mongodump-$$"
    mkdir -p "$DUMP_DIR"

    echo "Running mongodump..."
    mongodump \
        --uri="$DOCDB_URI" \
        --db="$DB_NAME" \
        --collection="$MESSAGES_COLLECTION" \
        --query="{\"time\": {\"\$gte\": $PREV_MONTH_START, \"\$lt\": $PREV_MONTH_END}}" \
        --out="$DUMP_DIR"

    # Count dumped documents
    if [[ -f "$DUMP_DIR/$DB_NAME/$MESSAGES_COLLECTION.bson" ]]; then
        DUMP_COUNT=$(bsondump "$DUMP_DIR/$DB_NAME/$MESSAGES_COLLECTION.bson" 2>/dev/null | wc -l || echo "unknown")
        echo "Dumped $DUMP_COUNT messages"
    fi

    echo ""
    echo "=== Step 2: Restoring to MongoDB ==="
    echo "Target: $MONGO_URI"

    mongorestore \
        --uri="$MONGO_URI" \
        --db="$DB_NAME" \
        --nsInclude="${DB_NAME}.${MESSAGES_COLLECTION}" \
        "$DUMP_DIR"

    # Cleanup dump files
    rm -rf "$DUMP_DIR"
    echo "Sync complete"
else
    echo ""
    echo "=== Skipping sync (DOCDB_URI not set) ==="
    echo "Using existing data in MongoDB"
fi

# Step 3: Run report generator
echo ""
echo "=== Step 3: Running FRA Report Generator ==="

# Update config if AWS_ENDPOINT_URL is set (for LocalStack testing)
if [[ -n "$AWS_ENDPOINT_URL" ]]; then
    echo "Using AWS endpoint: $AWS_ENDPOINT_URL"
    # The Java app needs to be configured to use this endpoint
    export AWS_ENDPOINT_URL
fi

cd "$BASE_DIR"
java -jar "$JAR"

echo ""
echo "=== Report generation complete ==="
echo "Finished: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
