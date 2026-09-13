package com.masspos.auth;

import com.masspos.user.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/** Enforces {@link RequiresRole} on API handlers. */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequiresRole required = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequiresRole.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresRole.class);
        }
        if (required == null) {
            return true;
        }
        Principal principal = AuthContext.require();
        if (principal.role() != UserRole.ADMIN && !Arrays.asList(required.value()).contains(principal.role())) {
            throw new AccessDeniedException("%s cannot do this; needs one of %s"
                    .formatted(principal.role(), Arrays.toString(required.value())));
        }
        return true;
    }
}
