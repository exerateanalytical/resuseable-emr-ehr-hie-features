package com.healthcore.audit;

import com.healthcore.tenant.FacilityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Aspect
@Component
public class ClinicalAuditAspect {

    private final AuditWriter auditWriter;

    public ClinicalAuditAspect(AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    @Around("@annotation(clinicalAudit)")
    public Object auditCall(ProceedingJoinPoint pjp, ClinicalAudit clinicalAudit) throws Throwable {
        String outcome = "SUCCESS";
        String failureReason = null;
        try {
            return pjp.proceed();
        } catch (Throwable ex) {
            outcome = "DENIED";
            failureReason = ex.getMessage();
            throw ex;
        } finally {
            AuditLogEntity entry = buildAuditEntry(clinicalAudit, extractFirstUuid(pjp.getArgs()), outcome, failureReason);
            auditWriter.write(entry);
        }
    }

    private AuditLogEntity buildAuditEntry(ClinicalAudit meta, UUID patientOrResourceId, String outcome, String reason) {
        AuditLogEntity entry = new AuditLogEntity();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        entry.setActorId(auth != null ? auth.getName() : "anonymous");
        if (auth != null) {
            entry.setActorRole(auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .findFirst()
                    .orElse("USER"));
        }
        entry.setFacilityId(FacilityContext.getFacilityId().orElse(null));
        entry.setAction(meta.action());
        entry.setResourceType(meta.resourceType());
        entry.setPatientId(patientOrResourceId);
        entry.setResourceId(patientOrResourceId != null ? patientOrResourceId.toString() : null);
        entry.setOutcome(outcome);
        entry.setReason(reason);
        entry.setSourceIp(resolveClientIp());
        return entry;
    }

    private UUID extractFirstUuid(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof UUID uuid) {
                return uuid;
            }
        }
        return null;
    }

    private String resolveClientIp() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest req = servletAttrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            return (forwarded != null && !forwarded.isBlank())
                    ? forwarded.split(",")[0].trim()
                    : req.getRemoteAddr();
        }
        return null;
    }
}
