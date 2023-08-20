/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm

import org.jetbrains.kotlin.backend.common.DefaultDelegateFactory
import org.jetbrains.kotlin.ir.backend.js.JsMapping
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

class WasmMapping : JsMapping() {
    val suspendFunctionsToJSPIFunctions = DefaultDelegateFactory.newDeclarationToDeclarationMapping<IrSimpleFunction, IrSimpleFunction>()
    val jspiFunctionsToSuspendFunctions = DefaultDelegateFactory.newDeclarationToDeclarationMapping<IrSimpleFunction, IrSimpleFunction>()
}
