package com.healthcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HealthCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(HealthCoreApplication.class, args);
    }
}
