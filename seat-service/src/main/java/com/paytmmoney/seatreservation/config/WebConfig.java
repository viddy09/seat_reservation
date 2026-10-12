package com.paytmmoney.seatreservation.config;

import com.paytmmoney.seatreservation.api.LogContextInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final LogContextInterceptor logContext;

    public WebConfig(LogContextInterceptor logContext) {
        this.logContext = logContext;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(logContext);
    }
}
