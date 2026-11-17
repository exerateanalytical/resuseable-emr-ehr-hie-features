package com.healthcore.audit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditWriter {

    private final AuditLogRepository auditLogRepository;

    public AuditWriter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLogEntity entry) {
        auditLogRepository.save(entry);
    }
}
