package com.healthcore.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebTenantConfig implements WebMvcConfigurer {

    private final FacilityInterceptor facilityInterceptor;

    public WebTenantConfig(FacilityInterceptor facilityInterceptor) {
        this.facilityInterceptor = facilityInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(facilityInterceptor).addPathPatterns("/api/**");
    }
}
