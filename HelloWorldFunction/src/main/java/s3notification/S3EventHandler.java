package s3notification;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;
import software.amazon.awssdk.services.sns.model.SnsException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;


public class S3EventHandler implements RequestHandler<S3Event, String> {
    private static final Logger logger = LoggerFactory.getLogger(S3EventHandler.class);
    private final SnsClient snsClient;
    private final S3Client s3Client;
    private final String snsTopicArn;
    private final String environment;


    public S3EventHandler() {
        this.snsClient = SnsClient
                .builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.EU_CENTRAL_1)
                .build();
        this.s3Client = S3Client
                .builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .region(Region.EU_CENTRAL_1)
                .build();
        this.snsTopicArn = System.getenv("SNS_TOPIC_ARN");
        this.environment = System.getenv("ENVIRONMENT");

        if (snsTopicArn == null || snsTopicArn.isEmpty()) {
            throw new IllegalStateException("SNS_TOPIC_ARN environment variable is not set");
        }
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        logger.info("Received S3 event with {} records", s3Event.getRecords().size());

        try {
            s3Event.getRecords().forEach(this::processS3Record);
            return "Successfully processed " + s3Event.getRecords().size() + " records";
        } catch (Exception e) {
            logger.error("Error processing S3 event", e);
            throw new RuntimeException("Error processing S3 event", e);
        }
    }

    private void processS3Record(S3EventNotificationRecord record) {
        String bucket = record.getS3().getBucket().getName();
        // URL decode the object key as S3 encodes special characters
        String key = URLDecoder.decode(record.getS3().getObject().getKey(), StandardCharsets.UTF_8);
        String eventTime = String.valueOf(record.getEventTime());
        String eventName = record.getEventName();

        logger.info("Processing S3 event: {} for object s3://{}/{}", eventName, bucket, key);

        // Get additional object metadata
        HeadObjectResponse objectMetadata = getObjectMetadata(bucket, key);
        String contentType = objectMetadata != null ? objectMetadata.contentType() : "unknown";
        long contentLength = objectMetadata != null ? objectMetadata.contentLength() : 0;

        // Create a notification message
        String subject = String.format("[%s] New file uploaded to S3: %s", environment.toUpperCase(), key);
        String message = createNotificationMessage(bucket, key, eventName, eventTime, contentType, contentLength);

        // Send the notification
        publishToSNS(subject, message);
    }

    private HeadObjectResponse getObjectMetadata(String bucket, String key) {
        try {
            HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();

            return s3Client.headObject(headObjectRequest);
        } catch (Exception e) {
            logger.warn("Could not retrieve object metadata for s3://{}/{}: {}", bucket, key, e.getMessage());
            return null;
        }
    }

    private String createNotificationMessage(String bucket, String key, String eventName, String eventTime,
                                             String contentType, long contentLength) {
        StringBuilder message = new StringBuilder();
        message.append(String.format("A new file has been uploaded to your S3 bucket in the %s environment.%n%n", environment));
        message.append("File Details:%n");
        message.append(String.format("- Bucket: %s%n", bucket));
        message.append(String.format("- File: %s%n", key));
        message.append(String.format("- Event: %s%n", eventName));
        message.append(String.format("- Time: %s%n", eventTime));
        message.append(String.format("- Content Type: %s%n", contentType));
        message.append(String.format("- Size: %d bytes%n", contentLength));
        message.append("%nThis is an automated notification from the AWS S3 Event-Driven Architecture.");

        return message.toString();
    }

    private void publishToSNS(String subject, String message) {
        try {
            PublishRequest request = PublishRequest.builder()
                    .topicArn(snsTopicArn)
                    .subject(subject)
                    .message(message)
                    .build();

            PublishResponse response = snsClient.publish(request);
            logger.info("Notification published to SNS. Message ID: {}", response.messageId());
        } catch (SnsException e) {
            logger.error("Error publishing to SNS", e);
            throw e;
        }
    }

}