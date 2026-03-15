package com.aibookkeeper.core.common.extensions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.aibookkeeper.core.common.result.AppResult
import com.aibookkeeper.core.common.result.AppException

fun <T> Flow<T>.asAppResult(): Flow<AppResult<T>> =
    map<T, AppResult<T>> { AppResult.Success(it) }
        .catch { emit(AppResult.Error(AppException.DatabaseError(it.message ?: "Unknown error", it))) }
