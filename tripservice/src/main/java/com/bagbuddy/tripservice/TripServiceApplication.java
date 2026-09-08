package com.bagbuddy.tripservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class TripServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(TripServiceApplication.class, args);
	}
}
