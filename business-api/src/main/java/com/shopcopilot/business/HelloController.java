package com.shopcopilot.business;

import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HelloController {

    record HelloResponse(String service, String message, LocalDateTime timestamp) {}

    @GetMapping("/hello")
    public HelloResponse hello(@RequestParam(defaultValue = "world") String name) {
        return new HelloResponse("business-api", "hello, " + name, LocalDateTime.now());
    }
}