package dev.dolu.aiservice.controller;

import dev.dolu.aiservice.service.ImageModerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/image")
public class ImageVerificationController {
    private static final Logger logger = LoggerFactory.getLogger(ImageVerificationController.class);

    private final ImageModerationService moderationService;

    @Value("${image.allowed-types:image/jpeg,image/png}")
    private String allowedTypes;

    @Value("${image.max-size-bytes:5242880}") // 5MB default
    private long maxSizeBytes;

    public ImageVerificationController(ImageModerationService moderationService) {
        this.moderationService = moderationService;
    }

    @PostMapping(value = "/verify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> verifyImage(@RequestParam MultipartFile file) {
        // Validate file presence
        if (file == null || file.isEmpty()) {
            logger.warn("No file uploaded");
            return ResponseEntity.badRequest().body(Map.of("error", "No file uploaded"));
        }
        // Validate file type
        List<String> allowed = Arrays.asList(allowedTypes.split(","));
        if (!allowed.contains(file.getContentType())) {
            logger.warn("Invalid file type: {}", file.getContentType());
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(Map.of("error", "Invalid file type. Allowed: " + allowedTypes));
        }
        // Validate file size
        if (file.getSize() > maxSizeBytes) {
            logger.warn("File too large: {} bytes", file.getSize());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "File too large. Max: " + maxSizeBytes + " bytes"));
        }
        try {
            ImageModerationService.ModerationResult result = moderationService.moderateImage(file.getBytes());
            // Example: check for NSFW and real estate relevance
            boolean nsfw = isLikelyNSFW(result.safeSearch);
            boolean realEstateRelevant = isRealEstateRelevant(result.labels);
            Map<String, Object> response = new HashMap<>();
            response.put("nsfw", nsfw);
            response.put("realEstateRelevant", realEstateRelevant);
            response.put("labels", result.labels);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            logger.error("Image moderation failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Image moderation failed: " + e.getMessage()));
        }
    }

    private boolean isLikelyNSFW(com.google.cloud.vision.v1.SafeSearchAnnotation safeSearch) {
        if (safeSearch == null) return false;
        // Consider NSFW if adult or violence is LIKELY or VERY_LIKELY
        return isLikelyOrWorse(safeSearch.getAdult()) || isLikelyOrWorse(safeSearch.getViolence())
                || isLikelyOrWorse(safeSearch.getRacy());
    }
    private boolean isLikelyOrWorse(com.google.cloud.vision.v1.Likelihood likelihood) {
        return likelihood == com.google.cloud.vision.v1.Likelihood.LIKELY
                || likelihood == com.google.cloud.vision.v1.Likelihood.VERY_LIKELY;
    }
    private boolean isRealEstateRelevant(List<String> labels) {
        Set<String> keywords = Set.of(
                // Traditional property terms
                "house", "home", "apartment", "flat", "building", "real estate", "property", "villa", "duplex", "condo",

                // Interior and room-related
                "bedroom", "living room", "dining room", "kitchen", "bathroom", "balcony", "interior", "hallway",

                // Furniture and decor
                "furniture", "sofa", "bed", "pillow", "mattress", "wardrobe", "table", "chair", "lamp", "rug",

                // Short stay / hospitality
                "suite", "hotel", "airbnb", "room", "residence", "lodging", "accommodation",

                // Amenities
                "pool", "swimming pool", "gym", "spa", "terrace", "garden", "garage", "parking", "view"
        );

        for (String label : labels) {
            String lower = label.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }
}

