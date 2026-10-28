package com.veritas.storage;

import com.veritas.common.exception.FileStorageException;
import com.veritas.common.exception.InvalidFileException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalStorageServiceTest {

    @TempDir
    Path tempDir;

    private LocalStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new LocalStorageService(tempDir.toString());
        storageService.init();
    }

    @Test
    @DisplayName("Should successfully store a valid JPEG image with real magic bytes")
    void shouldStoreValidJpegImage() {
        byte[] validJpegContent = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg", validJpegContent);

        String storageKey = storageService.store(file, "prop-123");

        assertNotNull(storageKey);
        assertTrue(storageKey.startsWith("prop-123/"));
        assertTrue(storageKey.endsWith(".jpg"));

        Resource resource = storageService.loadAsResource(storageKey);
        assertTrue(resource.exists());
        assertTrue(resource.isReadable());
    }

    @Test
    @DisplayName("Should successfully store a valid PNG image with real magic bytes")
    void shouldStoreValidPngImage() {
        byte[] validPngContent = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", validPngContent);

        String storageKey = storageService.store(file, "prop-456");

        assertNotNull(storageKey);
        assertTrue(storageKey.endsWith(".png"));

        Resource resource = storageService.loadAsResource(storageKey);
        assertTrue(resource.exists());
    }

    @Test
    @DisplayName("Should reject file disguised as image (fake extension and text content)")
    void shouldRejectDisguisedTextFile() {
        byte[] textContent = "This is a malicious script or text file disguised as jpg".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "malicious.jpg", "image/jpeg", textContent);

        assertThrows(InvalidFileException.class, () -> storageService.store(file, "prop-123"));
    }

    @Test
    @DisplayName("Should reject empty file")
    void shouldRejectEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

        assertThrows(InvalidFileException.class, () -> storageService.store(file, "prop-123"));
    }

    @Test
    @DisplayName("Should reject file exceeding maximum allowed size")
    void shouldRejectOversizedFile() {
        byte[] largeContent = new byte[(int) LocalStorageService.MAX_FILE_SIZE_BYTES + 1024];
        largeContent[0] = (byte) 0xFF;
        largeContent[1] = (byte) 0xD8;
        largeContent[2] = (byte) 0xFF;
        MockMultipartFile file = new MockMultipartFile("file", "huge.jpg", "image/jpeg", largeContent);

        assertThrows(InvalidFileException.class, () -> storageService.store(file, "prop-123"));
    }

    @Test
    @DisplayName("Should prevent path traversal attacks when loading resources")
    void shouldPreventPathTraversalOnLoad() {
        assertThrows(FileStorageException.class, () -> storageService.loadAsResource("../../../windows/system32/config/SAM"));
        assertThrows(FileStorageException.class, () -> storageService.loadAsResource("..\\..\\secret.txt"));
    }

    @Test
    @DisplayName("Should prevent path traversal attacks when deleting files")
    void shouldPreventPathTraversalOnDelete() {
        assertThrows(FileStorageException.class, () -> storageService.delete("../../../etc/passwd"));
    }

    @Test
    @DisplayName("Should delete stored file correctly")
    void shouldDeleteFileCorrectly() throws IOException {
        byte[] validJpegContent = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01};
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", validJpegContent);

        String key = storageService.store(file, "delete-test");
        assertTrue(storageService.loadAsResource(key).exists());

        storageService.delete(key);
        assertThrows(FileStorageException.class, () -> storageService.loadAsResource(key));
    }
}
