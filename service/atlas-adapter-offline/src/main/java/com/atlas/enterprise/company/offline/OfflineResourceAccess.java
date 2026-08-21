package com.atlas.enterprise.company.offline;

import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

@Component
class OfflineResourceAccess {
    private final OfflineDataProperties properties;
    private final ResourceLoader resourceLoader;

    OfflineResourceAccess(OfflineDataProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    Resource csv(String fileName) {
        String root = properties.getRoot();
        if (root.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)) {
            String separator = root.endsWith("/") ? "" : "/";
            return resourceLoader.getResource(root + separator + fileName);
        }
        if (root.startsWith("file:")) {
            String separator = root.endsWith("/") || root.endsWith("\\") ? "" : "/";
            return resourceLoader.getResource(root + separator + fileName);
        }
        return new FileSystemResource(Path.of(root).resolve(fileName).normalize());
    }

    Resource location(String location) {
        if (location.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX) || location.startsWith("file:")) {
            return resourceLoader.getResource(location);
        }
        return new FileSystemResource(Path.of(location).normalize());
    }
}
