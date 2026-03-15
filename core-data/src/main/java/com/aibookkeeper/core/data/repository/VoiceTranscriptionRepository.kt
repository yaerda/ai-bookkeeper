package com.aibookkeeper.core.data.repository

import java.io.File

interface VoiceTranscriptionRepository {
    fun isConfigured(): Boolean
    suspend fun transcribe(audioFile: File): Result<String>
}
