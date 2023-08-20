/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.BodyLoweringPass
import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

class SuspendCoroutineBuiltinLowering(val context: WasmBackendContext) : BodyLoweringPass {
    override fun lower(irFile: IrFile) {
        runOnFilePostfix(irFile, withLocalDeclarations = true)
    }

    override fun lower(irBody: IrBody, container: IrDeclaration) {
        if (container !is IrSimpleFunction || !context.mapping.jspiFunctionsToSuspendFunctions.keys.contains(container)) {
            return
        }
        val paramsLen = container.valueParameters.size
        val continuationParam = container.valueParameters[paramsLen - 2]
        val suspenderParam = container.valueParameters[paramsLen - 1]

        irBody.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitBody(body: IrBody): IrBody {
                // Nested bodies are covered by separate `lower` invocation
                return body
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid()
                if (!Symbols.isSuspendCoroutineIntrinsic(expression.symbol)) {
                    return expression
                }
                val builder = context.createIrBuilder(container.symbol, expression.startOffset, expression.endOffset)
                val blockCall = buildBlockCall(expression, builder, continuationParam)
                val suspendResult = buildSuspendResultVariable(irBody, blockCall)
                val intrinsicCall = builder.irCall(context.wasmSymbols.jspiSuspendCoroutine).apply {
                    putValueArgument(0, builder.irGet(continuationParam))
                    putValueArgument(1, builder.irGet(suspenderParam))
                }
                val ifThenElse = builder.irIfThenElse(
                    expression.type,
                    buildCondition(builder, suspendResult),
                    intrinsicCall,
                    builder.irReturn(builder.irGet(suspendResult)),
                    IrStatementOrigin.LOWERED_SUSPEND_INTRINSIC,
                )
                return builder.irComposite(origin = IrStatementOrigin.LOWERED_SUSPEND_INTRINSIC) {
                    resultType = expression.type
                    +suspendResult
                    +ifThenElse
                }
            }
        })
    }

    private fun buildSuspendResultVariable(irBody: IrBody, blockCall: IrCall) = buildVariable(
        null,
        irBody.startOffset,
        irBody.endOffset,
        IrDeclarationOrigin.JSPI_SUSPEND_RESULT,
        Name.identifier("jspiSuspendResult"),
        context.irBuiltIns.anyNType,
        isVar = true,
        isConst = false,
        isLateinit = false,
    ).apply {
        initializer = blockCall
    }

    private fun buildBlockCall(
        oldIntrinsicCall: IrCall,
        builder: DeclarationIrBuilder,
        continuationParam: IrValueParameter,
    ): IrCall {
        val suspendBlock = oldIntrinsicCall.getValueArgument(0)
            ?: throw RuntimeException("Call to suspendCoroutineUninterceptedOrReturn has no parameters")
        val invokeSymbol = suspendBlock.type.classOrFail.getSimpleFunction("invoke")
            ?: throw RuntimeException("Block passed to suspendCoroutineUninterceptedOrReturn has no 'invoke' method")
        return builder.irCall(
            invokeSymbol,
            context.irBuiltIns.anyNType,
            valueArgumentsCount = 1,
            typeArgumentsCount = oldIntrinsicCall.typeArgumentsCount,
            origin = IrStatementOrigin.LOWERED_SUSPEND_INTRINSIC,
        ).apply {
            dispatchReceiver = suspendBlock
            putValueArgument(0, builder.irGet(continuationParam))
            copyTypeArgumentsFrom(oldIntrinsicCall)
        }
    }

    private fun buildCondition(
        builder: DeclarationIrBuilder,
        suspendResult: IrVariable,
    ): IrCall {
        return builder.irCall(context.irBuiltIns.eqeqeqSymbol).apply {
            origin = IrStatementOrigin.LOWERED_SUSPEND_INTRINSIC
            putValueArgument(0, builder.irGet(suspendResult))
            putValueArgument(1, builder.irCall(context.ir.symbols.coroutineSuspendedGetter))
        }
    }
}
