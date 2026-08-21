package com.atlas.enterprise.report.docx;

import com.atlas.enterprise.report.application.PreviousReportUploadException;
import com.atlas.enterprise.report.port.PreviousReportUploadStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalPreviousReportUploadStore
    implements PreviousReportUploadStore {
    private static final int MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L;
    private static final int MAX_ZIP_ENTRIES = 2_048;
    private final Path previousRoot;

    public LocalPreviousReportUploadStore(
        @Value("${atlas.report.previous-root}") String previousRoot
    ) {
        this.previousRoot = Path.of(previousRoot)
            .toAbsolutePath()
            .normalize();
    }

    @Override
    public StoredPreviousReport store(
        String originalFilename,
        byte[] content
    ) {
        validate(originalFilename, content);
        String safeName = UUID.randomUUID() + ".docx";
        Path uploads = previousRoot.resolve("uploads").normalize();
        Path target = uploads.resolve(safeName).normalize();
        if (!target.startsWith(uploads)) {
            throw new PreviousReportUploadException(
                "Invalid previous report upload path"
            );
        }
        try {
            Files.createDirectories(uploads);
            Files.write(
                target,
                content,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new PreviousReportUploadException(
                "Could not store previous report",
                exception
            );
        }
        return new StoredPreviousReport(
            "uploads/" + safeName,
            basename(originalFilename),
            content.length,
            OoxmlDocxSupport.sha256(content)
        );
    }

    private static void validate(String filename, byte[] content) {
        if (filename == null
            || !filename.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new PreviousReportUploadException(
                "Only DOCX previous reports can be uploaded"
            );
        }
        if (content == null || content.length < 4) {
            throw new PreviousReportUploadException(
                "Uploaded DOCX must not be empty"
            );
        }
        if (content[0] != 'P' || content[1] != 'K') {
            throw new PreviousReportUploadException(
                "Uploaded file is not a valid OOXML document"
            );
        }
        if (content.length > MAX_UPLOAD_BYTES) {
            throw new PreviousReportUploadException(
                "Uploaded DOCX exceeds the 20 MB limit"
            );
        }
        validateOoxmlPackage(content);
    }

    private static String basename(String filename) {
        try {
            return Path.of(filename).getFileName().toString();
        } catch (RuntimeException exception) {
            throw new PreviousReportUploadException(
                "Uploaded DOCX filename is invalid",
                exception
            );
        }
    }

    private static void validateOoxmlPackage(byte[] content) {
        boolean hasContentTypes = false;
        boolean hasDocument = false;
        int entries = 0;
        long uncompressedBytes = 0;
        byte[] buffer = new byte[8_192];
        try (
            ZipInputStream input = new ZipInputStream(
                new ByteArrayInputStream(content)
            )
        ) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ZIP_ENTRIES) {
                    throw invalidPackage(
                        "Uploaded DOCX contains too many ZIP entries"
                    );
                }
                String entryName = entry.getName().replace('\\', '/');
                if (
                    entryName.startsWith("/")
                        || entryName.contains("../")
                        || entryName.equals("..")
                ) {
                    throw invalidPackage(
                        "Uploaded DOCX contains an unsafe ZIP entry"
                    );
                }
                hasContentTypes |= "[Content_Types].xml".equals(entryName);
                hasDocument |= "word/document.xml".equals(entryName);
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    uncompressedBytes += read;
                    if (uncompressedBytes > MAX_UNCOMPRESSED_BYTES) {
                        throw invalidPackage(
                            "Uploaded DOCX expands beyond the 50 MB limit"
                        );
                    }
                }
                input.closeEntry();
            }
        } catch (PreviousReportUploadException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new PreviousReportUploadException(
                "Uploaded file is not a readable OOXML package",
                exception
            );
        }
        if (!hasContentTypes || !hasDocument) {
            throw invalidPackage(
                "Uploaded file is missing required DOCX parts"
            );
        }
    }

    private static PreviousReportUploadException invalidPackage(
        String message
    ) {
        return new PreviousReportUploadException(message);
    }
}
