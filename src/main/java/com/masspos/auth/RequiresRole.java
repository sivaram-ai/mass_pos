package com.masspos.auth;

import com.masspos.user.UserRole;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Roles allowed to call this endpoint. ADMIN always passes. An endpoint without the annotation
 * still needs a signed-in user: only the paths listed in {@link AuthFilter} are public.
 */
@Documented
@Target({METHOD, TYPE})
@Retention(RUNTIME)
public @interface RequiresRole {

    UserRole[] value();
}
