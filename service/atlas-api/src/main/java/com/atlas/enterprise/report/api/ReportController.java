package com.atlas.enterprise.report.api;

import com.atlas.enterprise.report.ReportDiff;
import com.atlas.enterprise.report.application.ReportApplicationService;
import com.atlas.enterprise.report.application.ReportDownload;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks/{taskId}/reports")
public class ReportController {
    private static final MediaType DOCX = MediaType.parseMediaType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final ReportApplicationService reports;

    public ReportController(ReportApplicationService reports) {
        this.reports = reports;
    }

    @PostMapping
    public ResponseEntity<ReportVersionResponse> generate(
        @PathVariable UUID taskId,
        @RequestHeader(value = "X-Operator-Id", defaultValue = "local-operator")
        String operatorId
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ReportVersionResponse.from(reports.generate(taskId, operatorId)));
    }

    @GetMapping
    public List<ReportVersionResponse> list(@PathVariable UUID taskId) {
        return reports.list(taskId).stream().map(ReportVersionResponse::from).toList();
    }

    @GetMapping("/{reportId}/diff")
    public ReportDiff diff(
        @PathVariable UUID taskId,
        @PathVariable UUID reportId
    ) {
        return reports.diff(taskId, reportId);
    }

    @GetMapping("/{reportId}/download")
    public ResponseEntity<byte[]> download(
        @PathVariable UUID taskId,
        @PathVariable UUID reportId
    ) {
        ReportDownload download = reports.download(taskId, reportId);
        return downloadResponse(download);
    }

    @GetMapping("/latest/download")
    public ResponseEntity<byte[]> downloadLatest(@PathVariable UUID taskId) {
        return downloadResponse(reports.downloadLatest(taskId));
    }

    private static ResponseEntity<byte[]> downloadResponse(ReportDownload download) {
        ContentDisposition disposition = ContentDisposition.attachment()
            .filename(download.filename(), StandardCharsets.UTF_8)
            .build();
        return ResponseEntity.ok()
            .contentType(DOCX)
            .contentLength(download.content().length)
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-SHA256", download.report().contentHash())
            .body(download.content());
    }
}
