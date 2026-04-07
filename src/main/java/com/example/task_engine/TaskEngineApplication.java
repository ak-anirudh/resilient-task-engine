package com.example.task_engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class TaskEngineApplication {

	static void main(String[] args) {
		SpringApplication.run(TaskEngineApplication.class, args);
	}

}
