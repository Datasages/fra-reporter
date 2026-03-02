# FRA Report Generator - Manual Run Guide

## What This Tool Does

The FRA Report Generator creates regulatory compliance reports for the Federal Railroad Administration (FRA). It processes railway message data and generates three types of Excel reports:

| Report Type | Purpose | Generated When |
|-------------|---------|----------------|
| **Enforcement Report** | Tracks enforcement actions on trains | Monthly + Quarterly |
| **Failed Init Report** | Tracks locomotive initialization failures | Monthly + Quarterly |
| **Position Report** | Monitors locomotive positions and states | Monthly + Quarterly |

Reports are uploaded to S3 and metadata is stored in DocumentDB for the web UI.

---

## Quick Start

If you have Docker installed, MongoDB running locally with message data, and AWS credentials configured, run:

```bash
docker run \
  --network host \
  --name fra-report-run \
  -e MONGO_URI="mongodb://localhost:27017" \
  -e DB_NAME="amtk_reports" \
  -e AWS_REGION="us-east-1" \
  -e INIT_YEAR="2025" \
  -e INIT_MONTH="7" \
  petekofod/fra-report-generator:v1.3.7
```

**What each parameter means:**

| Parameter | Description |
|-----------|-------------|
| `--network host` | Container shares host networking (required to reach localhost MongoDB) |
| `--name fra-report-run` | Container name (use unique names for concurrent runs) |
| `MONGO_URI` | Local MongoDB containing the message data to process |
| `DB_NAME` | Database name (always `amtk_reports`) |
| `AWS_REGION` | AWS region for S3 uploads |
| `INIT_YEAR` | Year to generate reports for |
| `INIT_MONTH` | Month to generate reports for (1-12) |

**Quarterly reports** are automatically generated when `INIT_MONTH` is a quarter-end month (3, 6, 9, or 12).

> **Important:** Quarterly reports query all three months of the quarter. For accurate quarterly reports, you should run the monthly reports for each month throughout the quarter. Each monthly run syncs that month's messages to local MongoDB, so by quarter-end all three months of data are available.

---

## Architecture Overview

```
┌─────────────────┐     mongodump      ┌─────────────────┐
│   DocumentDB    │ ─────────────────► │  Local MongoDB  │
│ (Source Data)   │                    │ (Working Copy)  │
└─────────────────┘                    └────────┬────────┘
                                                │
                                                ▼
                                       ┌─────────────────┐
                                       │ Report Generator│
                                       │    (Docker)     │
                                       └────────┬────────┘
                                                │
                    ┌───────────────────────────┼───────────────────────────┐
                    ▼                           ▼                           ▼
           ┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
           │   S3 Bucket     │         │   DocumentDB    │         │   Console Log   │
           │ (Excel Reports) │         │ (Report Metadata)│         │   (Progress)    │
           └─────────────────┘         └─────────────────┘         └─────────────────┘
```

**Data Flow:**
1. Messages are copied from DocumentDB to local MongoDB (via mongodump/mongorestore)
2. The Docker container reads messages from local MongoDB
3. Reports are uploaded to S3
4. Report metadata is written back to DocumentDB

---

## Prerequisites

### Required Software
- **Docker** - Container runtime
- **MongoDB** - Local instance for processing (messages are synced here)
- **AWS CLI** - For verifying S3 uploads (optional)

### Required Access
- **EC2 Instance Profile** - IAM role with permissions for:
  - S3 read/write to `rwn-amtk-report-prod` bucket
  - SSM Parameter Store read for `/fra-reporter/docdb-uri`
- **DocumentDB** - Connection string stored in Parameter Store

### Message Data
The report generator needs message data in local MongoDB. The Docker container can sync this automatically if you provide the DocumentDB connection via Parameter Store, or you can manually sync using the steps in the "Manual Data Sync" section.

---

## Running Reports

### Option 1: Automatic Sync (Recommended)

The container automatically syncs message data from DocumentDB if it can access Parameter Store:

```bash
docker run \
  --network host \
  --name fra-jan-2026 \
  -e MONGO_URI="mongodb://localhost:27017" \
  -e DB_NAME="amtk_reports" \
  -e AWS_REGION="us-east-1" \
  -e INIT_YEAR="2026" \
  -e INIT_MONTH="1" \
  petekofod/fra-report-generator:v1.3.7
```

The container will:
1. Fetch `DOCDB_URI` from Parameter Store (`/fra-reporter/docdb-uri`)
2. Sync messages for the specified month from DocumentDB to local MongoDB
3. Generate reports
4. Upload to S3
5. Record metadata in DocumentDB

### Option 2: Manual Data Sync

If you need to manually sync data (e.g., no Parameter Store access):

```bash
# 1. Export from DocumentDB
mongodump \
  --uri="mongodb://user:pass@docdb-host:27017/?tls=true&replicaSet=rs0" \
  --db=amtk_reports \
  --collection=amtk_messages \
  --query='{"time": {"$gte": 1735689600, "$lt": 1738368000}}' \
  --out=/tmp/dump

# 2. Import to local MongoDB
mongorestore \
  --uri="mongodb://localhost:27017" \
  --nsInclude="amtk_reports.amtk_messages" \
  /tmp/dump

# 3. Run without sync (provide DOCDB_URI directly for metadata storage)
docker run \
  --network host \
  --name fra-jan-2026 \
  -e MONGO_URI="mongodb://localhost:27017" \
  -e DOCDB_URI="mongodb://user:pass@docdb-host:27017/?tls=true&replicaSet=rs0" \
  -e DB_NAME="amtk_reports" \
  -e AWS_REGION="us-east-1" \
  -e INIT_YEAR="2026" \
  -e INIT_MONTH="1" \
  petekofod/fra-report-generator:v1.3.7
```

### Running Multiple Months

To generate reports for multiple months, run separate containers with unique names:

```bash
# July 2025
docker run --network host --name fra-jul-2025 \
  -e MONGO_URI="mongodb://localhost:27017" \
  -e DB_NAME="amtk_reports" \
  -e AWS_REGION="us-east-1" \
  -e INIT_YEAR="2025" -e INIT_MONTH="7" \
  petekofod/fra-report-generator:v1.3.7

# August 2025
docker run --network host --name fra-aug-2025 \
  -e MONGO_URI="mongodb://localhost:27017" \
  -e DB_NAME="amtk_reports" \
  -e AWS_REGION="us-east-1" \
  -e INIT_YEAR="2025" -e INIT_MONTH="8" \
  petekofod/fra-report-generator:v1.3.7
```

### Container Management

```bash
# List running containers
docker ps

# View logs from a running container
docker logs -f fra-jul-2025

# Remove a stopped container (required before re-running with same name)
docker rm fra-jul-2025

# Force remove a running container
docker rm -f fra-jul-2025
```

---

## Automated Monthly Runs (Cron)

The report generator runs automatically via cron on the 2nd of each month at 3:00 AM.

### Cron Schedule

```
0 3 2 * *
```

| Field | Value | Meaning |
|-------|-------|---------|
| Minute | 0 | At minute 0 |
| Hour | 3 | At 3:00 AM |
| Day | 2 | On the 2nd of the month |
| Month | * | Every month |
| Weekday | * | Any day of week |

Running on the 2nd ensures the previous month has fully completed. The container auto-detects the previous month, so running on Feb 2nd processes January data.

### Cron Job Script

Location: `/usr/bin/fra-reporter`

```bash
#!/usr/bin/env bash
set -euo pipefail

# Generate unique container name: AUG-2025-20250801143045
TIMESTAMP=$(date +%Y%m%d%H%M%S)
MONTH=$(date +%b | tr '[:lower:]' '[:upper:]')
YEAR=$(date +%Y)
CONTAINER_NAME="${MONTH}-${YEAR}-${TIMESTAMP}"

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting FRA report generation (container: $CONTAINER_NAME)" >> /var/log/fra-report.log

docker run --rm \
  --network host \
  --name "$CONTAINER_NAME" \
  -e MONGO_URI="mongodb://localhost:27017" \
  -e DB_NAME="amtk_reports" \
  -e AWS_REGION="us-east-1" \
  petekofod/fra-report-generator:v1.3.7 \
  >> /var/log/fra-report.log 2>&1

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Finished (container: $CONTAINER_NAME)" >> /var/log/fra-report.log
```

### Log File

All output is logged to `/var/log/fra-report.log`.

```bash
# View recent log entries
tail -100 /var/log/fra-report.log

# Follow log in real-time
tail -f /var/log/fra-report.log

# Check for errors
grep -i error /var/log/fra-report.log
```

### Verify Cron is Running

```bash
# Check crontab
crontab -l

# Check if cron service is running
systemctl status cron
```

---

## Environment Variables Reference

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `MONGO_URI` | Yes | `mongodb://localhost:27017` | Local MongoDB for message queries |
| `DB_NAME` | Yes | `amtk_reports` | Database name |
| `AWS_REGION` | Yes | `us-east-1` | AWS region for S3 and SSM |
| `INIT_YEAR` | No | Auto-detect | Year to process (e.g., `2025`) |
| `INIT_MONTH` | No | Auto-detect | Month to process (1-12) |
| `DOCDB_URI` | No | From Parameter Store | DocumentDB connection string |
| `S3_BUCKET` | No | `rwn-amtk-report-prod` | S3 bucket for reports |
| `SSM_DOCDB_PARAM` | No | `/fra-reporter/docdb-uri` | Parameter Store path for DOCDB_URI |
| `MESSAGES_COLLECTION` | No | `amtk_messages` | Collection containing messages |
| `RETENTION_DAYS` | No | `90` | Days to keep messages (0 = no cleanup) |
| `INDEX_WAIT_SECONDS` | No | `30` | Seconds to wait after restore |

---

## Verifying Results

### Check S3 for Uploaded Reports

```bash
aws s3 ls s3://rwn-amtk-report-prod/ --recursive | tail -20
```

### Check Report Metadata in DocumentDB

```bash
mongosh "mongodb://your-docdb-uri/amtk_reports" \
  --eval "db.amtk_reports.find().sort({_id: -1}).limit(5).pretty()"
```

### Check Local MongoDB Message Count

```bash
mongosh mongodb://localhost:27017/amtk_reports \
  --eval "db.amtk_messages.countDocuments()"
```

---

## Database Administration

### Epoch Time Reference

| Period | Start Date | End Date | Start Epoch | End Epoch |
|--------|------------|----------|-------------|-----------|
| Jul 2025 | Jul 1, 2025 | Aug 1, 2025 | 1751328000 | 1754006400 |
| Aug 2025 | Aug 1, 2025 | Sep 1, 2025 | 1754006400 | 1756684800 |
| Sep 2025 | Sep 1, 2025 | Oct 1, 2025 | 1756684800 | 1759276800 |
| Q3 2025 | Jul 1, 2025 | Oct 1, 2025 | 1751328000 | 1759276800 |
| Q4 2025 | Oct 1, 2025 | Jan 1, 2026 | 1759276800 | 1767225600 |
| Jan 2026 | Jan 1, 2026 | Feb 1, 2026 | 1767225600 | 1769904000 |
| Feb 2026 | Feb 1, 2026 | Mar 1, 2026 | 1769904000 | 1772323200 |

### Calculate Epoch Timestamps

```bash
# Linux
date -d "2026-01-01" +%s

# macOS
date -j -f "%Y-%m-%d" "2026-01-01" "+%s"
```

### Clean Up Old Messages (Local MongoDB)

Delete messages older than 90 days to free disk space:

```bash
# Calculate 90 days ago in epoch seconds
CUTOFF=$(date -d "90 days ago" +%s)

# Delete old messages
mongosh mongodb://localhost:27017/amtk_reports \
  --eval "db.amtk_messages.deleteMany({time: {\$lt: $CUTOFF}})"
```

**Delete messages for a specific date range:**

```bash
# Delete July 2025 messages (after reports are generated)
mongosh mongodb://localhost:27017/amtk_reports \
  --eval "db.amtk_messages.deleteMany({time: {\$gte: 1751328000, \$lt: 1754006400}})"
```

### Check Message Date Range

```bash
# Find oldest and newest messages
mongosh mongodb://localhost:27017/amtk_reports --eval "
  const oldest = db.amtk_messages.find().sort({time: 1}).limit(1).toArray()[0];
  const newest = db.amtk_messages.find().sort({time: -1}).limit(1).toArray()[0];
  print('Oldest:', new Date(oldest.time * 1000).toISOString());
  print('Newest:', new Date(newest.time * 1000).toISOString());
"
```

### Count Messages by Month

```bash
mongosh mongodb://localhost:27017/amtk_reports --eval "
  db.amtk_messages.aggregate([
    {\$group: {
      _id: {\$dateToString: {format: '%Y-%m', date: {\$toDate: {\$multiply: ['\$time', 1000]}}}},
      count: {\$sum: 1}
    }},
    {\$sort: {_id: 1}}
  ])
"
```

### Regenerate Reports (Delete Existing Metadata)

Reports are skipped if metadata already exists. To regenerate:

```bash
# Delete specific report metadata
mongosh "mongodb://your-docdb-uri/amtk_reports" --eval "
  db.amtk_reports.deleteMany({
    year: 2025,
    month: 7,
    type: 'monthly'
  })
"

# Then re-run the report generator
```

---

## Troubleshooting

### "No messages found for time range"
- Verify epoch timestamps are correct
- Check that DocumentDB/local MongoDB has data for the period
- Run message count query to verify data exists

### "Connection refused" to MongoDB
- Ensure MongoDB is running: `docker ps` or `systemctl status mongod`
- Check the connection URI is correct
- Verify network connectivity

### "Cannot generate reports for YYYY-MM: month has not completed yet"
- You cannot generate reports for the current or future months
- Wait until the month ends, or specify a past month

### "Custom endpoint `` was not a valid URI"
- This occurs with older Docker images where `AWS_ENDPOINT_URL` was set to empty string
- Update to v1.3.7 or later

### "0 document(s) restored successfully"
- Check mongorestore path matches dump structure
- Use `--nsInclude` without `--db` flag

### "Access denied" to S3
- Verify AWS credentials (instance profile or environment variables)
- Check IAM permissions for the S3 bucket

### Reports already exist
- The generator skips reports that already exist in the database
- Delete existing report records from DocumentDB to regenerate (see above)

### Container name conflict
```bash
# Remove the existing container first
docker rm fra-report-run
```

---

## Version History

| Version | Changes |
|---------|---------|
| v1.3.7 | Fixed AWS_ENDPOINT_URL unbound variable, fixed mongorestore path |
| v1.3.6 | Fixed AWS_ENDPOINT_URL empty string causing S3 upload failure |
| v1.3.5 | Added SSM Parameter Store support for DOCDB_URI |
| v1.3.4 | Fixed quarterly report logic (trigger on quarter-end months) |
