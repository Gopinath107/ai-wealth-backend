package com.djai.wealthadvisor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DjaiApplication {
    public static void main(String[] args) {
        SpringApplication.run(DjaiApplication.class, args);
    }
}
