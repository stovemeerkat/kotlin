/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.light.classes.symbol.fields

import com.intellij.psi.*
import org.jetbrains.annotations.NotNull
import org.jetbrains.kotlin.analysis.api.KtAnalysisSession
import org.jetbrains.kotlin.analysis.api.lifetime.isValid
import org.jetbrains.kotlin.analysis.api.symbols.KtNamedClassOrObjectSymbol
import org.jetbrains.kotlin.asJava.builder.LightMemberOrigin
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.light.classes.symbol.FirLightIdentifier
import org.jetbrains.kotlin.light.classes.symbol.annotations.FirLightSimpleAnnotation
import org.jetbrains.kotlin.light.classes.symbol.annotations.hasDeprecatedAnnotation
import org.jetbrains.kotlin.light.classes.symbol.modifierLists.FirLightMemberModifierList
import org.jetbrains.kotlin.light.classes.symbol.nonExistentType
import org.jetbrains.kotlin.light.classes.symbol.toPsiVisibilityForMember
import org.jetbrains.kotlin.psi.KtDeclaration

context(KtAnalysisSession)
internal class FirLightFieldForObjectSymbol(
    private val objectSymbol: KtNamedClassOrObjectSymbol,
    containingClass: KtLightClass,
    private val name: String,
    lightMemberOrigin: LightMemberOrigin?,
) : FirLightField(containingClass, lightMemberOrigin) {

    override val kotlinOrigin: KtDeclaration? = objectSymbol.psi as? KtDeclaration

    override fun getName(): String = name

    private val _modifierList: PsiModifierList by lazyPub {
        val modifiers = setOf(objectSymbol.toPsiVisibilityForMember(isTopLevel = false), PsiModifier.STATIC, PsiModifier.FINAL)
        val notNullAnnotation = FirLightSimpleAnnotation(NotNull::class.java.name, this)
        FirLightMemberModifierList(this, modifiers, listOf(notNullAnnotation))
    }

    private val _isDeprecated: Boolean by lazyPub {
        objectSymbol.hasDeprecatedAnnotation()
    }

    override fun isDeprecated(): Boolean = _isDeprecated

    override fun getModifierList(): PsiModifierList? = _modifierList

    private val _type: PsiType by lazyPub {
        objectSymbol.buildSelfClassType().asPsiType(this@FirLightFieldForObjectSymbol)
            ?: nonExistentType()
    }

    private val _identifier: PsiIdentifier by lazyPub {
        FirLightIdentifier(this, objectSymbol)
    }

    override fun getNameIdentifier(): PsiIdentifier = _identifier


    override fun getType(): PsiType = _type

    override fun getInitializer(): PsiExpression? = null //TODO

    override fun equals(other: Any?): Boolean =
        this === other ||
                (other is FirLightFieldForObjectSymbol &&
                        kotlinOrigin == other.kotlinOrigin &&
                        objectSymbol == other.objectSymbol)

    override fun hashCode(): Int = kotlinOrigin.hashCode()

    override fun isValid(): Boolean = super.isValid() && objectSymbol.isValid()
}
