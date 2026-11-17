package com.healthcore.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class FacilityInterceptor implements HandlerInterceptor {

    public static final String FACILITY_HEADER = "X-Facility-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(FACILITY_HEADER);
        if (header != null && !header.isBlank()) {
            FacilityContext.setFacilityId(UUID.fromString(header.trim()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        FacilityContext.clear();
    }
}
