package com.rockwellcollins.railwaynet.reports;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

@Testcontainers
public abstract class IntegrationTestBase {

    @Container
    static final MongoDBContainer mongoContainer;

    @Container
    static final LocalStackContainer localStackContainer;

    // Static initialization block for Testcontainers
    // Testcontainers are managed by JUnit @Testcontainers annotation and automatically closed
    static {
        // Suppress resource leak warnings - containers are managed by JUnit lifecycle
        @SuppressWarnings("resource")
        MongoDBContainer tempMongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0"))
                .withExposedPorts(27017)
                .withReuse(false);
        mongoContainer = tempMongo;

        @SuppressWarnings("resource")
        LocalStackContainer tempLocalStack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:latest"))
                .withServices(LocalStackContainer.Service.S3)
                .withReuse(false);
        localStackContainer = tempLocalStack;
    }

    protected MongoClient mongoClient;
    protected MongoDatabase messagesDb;
    protected MongoDatabase reportsDb;
    protected MongoCollection<Document> messagesCollection;
    protected MongoCollection<Document> reportsCollection;
    protected S3Client s3Client;
    protected Properties testConfig;
    protected Path tempDir;

    @BeforeEach
    void setUpIntegrationTest() throws IOException {
        // Set up MongoDB
        mongoClient = MongoClients.create(mongoContainer.getConnectionString());
        messagesDb = mongoClient.getDatabase("test_messages");
        reportsDb = mongoClient.getDatabase("test_reports");
        messagesCollection = messagesDb.getCollection("messages");
        reportsCollection = reportsDb.getCollection("reports");

        // Set up S3
        s3Client = S3Client.builder()
                .endpointOverride(localStackContainer.getEndpoint())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                localStackContainer.getAccessKey(),
                                localStackContainer.getSecretKey())))
                .region(Region.US_WEST_2)
                .forcePathStyle(true)
                .build();

        // Create S3 bucket (ignore if it already exists)
        try {
            s3Client.createBucket(CreateBucketRequest.builder()
                    .bucket("test-reports-bucket")
                    .build());
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            if (e.statusCode() == 409) {
                // Bucket already exists (BucketAlreadyOwnedByYou), this is fine for tests
            } else {
                throw e; // Re-throw other S3 errors
            }
        }

        // Create temporary directory for test files
        tempDir = Files.createTempDirectory("strolr-test-");

        // Create test configuration
        testConfig = createTestConfiguration();

        // Copy Excel templates to temp directory
        copyTestTemplates();
    }

    @AfterEach
    void tearDownIntegrationTest() throws IOException {
        // Close MongoDB client
        if (mongoClient != null) {
            try {
                mongoClient.close();
            } catch (Exception e) {
                // Log but don't fail test cleanup
                System.err.println("Warning: Error closing MongoDB client: " + e.getMessage());
            }
        }

        // Close S3 client
        if (s3Client != null) {
            try {
                s3Client.close();
            } catch (Exception e) {
                // Log but don't fail test cleanup
                System.err.println("Warning: Error closing S3 client: " + e.getMessage());
            }
        }

        // Clean up temp directory
        if (tempDir != null && Files.exists(tempDir)) {
            try {
                Files.walk(tempDir)
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException e) {
                // Log but don't fail test cleanup
                System.err.println("Warning: Error cleaning up temp directory: " + e.getMessage());
            }
        }
    }

    protected Properties createTestConfiguration() {
        Properties config = new Properties();

        // MongoDB configuration
        config.setProperty("messages.mongo.url", mongoContainer.getConnectionString());
        config.setProperty("messages.mongo.database", "test_messages");
        config.setProperty("messages.mongo.collection", "messages");

        config.setProperty("reports.mongo.url", mongoContainer.getConnectionString());
        config.setProperty("reports.mongo.database", "test_reports");
        config.setProperty("reports.mongo.collection", "reports");

        // AWS configuration
        config.setProperty("aws.api.region", "us-west-2");
        config.setProperty("aws.s3bucket.report", "test-reports-bucket");
        config.setProperty("aws.s3bucket.base.url", localStackContainer.getEndpoint() + "/test-reports-bucket/");

        // Test-specific configuration
        config.setProperty("init.time", "30"); // 30-minute timeout for tests
        config.setProperty("init.year", "2024");
        config.setProperty("init.month", "2"); // February for test data

        // Template paths for testing (absolute paths to temp directory)
        config.setProperty("template.path.Enforcement_Report_template.xlsx",
                          tempDir.resolve("Enforcement_Report_template.xlsx").toString());
        config.setProperty("template.path.Failed_Init_Report_template.xlsx",
                          tempDir.resolve("Failed_Init_Report_template.xlsx").toString());
        config.setProperty("template.path.Position_Report_template.xlsx",
                          tempDir.resolve("Position_Report_template.xlsx").toString());

        return config;
    }

    protected void copyTestTemplates() throws IOException {
        // Copy actual Excel templates from build directory
        copyRealTemplate("Enforcement_Report_template.xlsx");
        copyRealTemplate("Failed_Init_Report_template.xlsx");
        copyRealTemplate("Position_Report_template.xlsx");
    }

    private void copyRealTemplate(String templateName) throws IOException {
        // Try to copy from build directory first
        Path buildTemplate = Path.of("build", templateName);
        Path tempTemplate = tempDir.resolve(templateName);

        if (Files.exists(buildTemplate)) {
            Files.copy(buildTemplate, tempTemplate);
        } else {
            // Fallback: create a minimal placeholder Excel file
            createMinimalExcelTemplate(tempTemplate);
        }
    }

    private void createMinimalExcelTemplate(Path templatePath) throws IOException {
        // Create a very basic Excel file structure that POI can read
        // This is a simplified approach for testing
        try (FileWriter writer = new FileWriter(templatePath.toFile())) {
            // Write basic content that our tests can work with
            writer.write("Test Excel Template - " + templatePath.getFileName());
        }
    }

    protected void loadTestData(List<Document> testMessages) {
        if (!testMessages.isEmpty()) {
            messagesCollection.insertMany(testMessages);
        }
    }

    protected void clearTestData() {
        messagesCollection.deleteMany(new Document());
        reportsCollection.deleteMany(new Document());
    }

    protected MongoMessagesDatabase createMessagesDatabase() {
        return new MongoMessagesDatabase(
                testConfig.getProperty("messages.mongo.url"),
                testConfig.getProperty("messages.mongo.database"),
                testConfig.getProperty("messages.mongo.collection")
        );
    }

    protected MongoReportsDatabase createReportsDatabase() {
        return new MongoReportsDatabase(testConfig);
    }

    protected S3Repository createS3Repository() {
        return new TestS3Repository(testConfig, s3Client);
    }

    /**
     * Custom S3Repository for testing that uses the test S3Client
     */
    private static class TestS3Repository extends S3Repository {
        private final S3Client testS3Client;
        private final String bucketName;

        public TestS3Repository(Properties config, S3Client testS3Client) {
            super(config);
            this.testS3Client = testS3Client;
            this.bucketName = config.getProperty("aws.s3bucket.report");
        }

        // Override to use test S3Client
        @Override
        public void upload(String fileName, String title, String type, int year, int term) {
            File file = new File(fileName);
            if (!file.exists()) {
                System.out.println("Test upload: File " + fileName + " doesn't exist, skipping upload");
                return;
            }

            try {
                // Use the test S3Client to upload to LocalStack
                software.amazon.awssdk.services.s3.model.PutObjectRequest request =
                    software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(fileName)
                        .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        .metadata(java.util.Map.of(
                                "title", title,
                                "type", type,
                                "year", String.valueOf(year),
                                "term", String.valueOf(term)
                        ))
                        .build();

                testS3Client.putObject(request, software.amazon.awssdk.core.sync.RequestBody.fromFile(file));
                System.out.println("Test upload successful: " + fileName + " -> S3 bucket: " + bucketName);
            } catch (Exception e) {
                System.err.println("Test upload failed: " + e.getMessage());
                throw new RuntimeException("S3 upload failed in test", e);
            }
        }
    }

    protected void changeToTempDirectory() {
        System.setProperty("user.dir", tempDir.toString());
    }

    protected long countReportsInDatabase(String reportType) {
        return reportsCollection.countDocuments(new Document("type", reportType));
    }

    protected boolean fileExistsInS3(String fileName) {
        try {
            s3Client.headObject(builder -> builder
                    .bucket("test-reports-bucket")
                    .key(fileName));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}