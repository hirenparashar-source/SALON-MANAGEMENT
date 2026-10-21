package com.sillage.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record component / field / accessor that must never leave the JVM as JSON.
 * Used for password hashes, salts, raw card numbers and other secrets: the writer drops
 * them silently, so a controller can return a whole entity without leaking credentials.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
public @interface JsonExclude {
}
