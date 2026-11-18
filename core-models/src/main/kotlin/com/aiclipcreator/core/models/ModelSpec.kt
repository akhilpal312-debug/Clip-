package com.aiclipcreator.core.models

import kotlinx.serialization.Serializable

/**
 * A downloadable, verifiable on-device model artifact.
 *
 * [sha256] is the hash of the exact artifact published at [url] and is verified after download.
 */
@Serializable
data class ModelSpec(
    val id: String,
    val name: String,
    val category: ModelCategory,
    val runtime: ModelRuntime,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
    val source: String,
    val version: String,
    val architectures: List<String> = listOf("arm64-v8a"),
    val minRamMb: Long = 512,
    val minFreeStorageMb: Long = 300,
    val description: String,
    val recommendedFor: ProductionProfile = ProductionProfile.BALANCED,
    val variantOf: String? = null
)

@Serializable
data class ModelStatus(
    val spec: ModelSpec,
    val state: ModelState,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val localPath: String? = null,
    val error: String? = null,
    val selfTestPassed: Boolean = false,
    val updatedAt: Long = 0L
)

@Serializable
data class ModelInstallEvent(
    val modelId: String,
    val stage: String,
    val message: String,
    val progress: Float,
    val timestamp: Long = System.currentTimeMillis()
)
