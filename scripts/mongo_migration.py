#!/usr/bin/env python3
"""
DocumentDB to MongoDB Migration Script
Copies documents from DocumentDB to MongoDB for the previous month and cleans up old records.
"""

import os
import sys
import time
import logging
from datetime import datetime, timedelta
from calendar import monthrange
from typing import Dict, Any, Optional
import pymongo
from pymongo import MongoClient
from pymongo.errors import ConnectionFailure, ServerSelectionTimeoutError
import argparse

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)

class MongoMigrator:
    """Handles migration from DocumentDB to MongoDB"""

    def __init__(self,
                 source_uri: str,
                 target_uri: str,
                 source_db: str,
                 target_db: str,
                 collection: str,
                 batch_size: int = 1000):
        """
        Initialize the migrator

        Args:
            source_uri: DocumentDB connection string
            target_uri: MongoDB connection string
            source_db: Source database name
            target_db: Target database name
            collection: Collection name to migrate
            batch_size: Number of documents to process in each batch
        """
        self.source_uri = source_uri
        self.target_uri = target_uri
        self.source_db = source_db
        self.target_db = target_db
        self.collection = collection
        self.batch_size = batch_size

        self.source_client = None
        self.target_client = None

    def connect(self) -> bool:
        """Establish connections to both databases"""
        try:
            logger.info("Connecting to DocumentDB...")
            self.source_client = MongoClient(
                self.source_uri,
                serverSelectionTimeoutMS=30000,
                connectTimeoutMS=30000,
                socketTimeoutMS=30000
            )
            # Test source connection
            self.source_client.admin.command('ping')
            logger.info("DocumentDB connection successful")

            logger.info("Connecting to MongoDB...")
            self.target_client = MongoClient(
                self.target_uri,
                serverSelectionTimeoutMS=30000,
                connectTimeoutMS=30000,
                socketTimeoutMS=30000
            )
            # Test target connection
            self.target_client.admin.command('ping')
            logger.info("MongoDB connection successful")

            return True

        except (ConnectionFailure, ServerSelectionTimeoutError) as e:
            logger.error(f"Database connection failed: {e}")
            return False
        except Exception as e:
            logger.error(f"Unexpected error during connection: {e}")
            return False

    def disconnect(self):
        """Close database connections"""
        if self.source_client:
            self.source_client.close()
            logger.info("DocumentDB connection closed")
        if self.target_client:
            self.target_client.close()
            logger.info("MongoDB connection closed")

    def get_previous_month_timestamps(self) -> tuple[int, int]:
        """
        Calculate Unix timestamps for the previous month

        Returns:
            Tuple of (start_timestamp, end_timestamp) for previous month
        """
        now = datetime.utcnow()

        # Get first day of current month
        first_day_current = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)

        # Get last day of previous month
        last_day_previous = first_day_current - timedelta(days=1)

        # Get first day of previous month
        first_day_previous = last_day_previous.replace(day=1, hour=0, minute=0, second=0, microsecond=0)

        # Convert to Unix timestamps
        start_timestamp = int(first_day_previous.timestamp())
        end_timestamp = int(last_day_previous.replace(hour=23, minute=59, second=59).timestamp())

        logger.info(f"Previous month: {first_day_previous.strftime('%Y-%m-%d')} to {last_day_previous.strftime('%Y-%m-%d')}")
        logger.info(f"Timestamp range: {start_timestamp} to {end_timestamp}")

        return start_timestamp, end_timestamp

    def get_cleanup_timestamp(self) -> int:
        """
        Calculate Unix timestamp for 120 days ago

        Returns:
            Unix timestamp for 120 days ago
        """
        cutoff_date = datetime.utcnow() - timedelta(days=120)
        cutoff_timestamp = int(cutoff_date.timestamp())

        logger.info(f"Cleanup cutoff date: {cutoff_date.strftime('%Y-%m-%d %H:%M:%S')} UTC")
        logger.info(f"Cleanup timestamp: {cutoff_timestamp}")

        return cutoff_timestamp

    def copy_documents(self, start_time: int, end_time: int) -> int:
        """
        Copy documents from DocumentDB to MongoDB for the specified time range

        Args:
            start_time: Start Unix timestamp
            end_time: End Unix timestamp

        Returns:
            Number of documents copied
        """
        try:
            source_collection = self.source_client[self.source_db][self.collection]
            target_collection = self.target_client[self.target_db][self.collection]

            # Query for documents in the time range
            query = {
                "time": {
                    "$gt": start_time,
                    "$lte": end_time
                }
            }

            # Count total documents to copy
            total_docs = source_collection.count_documents(query)
            logger.info(f"Found {total_docs} documents to copy")

            if total_docs == 0:
                logger.info("No documents found in the specified time range")
                return 0

            # Copy documents in batches
            copied_count = 0
            cursor = source_collection.find(query)

            batch = []
            for doc in cursor:
                batch.append(doc)

                if len(batch) >= self.batch_size:
                    # Insert batch
                    try:
                        result = target_collection.insert_many(batch, ordered=False)
                        copied_count += len(result.inserted_ids)
                        logger.info(f"Copied {copied_count}/{total_docs} documents")
                    except pymongo.errors.BulkWriteError as e:
                        # Handle duplicate key errors gracefully
                        inserted_count = len(e.details.get('writeErrors', []))
                        if inserted_count > 0:
                            copied_count += (len(batch) - inserted_count)
                        logger.warning(f"Bulk write error: {len(e.details.get('writeErrors', []))} duplicates skipped")

                    batch = []

            # Insert remaining documents
            if batch:
                try:
                    result = target_collection.insert_many(batch, ordered=False)
                    copied_count += len(result.inserted_ids)
                except pymongo.errors.BulkWriteError as e:
                    inserted_count = len(e.details.get('writeErrors', []))
                    if inserted_count > 0:
                        copied_count += (len(batch) - inserted_count)
                    logger.warning(f"Final batch: {len(e.details.get('writeErrors', []))} duplicates skipped")

            logger.info(f"Successfully copied {copied_count} documents")
            return copied_count

        except Exception as e:
            logger.error(f"Error copying documents: {e}")
            raise

    def cleanup_old_documents(self, cutoff_timestamp: int) -> int:
        """
        Delete documents older than the cutoff timestamp from target MongoDB

        Args:
            cutoff_timestamp: Unix timestamp cutoff

        Returns:
            Number of documents deleted
        """
        try:
            target_collection = self.target_client[self.target_db][self.collection]

            # Query for old documents
            query = {
                "time": {
                    "$lt": cutoff_timestamp
                }
            }

            # Count documents to delete
            delete_count = target_collection.count_documents(query)
            logger.info(f"Found {delete_count} documents older than 120 days")

            if delete_count == 0:
                logger.info("No old documents to clean up")
                return 0

            # Delete old documents
            result = target_collection.delete_many(query)
            deleted_count = result.deleted_count

            logger.info(f"Successfully deleted {deleted_count} old documents")
            return deleted_count

        except Exception as e:
            logger.error(f"Error deleting old documents: {e}")
            raise

    def run_migration(self) -> bool:
        """
        Run the complete migration process

        Returns:
            True if successful, False otherwise
        """
        try:
            # Connect to databases
            if not self.connect():
                return False

            # Get time ranges
            start_time, end_time = self.get_previous_month_timestamps()
            cleanup_time = self.get_cleanup_timestamp()

            # Copy documents for previous month
            logger.info("Starting document copy...")
            copied_count = self.copy_documents(start_time, end_time)

            # Clean up old documents
            logger.info("Starting cleanup of old documents...")
            deleted_count = self.cleanup_old_documents(cleanup_time)

            logger.info(f"Migration completed successfully:")
            logger.info(f"  - Documents copied: {copied_count}")
            logger.info(f"  - Documents deleted: {deleted_count}")

            return True

        except Exception as e:
            logger.error(f"Migration failed: {e}")
            return False
        finally:
            self.disconnect()

def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(description='DocumentDB to MongoDB Migration')
    parser.add_argument('--source-uri', required=True, help='DocumentDB connection string')
    parser.add_argument('--target-uri', required=True, help='MongoDB connection string')
    parser.add_argument('--source-db', required=True, help='Source database name')
    parser.add_argument('--target-db', required=True, help='Target database name')
    parser.add_argument('--collection', required=True, help='Collection name')
    parser.add_argument('--batch-size', type=int, default=1000, help='Batch size for copying')
    parser.add_argument('--dry-run', action='store_true', help='Dry run mode (no actual changes)')

    args = parser.parse_args()

    # Also support environment variables
    source_uri = args.source_uri or os.getenv('DOCUMENTDB_URI')
    target_uri = args.target_uri or os.getenv('MONGODB_URI')
    source_db = args.source_db or os.getenv('SOURCE_DATABASE', 'reports')
    target_db = args.target_db or os.getenv('TARGET_DATABASE', 'reports')
    collection = args.collection or os.getenv('COLLECTION_NAME', 'messages')

    if not source_uri or not target_uri:
        logger.error("Source and target URIs are required")
        sys.exit(1)

    logger.info("Starting DocumentDB to MongoDB migration")
    logger.info(f"Source DB: {source_db}.{collection}")
    logger.info(f"Target DB: {target_db}.{collection}")
    logger.info(f"Batch size: {args.batch_size}")

    if args.dry_run:
        logger.info("DRY RUN MODE - No changes will be made")
        # TODO: Implement dry run logic
        sys.exit(0)

    # Create migrator and run
    migrator = MongoMigrator(
        source_uri=source_uri,
        target_uri=target_uri,
        source_db=source_db,
        target_db=target_db,
        collection=collection,
        batch_size=args.batch_size
    )

    success = migrator.run_migration()
    sys.exit(0 if success else 1)

if __name__ == '__main__':
    main()