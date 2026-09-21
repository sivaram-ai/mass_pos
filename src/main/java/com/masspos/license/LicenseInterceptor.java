package com.masspos.license;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link RequiresLicense}. Registered after the role check, so a role refusal is reported first. */
@Component
public class LicenseInterceptor implements HandlerInterceptor {

    private final LicenseService licenses;

    public LicenseInterceptor(LicenseService licenses) {
        this.licenses = licenses;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod method
                && AnnotatedElementUtils.hasAnnotation(method.getMethod(), RequiresLicense.class)) {
            licenses.requireBilling();
        }
        return true;
    }
}
