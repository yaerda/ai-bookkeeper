package com.aibookkeeper.core.data.model

fun projectLabels(
    projectIds: List<String>?,
    bindings: List<ProjectBinding>
): List<String> {
    if (projectIds.isNullOrEmpty()) {
        return emptyList()
    }
    val names = bindings.associateBy(ProjectBinding::projectId)
    return projectIds.map { projectId ->
        names[projectId]?.name ?: "项目"
    }
}

fun projectSummary(
    projectIds: List<String>?,
    bindings: List<ProjectBinding>,
    limit: Int = 2
): String? {
    val labels = projectLabels(projectIds, bindings)
    if (labels.isEmpty()) {
        return null
    }
    val visible = labels.take(limit).joinToString(" · ") { "#$it" }
    return if (labels.size > limit) {
        "$visible +${labels.size - limit}"
    } else {
        visible
    }
}
