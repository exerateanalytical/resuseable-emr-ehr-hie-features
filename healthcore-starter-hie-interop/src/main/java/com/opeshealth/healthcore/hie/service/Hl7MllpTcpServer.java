/*
 * =============================================================================
 * Opes HealthCore SDK — HL7 v2.x MLLP TCP Server (Java 25 Virtual Threads)
 * Author / Architect : Jude Nshome — Opes Health Systems
 * Email              : nshome@opesware.com
 * Website            : https://www.opeshealthsystems.com
 * Location           : Douala, Cameroon
 * =============================================================================
 */
package com.opeshealth.healthcore.hie.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight, high-throughput Minimal Lower Layer Protocol (MLLP) TCP server
 * powered by Java 25 Virtual Threads ({@code Executors.newVirtualThreadPerTaskExecutor()})
 * for real-time integration with PACS, LIS analyzers, ADT registration desks, and theater monitors.
 *
 * <p>Frames inbound HL7 v2 messages delimited by MLLP Start Block ({@code 0x0B})
 * and End Block ({@code 0x1C 0x0D}), passes the payload through {@link HieInteropService#ingestHl7v2(String)},
 * and returns a valid MLLP-framed {@code ACK^A01} ({@code MSA|AA} or {@code MSA|AE}).</p>
 */
public class Hl7MllpTcpServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Hl7MllpTcpServer.class);
    private static final int MLLP_START_BLOCK = 0x0B;
    private static final int MLLP_END_BLOCK = 0x1C;
    private static final int MLLP_CARRIAGE_RETURN = 0x0D;

    private final int port;
    private final HieInteropService hieService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService executor;

    public Hl7MllpTcpServer(int port, HieInteropService hieService) {
        this.port = port;
        this.hieService = hieService;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(() -> {
            try {
                serverSocket = new ServerSocket(port);
                log.info("Opes HealthCore HL7 MLLP Virtual-Thread Server listening on TCP port {}", port);
                while (running.get() && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleConnection(client));
                }
            } catch (IOException ex) {
                if (running.get()) {
                    log.error("HL7 MLLP listener failure on port {}", port, ex);
                }
            }
        });
    }

    private void handleConnection(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            boolean inFrame = false;
            int prev = -1;
            int b;
            while ((b = in.read()) != -1) {
                if (b == MLLP_START_BLOCK) {
                    buffer.reset();
                    inFrame = true;
                    prev = b;
                    continue;
                }
                if (inFrame) {
                    if (prev == MLLP_END_BLOCK && b == MLLP_CARRIAGE_RETURN) {
                        byte[] rawBytes = buffer.toByteArray();
                        // Remove trailing 0x1C already buffered before 0x0D
                        int len = Math.max(0, rawBytes.length - 1);
                        String hl7Payload = new String(rawBytes, 0, len, StandardCharsets.UTF_8);
                        Map<String, Object> result = hieService.ingestHl7v2(hl7Payload);
                        String ackCode = Objects.toString(result.get("ackCode"), "AA");
                        String controlId = Objects.toString(result.get("controlId"), "UNKNOWN");
                        String ackMessage = "MSH|^~\\&|HEALTHCORE|OPES|SENDER|EXT|"
                                + java.time.Instant.now() + "||ACK|" + controlId + "|P|2.5.1\r"
                                + "MSA|" + ackCode + "|" + controlId + "\r";
                        out.write(MLLP_START_BLOCK);
                        out.write(ackMessage.getBytes(StandardCharsets.UTF_8));
                        out.write(MLLP_END_BLOCK);
                        out.write(MLLP_CARRIAGE_RETURN);
                        out.flush();
                        inFrame = false;
                    } else {
                        buffer.write(b);
                    }
                }
                prev = b;
            }
        } catch (IOException ex) {
            log.debug("MLLP client socket closed: {}", ex.getMessage());
        }
    }

    @Override
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
