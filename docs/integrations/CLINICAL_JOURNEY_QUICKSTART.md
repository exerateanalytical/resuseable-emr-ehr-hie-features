# OPES HealthCore — Clinical Journey & Departmental Queue Developer Quickstart

OPES HealthCore is a **reusable Java 25 / Spring Boot 4.0 SDK**, not a monolithic hospital application. Developers assemble only the modules their product requires.

## 1. Standalone Diagnostic Center Assembly (Zero EMR/Pharmacy/Inpatient Modules)
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.opeshealth.healthcore</groupId>
            <artifactId>healthcore-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <!-- Industry Pack for ISO 15189 Diagnostics + Standalone Queue & Workflow Engine -->
    <dependency>
        <groupId>com.opeshealth.healthcore</groupId>
        <artifactId>healthcore-pack-diagnostics</artifactId>
    </dependency>
    <dependency>
        <groupId>com.opeshealth.healthcore</groupId>
        <artifactId>healthcore-starter-queue-management</artifactId>
    </dependency>
    <dependency>
        <groupId>com.opeshealth.healthcore</groupId>
        <artifactId>healthcore-starter-workflow-engine</artifactId>
    </dependency>
</dependencies>
```

## 2. Standalone Retail / Community Pharmacy Assembly (Zero EMR/LIS/Radiology Modules)
```xml
<dependencies>
    <dependency>
        <groupId>com.opeshealth.healthcore</groupId>
        <artifactId>healthcore-pack-pharmacy</artifactId>
    </dependency>
    <dependency>
        <groupId>com.opeshealth.healthcore</groupId>
        <artifactId>healthcore-starter-queue-management</artifactId>
    </dependency>
</dependencies>
```

## 3. Customizing SDK Extension Points (Zero Forking)

### A. Plug In Your Real SMS / WhatsApp Carrier Adapter
```java
@Component
public class AfricasTalkingSmsAdapter implements PatientJourneyOrchestratorService.NotificationProviderAdapterSpi {
    @Override
    public boolean supports(PatientJourneyEntities.NotificationChannel channel) {
        return channel == PatientJourneyEntities.NotificationChannel.SMS;
    }

    @Override
    public PatientJourneyOrchestratorService.ProviderSubmissionResult submit(
            PatientJourneyEntities.DurableNotificationEntity notification) {
        // Submit notification.getRecipientAddress() + notification.getRenderedMessage() to live carrier API
        String carrierMessageId = callCarrierGateway(notification);
        return new PatientJourneyOrchestratorService.ProviderSubmissionResult(true, carrierMessageId, null);
    }
}
```
When your carrier webhook receives a delivery receipt, invoke:
```java
patientJourneyOrchestratorService.confirmNotificationDeliveryReceipt(facilityId, notificationId, carrierReceiptId);
```

### B. Customize Departmental Queue Prioritization
```java
@Bean
public QueueManagementService.QueuePrioritizationSpi maternalAndPediatricPrioritySpi() {
    return (priority, departmentCode, reentryCount, emergencyOverride) -> {
        int base = QueueManagementService.defaultUrgencyScore(priority, departmentCode, reentryCount, emergencyOverride);
        return "MATERNITY_TRIAGE".equals(departmentCode) ? base + 15 : base;
    };
}
```

### C. Customize Facility Billing & UHC Coverage Clearance
```java
@Bean
public ClinicalJourneyContracts.WorkflowBillingClearancePolicySpi nationalUhcClearancePolicy(
        UhcEligibilityClient uhcClient) {
    ClinicalJourneyContracts.DefaultFacilityBillingClearancePolicy defaults =
            new ClinicalJourneyContracts.DefaultFacilityBillingClearancePolicy();
    return (req, cfg) -> {
        if (req.emergencyUrgent() || req.criticalFindingCommunication()) {
            return defaults.evaluate(req, cfg); // Always preserves mandatory EMERGENCY_OVERRIDE
        }
        boolean activeUhc = uhcClient.isMemberCovered(req.facilityId(), req.patientId());
        if (activeUhc) {
            return new ClinicalJourneyContracts.BillingClearanceDecision(
                    ClinicalJourneyContracts.PaymentClearanceOutcome.INSURANCE_AUTHORIZED,
                    true, false, true, "National UHC coverage verified");
        }
        return defaults.evaluate(req, cfg);
    };
}
```

## 4. REST / OpenAPI Queue Gateway Examples
```http
### 1. Enqueue patient into Laboratory Collection (concurrency-safe daily ticket)
POST /api/v1/queues/entries
Content-Type: application/json
X-Facility-Id: 0195a000-0000-7000-8000-000000000001

{
  "departmentCode": "LAB_COLLECTION",
  "patientId": "0195a000-0000-7000-8000-000000000101",
  "encounterId": "0195a000-0000-7000-8000-000000000201",
  "orderId": "0195a000-0000-7000-8000-000000000301",
  "priority": "ROUTINE",
  "financialClearanceState": "PAID",
  "slaMinutes": 45,
  "idempotencyKey": "LAB-ORD-0195a000-0000-7000-8000-000000000301"
}

### 2. Fetch priority-ordered laboratory worklist
GET /api/v1/queues/departments/LAB_COLLECTION/worklist
X-Facility-Id: 0195a000-0000-7000-8000-000000000001

### 3. Atomically claim ticket for a phlebotomist
POST /api/v1/queues/entries/{queueEntryId}/claim?workerId=phleb-07
X-Facility-Id: 0195a000-0000-7000-8000-000000000001

### 4. Patient-facing waiting-room status (privacy-minimized, zero PHI/diagnosis)
GET /api/v1/queues/entries/{queueEntryId}/patient-status
X-Facility-Id: 0195a000-0000-7000-8000-000000000001
```
