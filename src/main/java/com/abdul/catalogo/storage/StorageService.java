package com.abdul.catalogo.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

public interface StorageService {
    String store(String relativeKey, InputStream input, long size) throws IOException;
    Resource load(String relativeKey);
    void delete(String relativeKey) throws IOException;
}
