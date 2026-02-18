package com.calorietracker.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/media")
public class MediaController {

    private static final Set<String> ALLOWED_FOLDERS = Set.of("ingredients", "dishes");
    private final Path mediaRoot;

    public MediaController(@Value("${app.media.root:media}") String mediaRoot) {
        this.mediaRoot = Paths.get(mediaRoot).toAbsolutePath().normalize();
    }

    @GetMapping("/{folder}/{filename:.+}")
    public ResponseEntity<Resource> getMedia(
        @PathVariable String folder,
        @PathVariable String filename
    ) {
        String safeFolder = normalizeFolder(folder);
        if (!ALLOWED_FOLDERS.contains(safeFolder)) {
            throw new ResponseStatusException(NOT_FOUND, "Media folder not found.");
        }
        if (!StringUtils.hasText(filename)) {
            throw new ResponseStatusException(BAD_REQUEST, "Filename is required.");
        }

        Path baseFolder = mediaRoot.resolve(safeFolder).normalize();
        Path mediaFile = baseFolder.resolve(filename).normalize();
        if (!mediaFile.startsWith(baseFolder)) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid file path.");
        }
        if (!Files.exists(mediaFile) || !Files.isRegularFile(mediaFile)) {
            throw new ResponseStatusException(NOT_FOUND, "Media file not found.");
        }

        try {
            Resource resource = new UrlResource(mediaFile.toUri());
            if (!resource.exists()) {
                throw new ResponseStatusException(NOT_FOUND, "Media file not found.");
            }

            MediaType mediaType = resolveMediaType(mediaFile);
            return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                .contentType(mediaType)
                .body(resource);
        } catch (IOException exception) {
            throw new ResponseStatusException(NOT_FOUND, "Unable to read media file.");
        }
    }

    private String normalizeFolder(String value) {
        return String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private MediaType resolveMediaType(Path mediaFile) throws IOException {
        String detected = Files.probeContentType(mediaFile);
        if (StringUtils.hasText(detected)) {
            try {
                return MediaType.parseMediaType(detected);
            } catch (Exception ignored) {
                return MediaType.APPLICATION_OCTET_STREAM;
            }
        }

        String file = mediaFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (file.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (file.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (file.endsWith(".jpg") || file.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
