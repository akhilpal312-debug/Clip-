package com.aiclipcreator.core.data

import com.aiclipcreator.core.ai.ModelStore
import com.aiclipcreator.core.ai.StoredModel
import com.aiclipcreator.core.database.ModelDao
import com.aiclipcreator.core.database.ModelEntity
import com.aiclipcreator.core.models.ModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelRepository(private val dao: ModelDao) : ModelStore {

    override suspend fun all(): List<StoredModel> = withContext(Dispatchers.IO) {
        dao.all().map {
            StoredModel(
                id = it.id,
                state = runCatching { ModelState.valueOf(it.state) }.getOrDefault(ModelState.NOT_INSTALLED),
                progress = it.progress,
                downloadedBytes = it.downloadedBytes,
                localPath = it.localPath,
                error = it.error,
                selfTestPassed = it.selfTestPassed,
                updatedAt = it.updatedAt
            )
        }
    }

    override suspend fun save(model: StoredModel) = withContext(Dispatchers.IO) {
        dao.upsert(
            ModelEntity(
                id = model.id,
                state = model.state.name,
                progress = model.progress,
                downloadedBytes = model.downloadedBytes,
                localPath = model.localPath,
                error = model.error,
                selfTestPassed = model.selfTestPassed,
                updatedAt = model.updatedAt
            )
        )
        Unit
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.delete(id)
        Unit
    }

}
