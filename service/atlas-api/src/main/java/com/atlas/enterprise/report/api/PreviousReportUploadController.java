package com.atlas.enterprise.report.api;

import com.atlas.enterprise.report.application.PreviousReportUploadException;
import com.atlas.enterprise.report.PreviousReport;
import com.atlas.enterprise.report.port.PreviousReportParser;
import com.atlas.enterprise.report.port.PreviousReportUploadStore;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files/previous-reports")
public class PreviousReportUploadController {
    private static final long MAX_BYTES = 20L * 1024L * 1024L;

    private final PreviousReportUploadStore uploads;
    private final PreviousReportParser parser;

    public PreviousReportUploadController(
        PreviousReportUploadStore uploads,
        PreviousReportParser parser
    ) {
        this.uploads = uploads;
        this.parser = parser;
    }

    @PostMapping(consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    public PreviousReportUploadResponse upload(
        @RequestParam("file") MultipartFile file
    ) {
        if (file.isEmpty()) {
            throw new PreviousReportUploadException(
                "Previous report file must not be empty"
            );
        }
        if (file.getSize() > MAX_BYTES) {
            throw new PreviousReportUploadException(
                "Previous report exceeds the 20 MB limit"
            );
        }
        try {
            byte[] content = file.getBytes();
            PreviousReport parsed;
            try {
                parsed = parser.parse(content);
            } catch (RuntimeException exception) {
                throw new PreviousReportUploadException(
                    "Uploaded DOCX could not be parsed",
                    exception
                );
            }
            return PreviousReportUploadResponse.from(uploads.store(
                file.getOriginalFilename(),
                content
            ), parsed);
        } catch (IOException exception) {
            throw new PreviousReportUploadException(
                "Could not read uploaded previous report",
                exception
            );
        }
    }
}
