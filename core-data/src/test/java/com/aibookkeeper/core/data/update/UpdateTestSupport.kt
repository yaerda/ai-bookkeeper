package com.aibookkeeper.core.data.update

import io.mockk.every
import io.mockk.mockk
import okhttp3.Call
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

internal fun updateCalls(
    onCancel: () -> Unit = {},
    response: (Request) -> Response
): Call.Factory = object : Call.Factory {
    override fun newCall(request: Request): Call = mockk {
        every { execute() } answers { response(request) }
        every { cancel() } answers { onCancel() }
    }
}

internal fun updateResponse(
    request: Request,
    body: ResponseBody,
    code: Int = 200
): Response = Response.Builder()
    .request(request)
    .protocol(Protocol.HTTP_1_1)
    .code(code)
    .message("Test response")
    .body(body)
    .build()
