package com.atlas.enterprise.report.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.enterprise.report.application.PreviousReportUploadException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalPreviousReportUploadStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesPackageUnderGeneratedNameDespiteUntrustedFilename()
        throws IOException {
        LocalPreviousReportUploadStore store =
            new LocalPreviousReportUploadStore(
                temporaryDirectory.toString()
            );

        var result = store.store(
            "../../不可信目录/旧报告.docx",
            minimalDocx()
        );

        assertTrue(result.fileId().matches(
            "uploads/[0-9a-f-]{36}\\.docx"
        ));
        assertEquals("旧报告.docx", result.originalFilename());
        Path stored = temporaryDirectory.resolve(result.fileId()).normalize();
        assertTrue(stored.startsWith(temporaryDirectory));
        assertTrue(Files.isRegularFile(stored));
    }

    @Test
    void rejectsZipThatIsNotAnOoxmlDocument() throws IOException {
        LocalPreviousReportUploadStore store =
            new LocalPreviousReportUploadStore(
                temporaryDirectory.toString()
            );

        PreviousReportUploadException error = assertThrows(
            PreviousReportUploadException.class,
            () -> store.store("伪造报告.docx", zip(
                "arbitrary.txt",
                "not a document"
            ))
        );

        assertEquals(
            "Uploaded file is missing required DOCX parts",
            error.getMessage()
        );
    }

    @Test
    void rejectsUnsafeZipEntry() throws IOException {
        LocalPreviousReportUploadStore store =
            new LocalPreviousReportUploadStore(
                temporaryDirectory.toString()
            );

        assertThrows(
            PreviousReportUploadException.class,
            () -> store.store("恶意报告.docx", zip(
                "../word/document.xml",
                "<document/>"
            ))
        );
    }

    private static byte[] minimalDocx() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            write(
                output,
                "[Content_Types].xml",
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"
            );
            write(
                output,
                "word/document.xml",
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>"
            );
        }
        return bytes.toByteArray();
    }

    private static byte[] zip(String name, String content)
        throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            write(output, name, content);
        }
        return bytes.toByteArray();
    }

    private static void write(
        ZipOutputStream output,
        String name,
        String content
    ) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
