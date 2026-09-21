package com.masspos.web;

import com.masspos.auth.RoleInterceptor;
import com.masspos.license.LicenseInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfig implements WebMvcConfigurer {

    private final RoleInterceptor roles;
    private final LicenseInterceptor licenses;

    public WebConfig(RoleInterceptor roles, LicenseInterceptor licenses) {
        this.roles = roles;
        this.licenses = licenses;
    }

    /** Roles first: a cashier trying a manager's job is told about the role, not the licence. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roles).addPathPatterns("/api/**");
        registry.addInterceptor(licenses).addPathPatterns("/api/**");
    }
}
