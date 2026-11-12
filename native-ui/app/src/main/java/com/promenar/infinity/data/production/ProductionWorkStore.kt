package com.promenar.infinity.data.production

import com.promenar.infinity.data.repository.ProductionRepository

/** 复用项目事务与修订号；测试可替换为内存快照。 */
interface ProductionWorkStore {
    suspend fun load(sessionId: String): ProductionSnapshot?
    suspend fun apply(sessionId: String, snapshot: ProductionSnapshot, action: ProductionUserAction): ProductionSnapshot
}
class RepositoryProductionWorkStore(private val repository: ProductionRepository) : ProductionWorkStore {
    override suspend fun load(sessionId: String) = repository.get(sessionId)
    override suspend fun apply(sessionId: String, snapshot: ProductionSnapshot, action: ProductionUserAction) =
        repository.applyForProduction(sessionId, snapshot.production.id, snapshot.revision, action)
}
