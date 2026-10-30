package com.datum.clientmanagement.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Single source of "now", injected so time-dependent code can be tested with a fixed clock. */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
