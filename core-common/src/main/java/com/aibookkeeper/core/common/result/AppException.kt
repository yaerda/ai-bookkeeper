package com.aibookkeeper.core.common.result

sealed class AppException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    class NetworkError(message: String = "网络连接失败", cause: Throwable? = null)
        : AppException(message, cause)

    class AiExtractionError(message: String = "AI 识别失败", cause: Throwable? = null)
        : AppException(message, cause)

    class DatabaseError(message: String = "数据操作失败", cause: Throwable? = null)
        : AppException(message, cause)

    class ValidationError(message: String)
        : AppException(message)

    class PermissionError(message: String)
        : AppException(message)
}
