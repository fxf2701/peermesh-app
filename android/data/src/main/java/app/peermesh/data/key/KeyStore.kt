package app.peermesh.data.key

import app.peermesh.data.db.dao.KeyDao
import app.peermesh.data.db.entity.KeyEntity

class KeyStore(private val keyDao: KeyDao) {
    fun upsertKey(gid: String, kid: String, nowSeconds: Long) {
        val existing = keyDao.findByGid(gid)
        val createdAt = existing?.created_at ?: nowSeconds
        val updated = KeyEntity(
            gid = gid,
            kid = kid,
            created_at = createdAt,
            updated_at = nowSeconds,
        )
        keyDao.upsert(updated)
    }

    fun getKey(gid: String): KeyEntity? = keyDao.findByGid(gid)
}
