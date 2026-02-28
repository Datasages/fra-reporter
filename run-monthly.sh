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

# SSM Parameter Store path for DOCDB_URI (used if DOCDB_URI env var is not set)
SSM_DOCDB_PARAM="${SSM_DOCDB_PARAM:-/fra-reporter/docdb-uri}"

# If DOCDB_URI is not set, try to fetch from AWS Parameter Store
if [[ -z "$DOCDB_URI" ]]; then
    echo "DOCDB_URI not set, fetching from Parameter Store: $SSM_DOCDB_PARAM"
    DOCDB_URI=$(aws ssm get-parameter --name "$SSM_DOCDB_PARAM" --with-decryption --query Parameter.Value --output text 2>/dev/null || echo "")
    if [[ -n "$DOCDB_URI" ]]; then
        echo "Successfully retrieved DOCDB_URI from Parameter Store"
    else
        echo "WARNING: Could not retrieve DOCDB_URI from Parameter Store"
    fi
fi

# AWS configuration for LocalStack testing
# Unset AWS_ENDPOINT_URL if empty - the AWS SDK fails on empty string
AWS_ENDPOINT_URL="${AWS_ENDPOINT_URL:-}"
if [[ -z "$AWS_ENDPOINT_URL" ]]; then
    unset AWS_ENDPOINT_URL
fi

# Optional: Override month/year (passed to both sync and Java app)
INIT_YEAR="${INIT_YEAR:-}"
INIT_MONTH="${INIT_MONTH:-}"

# Wait time after restore for indexes to update (seconds)
INDEX_WAIT_SECONDS="${INDEX_WAIT_SECONDS:-30}"

# Retention period for old messages (days) - set to 0 to disable cleanup
RETENTION_DAYS="${RETENTION_DAYS:-90}"

echo "=== FRA Report Generator - Monthly Run ==="
echo "Date: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"

# Calculate time range for sync
# If INIT_YEAR and INIT_MONTH are set, use them; otherwise auto-detect previous month
if [[ -n "$INIT_YEAR" && -n "$INIT_MONTH" ]]; then
    echo "Using specified month: $INIT_YEAR-$INIT_MONTH"
    # Calculate start of specified month
    MONTH_START="${INIT_YEAR}-$(printf '%02d' $INIT_MONTH)-01"
    # Calculate start of next month
    if [[ "$INIT_MONTH" -eq 12 ]]; then
        NEXT_YEAR=$((INIT_YEAR + 1))
        NEXT_MONTH="01"
    else
        NEXT_YEAR="$INIT_YEAR"
        NEXT_MONTH=$(printf '%02d' $((INIT_MONTH + 1)))
    fi
    MONTH_END="${NEXT_YEAR}-${NEXT_MONTH}-01"

    # Convert to epoch (GNU date - runs in Linux container)
    SYNC_START=$(date -d "$MONTH_START" +%s)
    SYNC_END=$(date -d "$MONTH_END" +%s)
    SYNC_START_HR="$MONTH_START"
    SYNC_END_HR="$MONTH_END"
else
    echo "Auto-detecting previous month"
    # Calculate previous month's time range (epoch seconds)
    if [[ "$(uname)" == "Darwin" ]]; then
        # macOS date syntax
        SYNC_START=$(date -v-1m -v1d -v0H -v0M -v0S +%s 2>/dev/null || echo "0")
        SYNC_END=$(date -v1d -v0H -v0M -v0S +%s 2>/dev/null || echo "0")
        SYNC_START_HR=$(date -r "$SYNC_START" '+%Y-%m-%d' 2>/dev/null || echo "unknown")
        SYNC_END_HR=$(date -r "$SYNC_END" '+%Y-%m-%d' 2>/dev/null || echo "unknown")
    else
        # GNU date syntax (Linux)
        SYNC_START=$(date -d "$(date +%Y-%m-01) -1 month" +%s)
        SYNC_END=$(date -d "$(date +%Y-%m-01)" +%s)
        SYNC_START_HR=$(date -d "@$SYNC_START" '+%Y-%m-%d')
        SYNC_END_HR=$(date -d "@$SYNC_END" '+%Y-%m-%d')
    fi
fi

echo "Sync range: $SYNC_START_HR to $SYNC_END_HR"
echo "Epoch range: $SYNC_START to $SYNC_END"

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
        --query="{\"time\": {\"\$gte\": $SYNC_START, \"\$lt\": $SYNC_END}}" \
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
        --nsInclude="${DB_NAME}.${MESSAGES_COLLECTION}" \
        "$DUMP_DIR"

    # Cleanup dump files
    rm -rf "$DUMP_DIR"
    echo "Sync complete"

    # Wait for indexes to update after bulk insert
    echo ""
    echo "Waiting ${INDEX_WAIT_SECONDS} seconds for indexes to update..."
    sleep "$INDEX_WAIT_SECONDS"
else
    echo ""
    echo "=== Skipping sync (DOCDB_URI not set) ==="
    echo "Using existing data in MongoDB"
fi

# Step 3: Generate config.properties and run report generator
echo ""
echo "=== Step 3: Running FRA Report Generator ==="

# AWS S3 configuration
AWS_REGION="${AWS_REGION:-us-east-1}"
S3_BUCKET="${S3_BUCKET:-rwn-amtk-report-prod}"

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
messages.mongo.collection = ${MESSAGES_COLLECTION}

aws.api.region = ${AWS_REGION}
aws.api.S3bucket = ${S3_BUCKET}
aws.s3bucket.report = ${S3_BUCKET}
aws.s3bucket.base.url = https://s3.amazonaws.com/${S3_BUCKET}/
EOF

echo "Config generated"

# Update config if AWS_ENDPOINT_URL is set (for LocalStack testing)
if [[ -n "${AWS_ENDPOINT_URL:-}" ]]; then
    echo "Using AWS endpoint: $AWS_ENDPOINT_URL"
    export AWS_ENDPOINT_URL
fi

cd "$BASE_DIR"
java $JAVA_OPTS -jar "$JAR"

# Step 4: Cleanup old messages (if retention is enabled)
if [[ "$RETENTION_DAYS" -gt 0 ]]; then
    echo ""
    echo "=== Step 4: Cleaning up messages older than ${RETENTION_DAYS} days ==="

    # Calculate cutoff timestamp (epoch seconds)
    CUTOFF_EPOCH=$(date -d "${RETENTION_DAYS} days ago" +%s)
    echo "Deleting messages with time < $CUTOFF_EPOCH"

    # Delete old messages using mongosh
    DELETE_RESULT=$(mongosh "$MONGO_URI/$DB_NAME" --quiet --eval "db.${MESSAGES_COLLECTION}.deleteMany({time: {\$lt: $CUTOFF_EPOCH}}).deletedCount" 2>/dev/null || echo "0")
    echo "Deleted $DELETE_RESULT old messages"
fi

echo ""
echo "=== Report generation complete ==="
echo "Finished: $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
