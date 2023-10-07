/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:Suppress("RedundantSuspendModifier")

package kotlin.wasm.internal

import kotlin.coroutines.*

@PublishedApi
@ExcludedFromCodegen
internal fun <T> getContinuation(): Continuation<T> =
    implementedAsIntrinsic

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> returnIfSuspended(argument: Any?): T =
    argument as T

@PublishedApi
internal fun <T> interceptContinuationIfNeeded(
    context: CoroutineContext,
    continuation: Continuation<T>
) = context[ContinuationInterceptor]?.interceptContinuation(continuation) ?: continuation


@PublishedApi
internal suspend fun getCoroutineContext(): CoroutineContext = getContinuation<Any?>().context

@PublishedApi
internal suspend fun <T> suspendCoroutineUninterceptedOrReturn(block: (Continuation<T>) -> Any?): T =
    returnIfSuspended<T>(block(getContinuation<T>()))

@Suppress("UNUSED_PARAMETER")
@ExcludedFromCodegen
@PublishedApi
internal fun <T> startCoroutineUninterceptedOrReturnIntrinsic0(
    f: (suspend () -> T),
    completion: Continuation<T>
): Any? {
    implementedAsIntrinsic
}

@Suppress("UNUSED_PARAMETER")
@ExcludedFromCodegen
@PublishedApi
internal fun <R, T> startCoroutineUninterceptedOrReturnIntrinsic1(
    f: (suspend R.() -> T),
    receiver: R,
    completion: Continuation<T>
): Any? {
    implementedAsIntrinsic
}

@Suppress("UNUSED_PARAMETER")
@ExcludedFromCodegen
@PublishedApi
internal fun <R, P, T> startCoroutineUninterceptedOrReturnIntrinsic2(
    f: (suspend R.(P) -> T),
    receiver: R,
    param: P,
    completion: Continuation<T>
): Any? {
    implementedAsIntrinsic
}

@PublishedApi
@SinceKotlin("1.3")
internal val EmptyContinuation = Continuation<Any?>(EmptyCoroutineContext) { result ->
    result.getOrThrow()
}

@Suppress("UNUSED_PARAMETER")
internal fun jspiResumeCoroutine(result: Any, jspiCoroutineCall: JSPICoroutineExternref) {
    implementedAsIntrinsic
}

@Suppress("UNUSED_PARAMETER")
internal fun jspiSuspendCoroutine(jspiCoroutineCall: JSPICoroutineExternref): Any {
    implementedAsIntrinsic
}

@Suppress("UNUSED_PARAMETER")
internal fun <T> jspiStartCoroutine0(f: (suspend () -> T), completion: Continuation<T>) {
    implementedAsIntrinsic
}

@Suppress("UNUSED_PARAMETER")
internal fun <R, T> jspiStartCoroutine1(f: (suspend R.() -> T), receiver: R, completion: Continuation<T>) {
    implementedAsIntrinsic
}

@Suppress("UNUSED_PARAMETER")
internal fun <R, P, T> jspiStartCoroutine2(f: (suspend R.(P) -> T), receiver: R, param: P, completion: Continuation<T>) {
    implementedAsIntrinsic
}

internal suspend fun <T> jspiCallWasmFunction0(f: (suspend () -> T)): T {
    return f()
}

internal suspend fun <R, T> jspiCallWasmFunction1(f: (suspend R.() -> T), receiver: R): T {
    return receiver.f()
}

internal suspend fun <R, P, T> jspiCallWasmFunction2(f: (suspend R.(P) -> T), receiver: R, param: P): T {
    return receiver.f(param)
}

internal external class JSPICoroutineExternref {}

class JSPICoroutine<T> private constructor(private val jsObject: JSPICoroutineExternref) {
    fun resumeWith(result: Result<T>) {
        jspiResumeCoroutine(result, jsObject)
    }
}
