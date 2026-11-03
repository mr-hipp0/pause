package com.pause.data

import com.pause.domain.AppCategory
import com.pause.domain.ProtectedApp

internal fun ProtectedAppEntity.toProtectedApp(): ProtectedApp = ProtectedApp(
    packageName = packageName,
    enabled = enabled,
    config = configJson?.let(ConfigCodec::decode),
    categoryOverride = enumValues<AppCategory>().firstOrNull { it.name == categoryOverride },
)

internal fun ProtectedApp.toEntity(): ProtectedAppEntity = ProtectedAppEntity(
    packageName = packageName,
    enabled = enabled,
    configJson = config?.let(ConfigCodec::encode),
    categoryOverride = categoryOverride?.name,
)
