/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irCatch
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name

class JSPICallWasmFunctionExportsLowering(val context: WasmBackendContext) : DeclarationTransformer {
    private val anyType = context.wasmSymbols.any.defaultType
    private val structRefType = context.wasmSymbols.wasmStructRefType
    private val coroutineConstructor = context.wasmSymbols.jspiCoroutineClass.owner.primaryConstructor
        ?: throw RuntimeException("Class kotlin.wasm.internal.JSPICoroutine does not have a primary constructor")
    private val resultClass = context.irBuiltIns.findClass(Name.identifier("Result"))
        ?: throw RuntimeException("Could not find Result class")
    private val resultCompanion = resultClass.owner.companionObject()
        ?: throw RuntimeException("Could not find companion object for Result class")
    private val resultSuccess = resultCompanion.getSimpleFunction("success")
        ?: throw RuntimeException("Could not find function Result.Companion.success")
    private val resultFailure = resultCompanion.getSimpleFunction("failure")
        ?: throw RuntimeException("Could not find function Result.Companion.success")
    private val resultConstructor = resultClass.owner.primaryConstructor
        ?: throw RuntimeException("Result class has no primary constructor")

    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration !is IrSimpleFunction) {
            return null
        }
        val arity = getExportArity(declaration)
        if (arity < 0) {
            return null
        }
        declaration.body = generateFunctionBody(arity, declaration)
        val nonContinuationParameters = declaration.valueParameters.slice(0..<(declaration.valueParameters.size - 1))
        for (parameter in nonContinuationParameters) {
            parameter.type = context.wasmSymbols.wasmStructRefType
        }
        declaration.valueParameters[declaration.valueParameters.size - 1].type = context.wasmSymbols.jspiCoroutineExternrefClass.defaultType
        return null
    }

    // returns -1 if declaration is not a jspiCallWasmFunction export
    private fun getExportArity(function: IrSimpleFunction): Int =
        when (function.fqNameWhenAvailable?.asString()) {
            "kotlin.wasm.internal.jspiCallWasmFunction0" -> 0
            "kotlin.wasm.internal.jspiCallWasmFunction1" -> 1
            "kotlin.wasm.internal.jspiCallWasmFunction2" -> 2
            else -> -1
        }

    private fun generateFunctionBody(arity: Int, function: IrSimpleFunction): IrBody {
        val builder = context.createIrBuilder(function.symbol, function.startOffset, function.endOffset)
        val functionParameter = function.valueParameters[0]
        val completionParameter = function.valueParameters[function.valueParameters.size - 2]
        val coroutineParameter = function.valueParameters[function.valueParameters.size - 1]
        val functionAnyCast = buildRefCastToAny(builder, builder.irGet(structRefType, functionParameter.symbol))
        val coroutineConstructorCall = builder.irCallConstructor(
            coroutineConstructor.symbol,
            listOf(function.typeParameters[0].defaultType),
        ).apply {
            putValueArgument(0, builder.irGet(context.wasmSymbols.jspiCoroutineExternrefClass.defaultType, coroutineParameter.symbol))
        }
        val invokeSymbol = functionParameter.type.classOrFail.getSimpleFunction("invoke")
            ?: throw RuntimeException("${function.name} called with function argument that does not have an 'invoke' method")
        val invokeCall = builder.irCall(invokeSymbol).apply {
            dispatchReceiver = functionAnyCast
            for (i in 0..<arity) {
                putValueArgument(i, builder.irCall(context.wasmSymbols.refCastNull, type = anyType).apply {
                    putTypeArgument(0, anyType)
                    putValueArgument(0, builder.irGet(function.valueParameters[i + 1], type = structRefType))
                })
            }
            putValueArgument(arity, coroutineConstructorCall)
        }
        val completionAnyCast = buildRefCastToAny(builder, builder.irGet(structRefType, completionParameter.symbol))
        val coroutineResultType = function.typeParameters[arity].defaultType
        val resumeSymbol = completionParameter.type.classOrFail.getSimpleFunction("resumeWith")
            ?: throw RuntimeException("Could not find function Continuation.resumeWith")
        val catchVariable = buildVariable(
            function,
            function.startOffset,
            function.endOffset,
            IrDeclarationOrigin.CATCH_PARAMETER,
            Name.identifier("t"),
            context.irBuiltIns.throwableType,
        )
        val tryExpression = builder.irTry(
            resultClass.typeWith(coroutineResultType),
            builder.irCall(resultSuccess).apply {
                putTypeArgument(0, coroutineResultType)
                dispatchReceiver = builder.irGetObject(resultCompanion.symbol)
                putValueArgument(0, invokeCall)
            },
            listOf(builder.irCatch(catchVariable, builder.irCall(resultFailure).apply {
                putTypeArgument(0, coroutineResultType)
                dispatchReceiver = builder.irGetObject(resultCompanion.symbol)
                putValueArgument(0, builder.irGet(catchVariable))
            })),
            null,
        )
        val resumeCall = builder.irCall(resumeSymbol).apply {
            dispatchReceiver = completionAnyCast
            putValueArgument(0, tryExpression)
        }
        return IrExpressionBodyImpl(resumeCall)
    }

    private fun buildRefCastToAny(builder: DeclarationIrBuilder, expression: IrExpression): IrCall =
        builder.irCall(context.wasmSymbols.refCastNull, type = anyType).apply {
            putTypeArgument(0, anyType)
            putValueArgument(0, expression)
        }
}
