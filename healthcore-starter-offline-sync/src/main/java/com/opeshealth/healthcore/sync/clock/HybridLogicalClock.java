/*
 * =============================================================================
 * Opes HealthCore SDK — Hybrid Logical Clock (HLC) & Ed25519 QR Health Card
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.sync.clock;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides:
 * <ol>
 *   <li>A thread-safe {@link HybridLogicalClock} generating lexicographically sortable
 *       {@code "<13-digit-epoch-ms>:<4-digit-counter>:<nodeId>"} timestamps resilient against
 *       drifting Android/iPad/Clinic Edge clocks.</li>
 *   <li>An Ed25519 cryptographic signer/verifier for compact offline Patient IPS QR Health Cards.</li>
 * </ol>
 */
public final class HybridLogicalClock {

    public record HlcStamp(long physicalMs, int logicalCounter, String nodeId) {
        public String format() {
            return String.format("%013d:%04d:%s", physicalMs, logicalCounter, nodeId);
        }
    }

    private final String nodeId;
    private final AtomicReference<HlcStamp> state = new AtomicReference<>(new HlcStamp(System.currentTimeMillis(), 0, "init"));

    public HybridLogicalClock(String nodeId) {
        this.nodeId = nodeId;
        this.state.set(new HlcStamp(System.currentTimeMillis(), 0, nodeId));
    }

    /** Advances local HLC upon generating a new offline/server event. */
    public String nextTimestamp() {
        return state.updateAndGet(prev -> {
            long wall = System.currentTimeMillis();
            if (wall > prev.physicalMs()) {
                return new HlcStamp(wall, 0, nodeId);
            }
            return new HlcStamp(prev.physicalMs(), prev.logicalCounter() + 1, nodeId);
        }).format();
    }

    /** Merges a remote client's HLC timestamp upon receiving an offline sync batch. */
    public String receiveRemote(long remotePhysicalMs, int remoteCounter) {
        return state.updateAndGet(prev -> {
            long wall = System.currentTimeMillis();
            long maxPhysical = Math.max(wall, Math.max(prev.physicalMs(), remotePhysicalMs));
            int counter;
            if (maxPhysical == prev.physicalMs() && maxPhysical == remotePhysicalMs) {
                counter = Math.max(prev.logicalCounter(), remoteCounter) + 1;
            } else if (maxPhysical == prev.physicalMs()) {
                counter = prev.logicalCounter() + 1;
            } else if (maxPhysical == remotePhysicalMs) {
                counter = remoteCounter + 1;
            } else {
                counter = 0;
            }
            return new HlcStamp(maxPhysical, counter, nodeId);
        }).format();
    }

    /** Signs & verifies compact offline IPS QR Health Cards using Java 25 native Ed25519. */
    public static final class Ed25519QrHealthCardSigner {
        private final KeyPair keyPair;

        public Ed25519QrHealthCardSigner() {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
                this.keyPair = kpg.generateKeyPair();
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("Ed25519 unavailable in JDK", ex);
            }
        }

        public String signCardPayload(String compactJsonPayload) {
            try {
                Signature sig = Signature.getInstance("Ed25519");
                sig.initSign(keyPair.getPrivate());
                byte[] data = compactJsonPayload.getBytes(StandardCharsets.UTF_8);
                sig.update(data);
                String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(data);
                String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
                return "HC1:" + payloadB64 + "." + sigB64;
            } catch (Exception ex) {
                throw new IllegalStateException("QR Health Card signing failure", ex);
            }
        }

        public boolean verifyCardToken(String qrToken) {
            if (qrToken == null || !qrToken.startsWith("HC1:") || !qrToken.contains(".")) return false;
            try {
                String body = qrToken.substring(4);
                int dot = body.indexOf('.');
                byte[] data = Base64.getUrlDecoder().decode(body.substring(0, dot));
                byte[] sigBytes = Base64.getUrlDecoder().decode(body.substring(dot + 1));
                Signature sig = Signature.getInstance("Ed25519");
                sig.initVerify(keyPair.getPublic());
                sig.update(data);
                return sig.verify(sigBytes);
            } catch (Exception ex) {
                return false;
            }
        }

        public String publicKeyBase64() {
            return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        }
    }
}
