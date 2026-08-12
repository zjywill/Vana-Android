package com.pinapia.vana.tenant

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class TenantStore(
    private val parent: File,
    private val json: Json = defaultJson,
) {
    private val file = File(parent, TenantPaths.TENANTS_FILE)

    @Serializable
    private data class Envelope(
        val version: Int = 1,
        val tenants: List<Tenant> = emptyList(),
    )

    fun load(): List<Tenant> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(Envelope.serializer(), file.readText()).tenants
        }.getOrDefault(emptyList())
    }

    fun save(tenants: List<Tenant>) {
        parent.mkdirs()
        file.writeText(json.encodeToString(Envelope.serializer(), Envelope(tenants = tenants)))
    }

    fun owner(): Tenant {
        val all = ensureBootstrapped()
        return all.firstOrNull { it.isOwner }
            ?: throw IllegalStateException("成员名单里没有机主")
    }

    fun all(): List<Tenant> = ensureBootstrapped()

    fun addManaged(name: String, ageBand: Tenant.AgeBand? = null): Tenant {
        val all = ensureBootstrapped().toMutableList()
        if (all.size >= MAX_TENANTS) {
            throw IllegalStateException("最多只能添加 12 位成员")
        }
        val tenant = Tenant(
            name = Tenant.normalized(name),
            kind = Tenant.Kind.MANAGED,
            ageBand = ageBand,
        )
        all += tenant
        save(all)
        TenantPaths.ensureTenantLayout(TenantPaths.root(tenant.id, parent))
        return tenant
    }

    fun update(tenant: Tenant) {
        val all = ensureBootstrapped().toMutableList()
        val index = all.indexOfFirst { it.id == tenant.id }
        require(index >= 0)
        all[index] = tenant.copy(name = Tenant.normalized(tenant.name))
        save(all)
    }

    fun remove(id: String) {
        val all = ensureBootstrapped().toMutableList()
        val target = all.firstOrNull { it.id == id } ?: return
        if (target.isOwner) {
            throw IllegalStateException("本人这一条不能删除")
        }
        all.removeAll { it.id == id }
        save(all)
        TenantPaths.root(id, parent).deleteRecursively()
    }

    /** 名单必须在任何视图建起来之前就位。 */
    fun ensureBootstrapped(): List<Tenant> {
        val existing = load()
        if (existing.any { it.isOwner }) {
            for (tenant in existing) {
                TenantPaths.ensureTenantLayout(TenantPaths.root(tenant.id, parent))
            }
            return existing
        }
        val owner = Tenant.owner()
        save(listOf(owner))
        TenantPaths.ensureTenantLayout(TenantPaths.root(owner.id, parent))
        migrateLegacyIfNeeded(owner.id)
        return listOf(owner)
    }

    private fun migrateLegacyIfNeeded(ownerId: String) {
        val ownerRoot = TenantPaths.root(ownerId, parent)
        for (item in TenantPaths.perTenantItems) {
            val legacy = File(parent, item.name)
            val dest = File(ownerRoot, item.name)
            if (!legacy.exists()) continue
            if (item.isDirectory) {
                if (!dest.exists()) {
                    legacy.copyRecursively(dest, overwrite = false)
                }
            } else if (!dest.exists()) {
                legacy.copyTo(dest, overwrite = false)
            }
        }
    }

    companion object {
        const val MAX_TENANTS = 12

        val defaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
