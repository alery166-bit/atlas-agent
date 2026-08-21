package com.atlas.enterprise.operations.api;

import com.atlas.enterprise.operations.application.PlatformObservabilityService;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.AuditEntry;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.AuditFilter;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.ConfigurationChange;
import com.atlas.enterprise.operations.port.PlatformObservabilityPort.OperationsSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform")
public class PlatformObservabilityController {
    private final PlatformObservabilityService observability;

    public PlatformObservabilityController(PlatformObservabilityService observability) {
        this.observability = observability;
    }

    @GetMapping("/operations")
    public OperationsSnapshot operations(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(name = "failure_limit", defaultValue = "20") int failureLimit
    ) {
        return observability.observe(from, to, failureLimit);
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(
        @RequestParam(name = "task_id", required = false) UUID taskId,
        @RequestParam(required = false) String enterprise,
        @RequestParam(name = "operator_id", required = false) String operatorId,
        @RequestParam(name = "event_type", required = false) String eventType,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "100") int limit
    ) {
        return observability.audit(new AuditFilter(
            taskId, enterprise, operatorId, eventType, from, to, limit
        ));
    }

    @GetMapping(value = "/audit/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(
        @RequestParam(name = "task_id", required = false) UUID taskId,
        @RequestParam(required = false) String enterprise,
        @RequestParam(name = "operator_id", required = false) String operatorId,
        @RequestParam(name = "event_type", required = false) String eventType,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "500") int limit
    ) {
        List<AuditEntry> entries = observability.audit(new AuditFilter(
            taskId, enterprise, operatorId, eventType, from, to, limit
        ));
        StringBuilder csv = new StringBuilder("\uFEFF时间,事件类型,动作,任务编号,企业,操作人,目标类型,目标标识,变更前,变更后,说明,追踪号\r\n");
        entries.forEach(entry -> csv.append(row(
            entry.occurredAt(), entry.eventType(), entry.action(), entry.taskNo(),
            entry.enterpriseName(), entry.operatorId(), entry.targetType(), entry.targetId(),
            entry.beforeJson(), entry.afterJson(), entry.detail(), entry.traceId()
        )));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
            .filename("atlas-audit.csv", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers)
            .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/configuration-changes")
    public ConfigurationChange configurationChange(@RequestParam("release_id") UUID releaseId) {
        return observability.configurationChange(releaseId);
    }

    private static String row(Object... values) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) line.append(',');
            String value = values[index] == null ? "" : values[index].toString();
            line.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return line.append("\r\n").toString();
    }
}
