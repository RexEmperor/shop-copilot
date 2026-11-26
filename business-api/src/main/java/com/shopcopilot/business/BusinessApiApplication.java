package com.shopcopilot.business;

import com.shopcopilot.business.config.CacheProperties;
import com.shopcopilot.business.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({CacheProperties.class, RateLimitProperties.class})
public class BusinessApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(BusinessApiApplication.class, args);
	}

}
