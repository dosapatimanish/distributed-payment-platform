package com.paymentplatform.orchestrator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.client.RestClient;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ConversionOrchestratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConversionOrchestratorApplication.class, args);
	}

	/** Shared builder for the downstream-service HTTP clients (see the {@code client} package). */
	@Bean
	public RestClient.Builder restClientBuilder() {
		return RestClient.builder();
	}
}
