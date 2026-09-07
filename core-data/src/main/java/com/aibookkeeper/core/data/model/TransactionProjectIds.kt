package com.aibookkeeper.core.data.model

private const val PROJECT_IDS_SEPARATOR = "\u001F"
private const val PROJECT_IDS_STATE_UNSPECIFIED = "UNSPECIFIED"
private const val PROJECT_IDS_STATE_EXPLICIT = "EXPLICIT"

fun encodeProjectIds(projectIds: List<String>?): Pair<String, String?> =
    if (projectIds == null) {
        PROJECT_IDS_STATE_UNSPECIFIED to null
    } else {
        PROJECT_IDS_STATE_EXPLICIT to projectIds.joinToString(PROJECT_IDS_SEPARATOR)
    }

fun decodeProjectIds(state: String, blob: String?): List<String>? =
    when (state) {
        PROJECT_IDS_STATE_UNSPECIFIED -> null
        else -> {
            if (blob.isNullOrEmpty()) {
                emptyList()
            } else {
                blob.split(PROJECT_IDS_SEPARATOR).filter { it.isNotBlank() }
            }
        }
    }
