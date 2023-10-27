/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTypeOperatorCallImpl
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getSimpleFunction

class JSPICallWasmFunctionExportsLowering(val context: WasmBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration !is IrSimpleFunction) {
            return null
        }
        val arity = getExportArity(declaration)
        if (arity < 0) {
            return null
        }
        declaration.body = generateFunctionBody(arity, declaration)
        val continuationParameter = declaration.valueParameters[declaration.valueParameters.size - 1]
        continuationParameter.type = context.wasmSymbols.wasmStructRefType
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
        val continuationParameter = function.valueParameters[function.valueParameters.size - 1]
        val cast = IrTypeOperatorCallImpl(
            function.startOffset,
            function.endOffset,
            continuationParameter.type,
            IrTypeOperator.CAST,
            continuationParameter.type,
            builder.irGet(continuationParameter),
        )
        val invokeSymbol = functionParameter.type.classOrFail.getSimpleFunction("invoke")
            ?: throw RuntimeException("${function.name} called with function argument that does not have an 'invoke' method")
        val call = builder.irCall(invokeSymbol).apply {
            dispatchReceiver = builder.irGet(functionParameter)
            for (i in 0..<arity) {
                putValueArgument(i, builder.irGet(function.valueParameters[i + 1]))
            }
            putValueArgument(arity, cast)
        }
        return IrExpressionBodyImpl(call)
    }

}