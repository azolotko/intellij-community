// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lang;

import com.intellij.lang.refactoring.RefactoringSupportProvider;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class LanguageRefactoringSupport extends LanguageExtension<RefactoringSupportProvider> {
  /**
   * @deprecated Use {@link #getInstance} instead
   */
  @Deprecated
  public static final LanguageRefactoringSupport INSTANCE = new LanguageRefactoringSupport();

  public static @NotNull LanguageRefactoringSupport getInstance() {
    return INSTANCE;
  }

  private LanguageRefactoringSupport() {
    super("com.intellij.lang.refactoringSupport", new RefactoringSupportProvider() {});
  }

  public @Nullable RefactoringSupportProvider forContext(@NotNull PsiElement element) {
    List<RefactoringSupportProvider> providers = allForLanguage(element.getLanguage());
    for (RefactoringSupportProvider provider : providers) {
      if (provider.isAvailable(element)) {
        return provider;
      }
    }
    return null;
  }
}
