package dev.dolu.aiservice.service;

import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.*;

@Service
public class ImageModerationService {
    private static final Logger logger = LoggerFactory.getLogger(ImageModerationService.class);

    @Value("${google.vision.credentials.path}")
    private String credentialsPath;

    @PostConstruct
    public void init() {
        System.setProperty("GOOGLE_APPLICATION_CREDENTIALS", credentialsPath);
        logger.info("GOOGLE_APPLICATION_CREDENTIALS set to: {}", credentialsPath);

        java.io.File credentialsFile = new java.io.File(credentialsPath);
        if (credentialsFile.exists()) {
            logger.info("✅ Credentials file FOUND at path.");
        } else {
            logger.error("❌ Credentials file NOT FOUND at path: {}", credentialsPath);
        }
    }

    public ModerationResult moderateImage(byte[] imageBytes) throws IOException {
        ByteString imgBytes = ByteString.copyFrom(imageBytes);
        Image img = Image.newBuilder().setContent(imgBytes).build();
        Feature safeSearch = Feature.newBuilder().setType(Feature.Type.SAFE_SEARCH_DETECTION).build();
        Feature labelDetection = Feature.newBuilder().setType(Feature.Type.LABEL_DETECTION).build();
        AnnotateImageRequest request = AnnotateImageRequest.newBuilder()
                .addFeatures(safeSearch)
                .addFeatures(labelDetection)
                .setImage(img)
                .build();

        com.google.auth.oauth2.GoogleCredentials credentials = com.google.auth.oauth2.GoogleCredentials.fromStream(new java.io.FileInputStream(credentialsPath))
            .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
            .setCredentialsProvider(() -> credentials)
            .build();
        try (ImageAnnotatorClient client = ImageAnnotatorClient.create(settings)) {
            List<AnnotateImageRequest> requests = Collections.singletonList(request);
            BatchAnnotateImagesResponse response = client.batchAnnotateImages(requests);
            AnnotateImageResponse res = response.getResponses(0);
            if (res.hasError()) {
                logger.error("Vision API error: {}", res.getError().getMessage());
                throw new IOException("Vision API error: " + res.getError().getMessage());
            }
            // Map labels to string list
            List<String> labels = new ArrayList<>();
            for (EntityAnnotation annotation : res.getLabelAnnotationsList()) {
                labels.add(annotation.getDescription());
            }
            SafeSearchAnnotation safeSearchAnnotation = res.getSafeSearchAnnotation();
            return new ModerationResult(labels, safeSearchAnnotation);
        }
    }

    public static class ModerationResult {
        public final List<String> labels;
        public final SafeSearchAnnotation safeSearch;
        public ModerationResult(List<String> labels, SafeSearchAnnotation safeSearch) {
            this.labels = labels;
            this.safeSearch = safeSearch;
        }
    }
}

