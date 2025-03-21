// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.introduce.introduceTypeAlias

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.containers.LinkedMultiMap
import com.intellij.util.containers.MultiMap
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.idea.base.fe10.codeInsight.newDeclaration.Fe10KotlinNameSuggester
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.psi.unifier.KotlinPsiRange
import org.jetbrains.kotlin.idea.base.psi.unifier.toRange
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.analyzeInContext
import org.jetbrains.kotlin.idea.caches.resolve.unsafeResolveToDescriptor
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.CollectingNameValidator
import org.jetbrains.kotlin.idea.core.compareDescriptors
import org.jetbrains.kotlin.idea.refactoring.introduce.insertDeclaration
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.idea.util.psi.patternMatching.KotlinPsiUnifier
import org.jetbrains.kotlin.idea.util.psi.patternMatching.UnifierParameter
import org.jetbrains.kotlin.idea.util.psi.patternMatching.match
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.scopes.utils.findClassifier
import org.jetbrains.kotlin.types.TypeUtils

sealed class IntroduceTypeAliasAnalysisResult {
    class Error(@NlsContexts.DialogMessage val message: String) : IntroduceTypeAliasAnalysisResult()
    class Success(val descriptor: IntroduceTypeAliasDescriptor) : IntroduceTypeAliasAnalysisResult()
}

private fun IntroduceTypeAliasData.getTargetScope() = targetSibling.getResolutionScope(bindingContext, resolutionFacade)

fun IntroduceTypeAliasData.analyze(): IntroduceTypeAliasAnalysisResult {
    val psiFactory = KtPsiFactory(originalTypeElement.project)

    val contextExpression = originalTypeElement.getStrictParentOfType<KtExpression>()!!
    val targetScope = getTargetScope()

    val dummyVar = psiFactory.createProperty("val a: Int").apply {
        typeReference!!.replace(
            originalTypeElement.parent as? KtTypeReference ?: if (originalTypeElement is KtTypeElement) psiFactory.createType(
                originalTypeElement
            ) else psiFactory.createType(originalTypeElement.text)
        )
    }
    val newTypeReference = dummyVar.typeReference!!
    val newReferences = newTypeReference.collectDescendantsOfType<KtTypeReference> { it.resolveInfo != null }
    val newContext = dummyVar.analyzeInContext(targetScope, contextExpression)
    val project = originalTypeElement.project

    val unifier = KotlinPsiUnifier.DEFAULT
    val groupedReferencesToExtract = LinkedMultiMap<TypeReferenceInfo, TypeReferenceInfo>()

    val forcedCandidates = if (extractTypeConstructor) newTypeReference.typeElement!!.typeArgumentsAsTypes else emptyList()

    for (newReference in newReferences) {
        val resolveInfo = newReference.resolveInfo!!

        if (newReference !in forcedCandidates) {
            val originalDescriptor = resolveInfo.type.constructor.declarationDescriptor
            val newDescriptor = newContext[BindingContext.TYPE, newReference]?.constructor?.declarationDescriptor
            if (compareDescriptors(project, originalDescriptor, newDescriptor)) continue
        }

        val equivalenceRepresentative = groupedReferencesToExtract
            .keySet()
            .firstOrNull { unifier.unify(it.reference, resolveInfo.reference).isMatched }
        if (equivalenceRepresentative != null) {
            groupedReferencesToExtract.putValue(equivalenceRepresentative, resolveInfo)
        } else {
            groupedReferencesToExtract.putValue(resolveInfo, resolveInfo)
        }

        val referencesToExtractIterator = groupedReferencesToExtract.values().iterator()
        while (referencesToExtractIterator.hasNext()) {
            val referenceToExtract = referencesToExtractIterator.next()
            if (resolveInfo.reference.isAncestor(referenceToExtract.reference, true)) {
                referencesToExtractIterator.remove()
            }
        }
    }

    val typeParameterNameValidator = CollectingNameValidator()
    val brokenReferences = groupedReferencesToExtract.keySet().filter { groupedReferencesToExtract[it].isNotEmpty() }
    val typeParameterNames = Fe10KotlinNameSuggester.suggestNamesForTypeParameters(brokenReferences.size, typeParameterNameValidator)
    val typeParameters = (typeParameterNames zip brokenReferences).map { TypeParameter(it.first, groupedReferencesToExtract[it.second]) }

    if (typeParameters.any { it.typeReferenceInfos.any { info -> info.reference.typeElement == originalTypeElement } }) {
        return IntroduceTypeAliasAnalysisResult.Error(KotlinBundle.message("text.type.alias.cannot.refer.to.types.which.aren.t.accessible.in.the.scope.where.it.s.defined"))
    }

    val descriptor = IntroduceTypeAliasDescriptor(this, "Dummy", null, typeParameters)

    val initialName = Fe10KotlinNameSuggester.suggestTypeAliasNameByPsi(descriptor.generateTypeAlias(true).getTypeReference()!!.typeElement!!) {
        targetScope.findClassifier(Name.identifier(it), NoLookupLocation.FROM_IDE) == null
    }

    return IntroduceTypeAliasAnalysisResult.Success(descriptor.copy(name = initialName))
}

fun IntroduceTypeAliasData.getApplicableVisibilities(): List<KtModifierKeywordToken> = when (targetSibling.parent) {
    is KtClassBody -> listOf(PRIVATE_KEYWORD, PUBLIC_KEYWORD, INTERNAL_KEYWORD, PROTECTED_KEYWORD)
    is KtFile -> listOf(PRIVATE_KEYWORD, PUBLIC_KEYWORD, INTERNAL_KEYWORD)
    else -> emptyList()
}

fun IntroduceTypeAliasDescriptor.validate(): IntroduceTypeAliasDescriptorWithConflicts {
    val conflicts = MultiMap<PsiElement, String>()

    val originalType = originalData.originalTypeElement
    when {
        name.isEmpty() ->
            conflicts.putValue(originalType, KotlinBundle.message("text.no.name.provided.for.type.alias"))
        !name.isIdentifier() ->
            conflicts.putValue(originalType, KotlinBundle.message("text.type.alias.name.must.be.a.valid.identifier.0", name))
        originalData.getTargetScope().findClassifier(Name.identifier(name), NoLookupLocation.FROM_IDE) != null ->
            conflicts.putValue(originalType, KotlinBundle.message("text.type.already.exists.in.the.target.scope", name))
    }

    if (typeParameters.distinctBy { it.name }.size != typeParameters.size) {
        conflicts.putValue(originalType, KotlinBundle.message("text.type.parameter.names.must.be.distinct"))
    }

    if (visibility != null && visibility !in originalData.getApplicableVisibilities()) {
        conflicts.putValue(originalType, KotlinBundle.message("text.0.is.not.allowed.in.the.target.context", visibility))
    }

    return IntroduceTypeAliasDescriptorWithConflicts(this, conflicts)
}

fun findDuplicates(typeAlias: KtTypeAlias): Map<KotlinPsiRange, () -> Unit> {
    val aliasName = typeAlias.name?.quoteIfNeeded() ?: return emptyMap()
    val aliasRange = typeAlias.textRange
    val typeAliasDescriptor = typeAlias.unsafeResolveToDescriptor() as TypeAliasDescriptor

    val unifierParameters = typeAliasDescriptor.declaredTypeParameters.map { UnifierParameter(it, null) }
    val unifier = KotlinPsiUnifier(unifierParameters)

    val psiFactory = KtPsiFactory(typeAlias.project)

    fun replaceTypeElement(occurrence: KtTypeElement, typeArgumentsText: String) {
        occurrence.replace(psiFactory.createType("$aliasName$typeArgumentsText").typeElement!!)
    }

    fun replaceOccurrence(occurrence: PsiElement, arguments: List<KtTypeElement>) {
        val typeArgumentsText = if (arguments.isNotEmpty()) "<${arguments.joinToString { it.text }}>" else ""
        when (occurrence) {
            is KtTypeElement -> {
                replaceTypeElement(occurrence, typeArgumentsText)
            }

            is KtSuperTypeCallEntry -> {
                occurrence.calleeExpression.typeReference?.typeElement?.let { replaceTypeElement(it, typeArgumentsText) }
            }

            is KtCallElement -> {
                val qualifiedExpression = occurrence.parent as? KtQualifiedExpression
                val callExpression = if (qualifiedExpression != null && qualifiedExpression.selectorExpression == occurrence) {
                    qualifiedExpression.replaced(occurrence)
                } else occurrence
                val typeArgumentList = callExpression.typeArgumentList
                if (arguments.isNotEmpty()) {
                    val newTypeArgumentList = psiFactory.createTypeArguments(typeArgumentsText)
                    typeArgumentList?.replace(newTypeArgumentList) ?: callExpression.addAfter(
                        newTypeArgumentList,
                        callExpression.calleeExpression
                    )
                } else {
                    typeArgumentList?.delete()
                }
                callExpression.calleeExpression?.replace(psiFactory.createExpression(aliasName))
            }

            is KtExpression -> occurrence.replace(psiFactory.createExpression(aliasName))
        }
    }

    val rangesWithReplacers = ArrayList<Pair<KotlinPsiRange, () -> Unit>>()

    val originalTypePsi = typeAliasDescriptor.underlyingType.constructor.declarationDescriptor?.let {
        DescriptorToSourceUtilsIde.getAnyDeclaration(typeAlias.project, it)
    }
    if (originalTypePsi != null) {
        for (reference in ReferencesSearch.search(originalTypePsi, LocalSearchScope(typeAlias.parent)).asIterable()) {
            val element = reference.element as? KtSimpleNameExpression ?: continue
            if ((element.textRange.intersects(aliasRange))) continue

            val arguments: List<KtTypeElement>
            val occurrence: KtElement

            val callElement = element.getParentOfTypeAndBranch<KtCallElement> { calleeExpression }
            if (callElement != null) {
                occurrence = callElement
                arguments = callElement.typeArguments.mapNotNull { it.typeReference?.typeElement }
            } else {
                val userType = element.getParentOfTypeAndBranch<KtUserType> { referenceExpression }
                if (userType != null) {
                    occurrence = userType
                    arguments = userType.typeArgumentsAsTypes.mapNotNull { it.typeElement }
                } else continue
            }
            if (arguments.size != typeAliasDescriptor.declaredTypeParameters.size) continue
            if (TypeUtils.isNullableType(typeAliasDescriptor.underlyingType)
                && occurrence is KtUserType
                && occurrence.parent !is KtNullableType
            ) continue
            rangesWithReplacers += occurrence.toRange() to { replaceOccurrence(occurrence, arguments) }
        }
    }
    typeAlias
        .getTypeReference()
        ?.typeElement
        .toRange()
        .match(typeAlias.parent, unifier)
        .asSequence()
        .filter { !(it.range.textRange.intersects(aliasRange)) }
        .mapNotNullTo(rangesWithReplacers) { match ->
            val occurrence = match.range.elements.singleOrNull() as? KtTypeElement ?: return@mapNotNullTo null
            val arguments = unifierParameters.mapNotNull { (match.substitution[it] as? KtTypeReference)?.typeElement }
            if (arguments.size != unifierParameters.size) return@mapNotNullTo null
            match.range to { replaceOccurrence(occurrence, arguments) }
        }
    return rangesWithReplacers.toMap()
}

private var KtTypeReference.typeParameterInfo: TypeParameter? by CopyablePsiUserDataProperty(Key.create("TYPE_PARAMETER_INFO"))

fun IntroduceTypeAliasDescriptor.generateTypeAlias(previewOnly: Boolean = false): KtTypeAlias {
    val originalElement = originalData.originalTypeElement
    val psiFactory = KtPsiFactory(originalElement.project)

    for (typeParameter in typeParameters)
        for (it in typeParameter.typeReferenceInfos) {
            it.reference.typeParameterInfo = typeParameter
        }

    val typeParameterNames = typeParameters.map { it.name }
    val typeAlias = if (originalElement is KtTypeElement) {
        psiFactory.createTypeAlias(name, typeParameterNames, originalElement)
    } else {
        psiFactory.createTypeAlias(name, typeParameterNames, originalElement.text)
    }
    if (visibility != null && visibility != DEFAULT_VISIBILITY_KEYWORD) {
        typeAlias.addModifier(visibility)
    }

    for (typeParameter in typeParameters)
        for (it in typeParameter.typeReferenceInfos) {
            it.reference.typeParameterInfo = null
        }

    fun replaceUsage() {
        val aliasInstanceText = if (typeParameters.isNotEmpty()) {
            "$name<${typeParameters.joinToString { it.typeReferenceInfos.first().reference.text }}>"
        } else {
            name
        }
        when (originalElement) {
            is KtTypeElement -> originalElement.replace(psiFactory.createType(aliasInstanceText).typeElement!!)
            is KtExpression -> originalElement.replace(psiFactory.createExpression(aliasInstanceText))
        }
    }

    fun introduceTypeParameters() {
        typeAlias.getTypeReference()!!.forEachDescendantOfType<KtTypeReference> {
            val typeParameter = it.typeParameterInfo ?: return@forEachDescendantOfType
            val typeParameterReference = psiFactory.createType(typeParameter.name)
            it.replace(typeParameterReference)
        }
    }

    return if (previewOnly) {
        introduceTypeParameters()
        typeAlias
    } else {
        replaceUsage()
        introduceTypeParameters()
        insertDeclaration(typeAlias, originalData.targetSibling)
    }
}