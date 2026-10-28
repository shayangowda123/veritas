package com.veritas.storage;

import com.veritas.common.exception.FileStorageException;
import com.veritas.common.exception.InvalidFileException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

@Service
public class LocalStorageService implements StorageService {

    public static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final Path rootLocation;

    public LocalStorageService(
            @Value("${veritas.storage.local.base-dir:./uploads/property-images}") String baseDir
    ) {
        this.rootLocation = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new FileStorageException("Could not initialize storage location", e);
        }
    }

    @Override
    public String store(MultipartFile file, String subDirectory) {
        if (file == null || file.isEmpty()) {
            throw new InvalidFileException("Cannot store an empty or missing file");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new InvalidFileException("File size exceeds maximum permitted limit of 5MB");
        }

        String detectedExtension = detectAndValidateImageFormat(file);

        // Sanitize subDirectory to prevent path traversal
        String safeSubDir = (subDirectory != null)
                ? subDirectory.replaceAll("[^a-zA-Z0-9_-]", "")
                : "";

        Path destinationFolder = safeSubDir.isEmpty()
                ? rootLocation
                : rootLocation.resolve(safeSubDir).normalize();

        if (!destinationFolder.startsWith(rootLocation)) {
            throw new FileStorageException("Directory traversal attempt detected in target folder");
        }

        try {
            Files.createDirectories(destinationFolder);
        } catch (IOException e) {
            throw new FileStorageException("Failed to create destination directory", e);
        }

        // Generate safe unique filename
        String safeFilename = UUID.randomUUID() + detectedExtension;
        Path destinationFile = destinationFolder.resolve(safeFilename).normalize();

        if (!destinationFile.startsWith(rootLocation)) {
            throw new FileStorageException("Directory traversal attempt detected in filename");
        }

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new FileStorageException("Failed to store file: " + safeFilename, e);
        }

        return rootLocation.relativize(destinationFile).toString().replace('\\', '/');
    }

    @Override
    public Resource loadAsResource(String storageKey) {
        try {
            if (storageKey == null || storageKey.isBlank()) {
                throw new InvalidFileException("Storage key cannot be blank");
            }

            Path file = rootLocation.resolve(storageKey).normalize();

            if (!file.startsWith(rootLocation)) {
                throw new FileStorageException("Access denied: path traversal attempt");
            }

            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new FileStorageException("File not found or unreadable: " + storageKey);
            }
        } catch (MalformedURLException e) {
            throw new FileStorageException("Invalid file URL for storage key: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }

        Path file = rootLocation.resolve(storageKey).normalize();

        if (!file.startsWith(rootLocation)) {
            throw new FileStorageException("Access denied: path traversal attempt during deletion");
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new FileStorageException("Could not delete file: " + storageKey, e);
        }
    }

    /**
     * Inspects magic bytes from the stream to verify actual image file format.
     * Does NOT trust client-supplied Content-Type or file extension.
     */
    private String detectAndValidateImageFormat(MultipartFile file) {
        byte[] header = new byte[12];
        int bytesRead;

        try (InputStream is = file.getInputStream()) {
            bytesRead = is.read(header);
        } catch (IOException e) {
            throw new InvalidFileException("Failed to read file content for format validation");
        }

        if (bytesRead < 8) {
            throw new InvalidFileException("File too small to be a valid image");
        }

        // JPEG: FF D8 FF
        if ((header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return ".jpg";
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        byte[] pngSignature = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        if (bytesRead >= 8 && Arrays.equals(Arrays.copyOfRange(header, 0, 8), pngSignature)) {
            return ".png";
        }

        // WebP: RIFF (bytes 0-3: 52 49 46 46) and WEBP (bytes 8-11: 57 45 42 50)
        if (bytesRead >= 12
                && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46
                && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
            return ".webp";
        }

        throw new InvalidFileException("Unsupported file type. Only real JPEG, PNG, and WebP images are permitted.");
    }
}
