/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.name.Name

class SuspendCoroutineBuiltinLowering(val context: WasmBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (!isSuspendCoroutineBuiltin(declaration)) {
            return null
        }
        val function = declaration as IrSimpleFunction
        function.body = generateFunctionBody(function)
        return null
    }

    private fun isSuspendCoroutineBuiltin(declaration: IrDeclaration): Boolean =
        declaration is IrSimpleFunction && declaration.fqNameWhenAvailable?.asString() == "kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn"

    private fun generateFunctionBody(function: IrSimpleFunction): IrBody {
        val builder = context.createIrBuilder(function.symbol, function.startOffset, function.endOffset)
        val blockParam = function.valueParameters[0]
        val continuationParam = function.valueParameters[1]
        val blockCall = buildBlockCall(builder, blockParam, continuationParam)
        val suspendResultVariable = buildSuspendResultVariable(function, blockCall)
        val suspendMethodSymbol = continuationParam.type.classOrFail.getSimpleFunction("suspend")
            ?: throw RuntimeException("Continuation parameter of 'suspendCoroutineUninterceptedOrReturn' has no method 'suspend'")
        val suspendMethodCall = builder.irCall(suspendMethodSymbol).apply {
            dispatchReceiver = builder.irGet(continuationParam)
        }
        val ifThenElse = builder.irIfThenElse(
            function.returnType,
            buildCondition(builder, suspendResultVariable),
            suspendMethodCall,
            builder.irGet(suspendResultVariable),
            IrStatementOrigin.LOWERED_SUSPEND_INTRINSIC,
        )
        return builder.irBlockBody {
            +suspendResultVariable
            +builder.irReturn(ifThenElse)
        }
    }

    private fun buildBlockCall(
        builder: DeclarationIrBuilder,
        blockParam: IrValueParameter,
        continuationParam: IrValueParameter,
    ): IrCall {
        val invokeSymbol = blockParam.type.classOrFail.getSimpleFunction("invoke")
            ?: throw RuntimeException("Block parameter of suspendCoroutineUninterceptedOrReturn has no 'invoke' method")
        return builder.irCall(
            invokeSymbol,
            context.irBuiltIns.anyNType,
            valueArgumentsCount = 1,
            typeArgumentsCount = 0,
            origin = IrStatementOrigin.LOWERED_SUSPEND_INTRINSIC,
        ).apply {
            dispatchReceiver = builder.irGet(blockParam)
            putValueArgument(0, builder.irGet(continuationParam))
        }
    }

    private fun buildSuspendResultVariable(function: IrSimpleFunction, blockCall: IrCall) = buildVariable(
        null,
        function.startOffset,
        function.endOffset,
        IrDeclarationOrigin.JSPI_SUSPEND_RESULT,
        Name.identifier("jspiSuspendResult"),
        context.irBuiltIns.anyNType,
        isVar = false,
        isConst = false,
        isLateinit = false,
    ).apply {
        initializer = blockCall
    }

    private fun buildCondition(
        builder: DeclarationIrBuilder,
        suspendResultVariable: IrVariable,
    ): IrCall {
        return builder.irCall(context.irBuiltIns.eqeqeqSymbol).apply {
            origin = IrStatementOrigin.LOWERED_SUSPEND_INTRINSIC
            putValueArgument(0, builder.irGet(suspendResultVariable))
            putValueArgument(1, builder.irCall(context.ir.symbols.coroutineSuspendedGetter))
        }
    }
}
