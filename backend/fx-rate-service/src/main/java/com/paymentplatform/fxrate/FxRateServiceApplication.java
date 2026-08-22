package com.paymentplatform.fxrate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // needed for RateRefreshScheduler's @Scheduled tick
public class FxRateServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(FxRateServiceApplication.class, args);
	}

}
