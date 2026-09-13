package com.masspos.common.persistence;

import org.hibernate.annotations.IdGeneratorType;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Primary key generated as a {@link UuidV7} when the entity is persisted. An id that is already set
 * (a row replicated from another terminal or the cloud) is kept as is.
 */
@IdGeneratorType(UuidV7IdGenerator.class)
@Retention(RUNTIME)
@Target({FIELD, METHOD})
public @interface GeneratedUuidV7 {
}
