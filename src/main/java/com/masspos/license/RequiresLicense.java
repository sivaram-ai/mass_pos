package com.masspos.license;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Endpoints that issue a tax document (a bill, an edited bill, a credit note) and so need a licence
 * that allows billing. Everything else, including reports, reprints, cancelling and setting the shop
 * up, keeps working on an unlicensed or lapsed till, so its records are never held hostage.
 */
@Documented
@Target(METHOD)
@Retention(RUNTIME)
public @interface RequiresLicense {
}
