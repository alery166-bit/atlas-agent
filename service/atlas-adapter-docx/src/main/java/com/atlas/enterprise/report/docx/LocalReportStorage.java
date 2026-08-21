package com.atlas.enterprise.report.docx;

import com.atlas.enterprise.report.StoredReportObject;
import com.atlas.enterprise.report.application.ReportValidationException;
import com.atlas.enterprise.report.port.ReportStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalReportStorage implements ReportStorage {
    private static final String MIME =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String PREFIX = "local:reports/";

    private final Path reportRoot;

    public LocalReportStorage(@Value("${atlas.storage.root}") String storageRoot) {
        this.reportRoot = Path.of(storageRoot)
            .toAbsolutePath()
            .normalize()
            .resolve("reports");
    }

    @Override
    public StoredReportObject put(UUID reportId, byte[] content) {
        try {
            Files.createDirectories(reportRoot);
            String filename = reportId + ".docx";
            Path target = reportRoot.resolve(filename).normalize();
            requireInside(target);
            String hash = OoxmlDocxSupport.sha256(content);
            if (Files.exists(target)) {
                byte[] existing = Files.readAllBytes(target);
                if (!hash.equals(OoxmlDocxSupport.sha256(existing))) {
                    throw new ReportValidationException(
                        "Generated report id already exists with different content"
                    );
                }
            } else {
                Path temporary = reportRoot.resolve(reportId + ".tmp").normalize();
                Files.write(temporary, content);
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE
                    );
                } catch (IOException atomicMoveUnavailable) {
                    Files.move(temporary, target);
                }
            }
            return new StoredReportObject(
                PREFIX + filename,
                hash,
                Files.size(target),
                MIME
            );
        } catch (IOException exception) {
            throw new ReportValidationException("Could not store generated report", exception);
        }
    }

    @Override
    public byte[] get(String uri) {
        Path path = resolve(uri);
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new ReportValidationException("Could not read generated report", exception);
        }
    }

    @Override
    public boolean exists(String uri, String contentHash) {
        Path path = resolve(uri);
        try {
            return Files.isRegularFile(path)
                && OoxmlDocxSupport.sha256(Files.readAllBytes(path)).equals(contentHash);
        } catch (IOException exception) {
            return false;
        }
    }

    private Path resolve(String uri) {
        if (uri == null || !uri.startsWith(PREFIX)) {
            throw new ReportValidationException("Unsupported report URI");
        }
        String filename = uri.substring(PREFIX.length());
        if (!filename.matches("[0-9a-fA-F-]{36}\\.docx")) {
            throw new ReportValidationException("Invalid report URI");
        }
        Path path = reportRoot.resolve(filename).normalize();
        requireInside(path);
        return path;
    }

    private void requireInside(Path path) {
        if (!path.startsWith(reportRoot)) {
            throw new ReportValidationException("Report path escaped the storage root");
        }
    }
}
