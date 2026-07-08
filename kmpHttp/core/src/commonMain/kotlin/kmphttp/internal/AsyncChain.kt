package kmphttp.internal

import kmphttp.AsyncInterceptor
import kmphttp.RedirectOptions
import kmphttp.Request
import kmphttp.Response

class AsyncChain(
    val interceptors: List<AsyncInterceptor>,
    val interceptorIndex: Int,
    val execute: suspend (Request) -> Response,
    override val request: Request,
    override val options: RedirectOptions
) : AsyncInterceptor.Chain {
    override suspend fun proceed(request: Request): Response {
        if (interceptorIndex == interceptors.size) {
            return execute(request)
        } else {
            val interceptor = interceptors[interceptorIndex]
            return interceptor.intercept(AsyncChain(interceptors, interceptorIndex + 1, execute, request, options))
        }
    }
}
