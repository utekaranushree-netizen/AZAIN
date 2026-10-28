package com.stationaryshop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS is needed only when the frontend is opened from a DIFFERENT address than the API
 * (for example VS Code Live Server on http://localhost:5500). When the frontend is served by this
 * Spring Boot application (the default, http://localhost:8080) the browser never needs CORS.
 * Allowed origins come from app.cors.allowed-origins (environment variable CORS_ALLOWED_ORIGINS).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type");
    }
}
