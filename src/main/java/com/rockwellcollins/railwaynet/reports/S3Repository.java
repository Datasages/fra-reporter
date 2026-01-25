package com.rockwellcollins.railwaynet.reports;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.util.Properties;

public class S3Repository {
    private static final Logger logger = LoggerFactory.getLogger(S3Repository.class);

    private final S3Client s3client;
    private final String bucketName;

    public S3Repository(Properties config) {
        this.bucketName = config.getProperty("aws.s3bucket.report");

        S3ClientBuilder builder = S3Client.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.of(config.getProperty("aws.api.region")));

        // Support custom endpoint for LocalStack testing
        String endpointUrl = System.getenv("AWS_ENDPOINT_URL");
        if (endpointUrl != null && !endpointUrl.isEmpty()) {
            logger.info("Using custom S3 endpoint: " + endpointUrl);
            builder.endpointOverride(URI.create(endpointUrl))
                   .forcePathStyle(true);  // Required for LocalStack
        }

        this.s3client = builder.build();
    }

    public void upload(String fileName, String title, String type, int year, int term) {
        logger.debug("Uploading " + fileName + " to S3");

        File file = new File(fileName);
        if (!file.exists()) {
            logger.warn("The file doesn't exist, nothing to upload!");
            return;
        }

        PutObjectRequest request = PutObjectRequest.builder()
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

        this.s3client.putObject(request, RequestBody.fromFile(file));
    }

}
