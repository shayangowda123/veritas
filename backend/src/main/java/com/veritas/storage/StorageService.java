package com.veritas.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface StorageService {

    /**
     * Stores a validated file into the specified sub-directory and returns a safe storage key.
     *
     * @param file the multipart file to store
     * @param subDirectory optional category/subfolder (e.g. property id)
     * @return the relative safe storage key
     */
    String store(MultipartFile file, String subDirectory);

    /**
     * Loads a file as a Spring Resource given its storage key.
     *
     * @param storageKey the storage key
     * @return the Resource
     */
    Resource loadAsResource(String storageKey);

    /**
     * Deletes a stored file given its storage key.
     *
     * @param storageKey the storage key
     */
    void delete(String storageKey);
}
