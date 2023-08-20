/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.DeclarationTransformer
import org.jetbrains.kotlin.backend.common.getOrPut
import org.jetbrains.kotlin.backend.common.ir.moveBodyTo
import org.jetbrains.kotlin.backend.common.lower.VariableRemapper
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.utils.memoryOptimizedMap
import org.jetbrains.kotlin.utils.memoryOptimizedPlus


class AddJSPIParamsToNonLocalSuspendFunctionsLowering(val context: WasmBackendContext) : DeclarationTransformer {
    override fun transformFlat(declaration: IrDeclaration): List<IrDeclaration>? {
        if (declaration !is IrSimpleFunction || !declaration.isSuspend) {
            return null
        }
        val newFunction = getOrCreateJSPISuspendFunctionStub(declaration)
        val parameterMapping = declaration.explicitParameters.zip(newFunction.explicitParameters).toMap()
        newFunction.body = declaration.moveBodyTo(newFunction, parameterMapping)
        for ((old, new) in parameterMapping.entries) {
            new.defaultValue = old.defaultValue?.transform(VariableRemapper(parameterMapping), null)
        }
        return listOf(newFunction)
    }

    private fun getOrCreateJSPISuspendFunctionStub(oldFunction: IrSimpleFunction): IrSimpleFunction {
        return context.mapping.suspendFunctionsToJSPIFunctions.getOrPut(oldFunction) {
            createJSPISuspendFunctionStub(oldFunction).also {
                context.mapping.jspiFunctionsToSuspendFunctions[it] = oldFunction
            }
        }
    }

    private fun createJSPISuspendFunctionStub(oldFunction: IrSimpleFunction): IrSimpleFunction {
        require(oldFunction.isSuspend) { "${oldFunction.fqNameWhenAvailable} should be a suspend function to create version with JSPI parameter" }
        val newFunction = oldFunction.factory.buildFun {
            updateFrom(oldFunction)
            isSuspend = false
            name = oldFunction.name
            origin = oldFunction.origin
            returnType = oldFunction.returnType
        }

        newFunction.parent = oldFunction.parent
        newFunction.metadata = oldFunction.metadata
        newFunction.copyAnnotationsFrom(oldFunction)
        newFunction.copyAttributes(oldFunction)
        newFunction.copyTypeParametersFrom(oldFunction)
        val substitutionMap = makeTypeParameterSubstitutionMap(oldFunction, newFunction)
        newFunction.copyReceiverParametersFrom(oldFunction, substitutionMap)

        newFunction.overriddenSymbols = newFunction.overriddenSymbols memoryOptimizedPlus oldFunction.overriddenSymbols.map {
            oldFunction.factory.stageController.restrictTo(it.owner) {
                getOrCreateJSPISuspendFunctionStub(it.owner).symbol
            }
        }

        newFunction.valueParameters = oldFunction.valueParameters.memoryOptimizedMap { it.copyTo(newFunction) }
        newFunction.addValueParameter {
            startOffset = newFunction.startOffset
            endOffset = newFunction.endOffset
            origin = IrDeclarationOrigin.CONTINUATION
            name = Name.identifier("\$continuation")
            type = context.wasmSymbols.jspiCoroutineClass.typeWith(oldFunction.returnType).substitute(substitutionMap)
        }

        return newFunction
    }
}

