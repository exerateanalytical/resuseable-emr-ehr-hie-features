/*
 * =============================================================================
 * Opes HealthCore SDK — Cold-Chain IoT MQTT Broker Telemetry Subscriber
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.ops.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opeshealth.healthcore.ops.domain.HospitalOpsEntities.ColdChainTelemetryEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import com.opeshealth.healthcore.ops.autoconfigure.HospitalOpsAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Optional Eclipse Paho MQTT v3 subscriber bridging WHO PQS vaccine/blood refrigerator
 * temperature sensors directly into {@link HospitalOpsService#ingestColdChainReading}.
 *
 * <p>Activated automatically when {@code org.eclipse.paho.client.mqttv3} is on the
 * classpath, the biomedical cold-chain slice is active, and
 * {@code healthcore.coldchain.mqtt.enabled=true}.</p>
 */
@Configuration
@ConditionalOnClass(name = "org.eclipse.paho.client.mqttv3.MqttClient")
@Conditional(HospitalOpsAutoConfiguration.OnBiomedSlice.class)
@ConditionalOnProperty(prefix = "healthcore.coldchain.mqtt", name = "enabled", havingValue = "true")
public class BiomedicalColdChainMqttClient {

    private static final Logger log = LoggerFactory.getLogger(BiomedicalColdChainMqttClient.class);

    private final HospitalOpsService opsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${healthcore.coldchain.mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${healthcore.coldchain.mqtt.topic:healthcore/+/coldchain/#}")
    private String topicFilter;

    @Value("${healthcore.coldchain.mqtt.client-id:healthcore-coldchain-subscriber}")
    private String clientId;

    private MqttClient client;

    public BiomedicalColdChainMqttClient(HospitalOpsService opsService) {
        this.opsService = opsService;
    }

    @PostConstruct
    public void start() {
        try {
            client = new MqttClient(brokerUrl, clientId);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            client.setCallback(new MqttCallback() {
                @Override public void connectionLost(Throwable cause) {
                    log.warn("Cold-chain MQTT connection lost: {}", cause != null ? cause.getMessage() : "unknown");
                }
                @Override public void messageArrived(String topic, MqttMessage message) {
                    handleTelemetryPayload(new String(message.getPayload()));
                }
                @Override public void deliveryComplete(IMqttDeliveryToken token) {}
            });
            client.connect(opts);
            client.subscribe(topicFilter, 1);
            log.info("Cold-chain MQTT client subscribed to {} at {}", topicFilter, brokerUrl);
        } catch (Exception ex) {
            log.error("Failed to start cold-chain MQTT subscriber at {}: {}", brokerUrl, ex.getMessage());
        }
    }

    /** Parses JSON telemetry payload and routes through WHO 2C-8C excursion checks. */
    public void handleTelemetryPayload(String jsonPayload) {
        try {
            JsonNode root = objectMapper.readTree(jsonPayload);
            ColdChainTelemetryEntity reading = new ColdChainTelemetryEntity();
            reading.setFacilityId(UUID.fromString(root.path("facilityId").asText()));
            reading.setEquipmentTag(root.path("equipmentTag").asText("FRIDGE-UNKNOWN"));
            reading.setTempCelsius(new BigDecimal(root.path("tempCelsius").asText("4.0")));
            opsService.ingestColdChainReading(reading);
        } catch (Exception ex) {
            log.warn("Ignored malformed cold-chain MQTT telemetry frame: {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (client != null && client.isConnected()) {
            try {
                client.disconnect();
                client.close();
            } catch (Exception ignored) {
            }
        }
    }
}
