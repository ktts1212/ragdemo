package com.example.ragdemo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RagDemoApplication {

    @Bean
    public CommandLineRunner debugConfig(@Value("${rag.embedding.base-url}") String baseUrl) {
        return args -> {
            System.out.println("========== 配置调试 ==========");
            System.out.println("rag.embedding.base-url: " + baseUrl);
            System.out.println("================================");
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(RagDemoApplication.class, args);
    }
}


