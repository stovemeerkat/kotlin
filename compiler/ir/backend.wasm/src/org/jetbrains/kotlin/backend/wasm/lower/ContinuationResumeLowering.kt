/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class ContinuationResumeLowering(val context: WasmBackendContext) : BodyLoweringPass {
    override fun lower(irFile: IrFile) {
        runOnFilePostfix(irFile, withLocalDeclarations = true)
    }

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container !is IrSimpleFunction || !context.mapping.jspiFunctionsToSuspendFunctions.keys.contains(container)) {
            // TODO: maybe add code to make sure resume is not called outside of suspend functions
            //       (This is only a limitation of this particular implementation.)
            return
        }
        val paramsLen = container.valueParameters.size
        val suspenderParam = container.valueParameters[paramsLen - 1]

        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid()
                if (!isResumeWithCall(expression)) {
                    return expression
                }
                val builder = context.createIrBuilder(container.symbol, expression.startOffset, expression.endOffset)
                val receiver = expression.dispatchReceiver
                    ?: throw RuntimeException("Call to 'Continuation.resumeWith' has no dispatch receiver ")
                return builder.irCall(context.wasmSymbols.jspiResumeCoroutine).apply {
                    putValueArgument(0, expression.getValueArgument(0))
                    putValueArgument(1, receiver)
                    putValueArgument(2, builder.irGet(suspenderParam))
                    copyTypeArgumentsFrom(expression)
                }
            }
        })
    }

    private fun isResumeWithCall(call: IrCall): Boolean {
        if (call.symbol.owner.name.asString() != "resumeWith") {
            return false
        }
        val receiverClassSymbol = call.dispatchReceiver?.type?.classOrNull ?: return false
        return receiverClassSymbol == context.wasmSymbols.continuationClass || receiverClassSymbol.owner.superTypes.find {
            it.classOrFail == context.wasmSymbols.continuationClass
        } !== null
    }
}
