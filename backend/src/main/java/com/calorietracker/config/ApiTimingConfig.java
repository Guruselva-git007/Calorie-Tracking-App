package com.calorietracker.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiTimingConfig implements WebMvcConfigurer {

    private final ApiTimingInterceptor apiTimingInterceptor;

    public ApiTimingConfig(ApiTimingInterceptor apiTimingInterceptor) {
        this.apiTimingInterceptor = apiTimingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiTimingInterceptor).addPathPatterns("/api/**");
    }
}
