package com.rockwellcollins.railwaynet.reports;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Properties;

public class S3Repository {
    private static final Logger logger = LoggerFactory.getLogger(S3Repository.class);

    private final AmazonS3 s3client;
    private final String bucketName;

    public S3Repository(Properties config) {
        this.bucketName = config.getProperty("aws.api.S3bucket");
        this.s3client = AmazonS3ClientBuilder
                .standard()
                .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
                .withRegion(Regions.fromName(config.getProperty("aws.api.region")))
                .build();
    }

    public void upload(String fileName, String title, String type, int year, int term) {
        logger.debug("Uploading " + fileName + " to S3");

        if (! new File(fileName).exists()) {
            logger.warn("The file doesn't exist, nothing to upload!");
            return;
        }

        PutObjectRequest request = new PutObjectRequest(bucketName, fileName, new File(fileName));
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        metadata.addUserMetadata("title", title);
        metadata.addUserMetadata("type", type);
        metadata.addUserMetadata("year", String.valueOf(year));
        metadata.addUserMetadata("term", String.valueOf(term));
        request.setMetadata(metadata);
        this.s3client.putObject(request);
    }

}
