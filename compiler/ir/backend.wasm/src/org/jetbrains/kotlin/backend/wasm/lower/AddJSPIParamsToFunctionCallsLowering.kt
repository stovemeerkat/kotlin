/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.irCall
import org.jetbrains.kotlin.ir.util.isSuspend
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

class AddJSPIParamsToFunctionCallsLowering(val context: WasmBackendContext) : BodyLoweringPass {
    override fun lower(irFile: IrFile) {
        runOnFilePostfix(irFile, withLocalDeclarations = true)
    }

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container !is IrSimpleFunction || !isJSPIFunction(container)) {
            return
        }
        val paramsLen = container.valueParameters.size
        val continuationParam = container.valueParameters[paramsLen - 2]
        val suspenderParam = container.valueParameters[paramsLen - 1]
        val builder by lazy { context.createIrBuilder(container.symbol) }

        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitBody(body: IrBody): IrBody {
                // Nested bodies are covered by separate `lower` invocation
                return body
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid()
                if (!expression.isSuspend) {
                    return expression
                }
                val oldFun = expression.symbol.owner
                val newFun = context.mapping.suspendFunctionsToJSPIFunctions[oldFun]
                    ?: throw RuntimeException("Suspending function is called but was not lowered to have JSPI parameters")
                return irCall(
                    expression,
                    newFun.symbol,
                    newSuperQualifierSymbol = expression.superQualifierSymbol,
                ).also {
                    it.putValueArgument(it.valueArgumentsCount - 2, builder.irGet(continuationParam))
                    it.putValueArgument(it.valueArgumentsCount - 1, builder.irGet(suspenderParam))
                }
            }
        })
    }

    private fun isJSPIFunction(declaration: IrSimpleFunction): Boolean =
        declaration.symbol == context.wasmSymbols.suspendCoroutineUninterceptedOrReturn
                || context.mapping.jspiFunctionsToSuspendFunctions.keys.contains(declaration)
}
