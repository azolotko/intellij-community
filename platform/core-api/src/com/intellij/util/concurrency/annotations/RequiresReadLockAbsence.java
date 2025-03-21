// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.concurrency.annotations;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ReadAction;
import com.intellij.util.ThrowableRunnable;
import org.jetbrains.annotations.ApiStatus;

import java.lang.annotation.*;

/**
 * Methods and constructors annotated with {@code RequiresReadLockAbsence} must be called without a read lock <i>nor</i>
 * a write lock held.
 * Parameters annotated with {@code RequiresReadLockAbsence} must be callables and are guaranteed to be called without
 * a read lock <i>nor</i> a write lock held.
 * (Write access <i>implies</i> read access, hence requires-read-lock-absence <i>implies</i> requires-write-lock-absence)
 * <p/>
 * Aside from a documentation purpose, the annotation is processed by the {@link org.jetbrains.jps.devkit.threadingModelHelper}.
 * The plugin instruments annotated elements with {@link Application#assertReadAccessNotAllowed()} calls
 * to ensure annotation's contract is not violated at runtime. The instrumentation can be disabled
 * by setting {@link RequiresReadLockAbsence#generateAssertion()} to {@code false}.
 * <p/>
 * <b>Important:</b> the instrumentation has limitations. Please read the docs
 * of the {@link org.jetbrains.jps.devkit.threadingModelHelper} to learn about them.
 *
 * @see <a href="https://plugins.jetbrains.com/docs/intellij/threading-model.html">Threading Model</a>
 * @see Application#assertReadAccessNotAllowed()
 * @see ReadAction#run(ThrowableRunnable)
 */
@ApiStatus.Experimental
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER})
public @interface RequiresReadLockAbsence {
  /**
   * @return {@code false} if annotated element must not be instrumented with the assertion.
   */
  boolean generateAssertion() default true;
}
