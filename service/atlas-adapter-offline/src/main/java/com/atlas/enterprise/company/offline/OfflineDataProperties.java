package com.atlas.enterprise.company.offline;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atlas.data.offline")
public class OfflineDataProperties {
    private String root = "../data/company";
    private List<String> jsonFiles = new ArrayList<>();

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public List<String> getJsonFiles() {
        return jsonFiles;
    }

    public void setJsonFiles(List<String> jsonFiles) {
        this.jsonFiles = jsonFiles == null ? new ArrayList<>() : new ArrayList<>(jsonFiles);
    }
}
