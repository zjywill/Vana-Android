package com.pinapia.vana.tenant

import java.io.File
import java.util.UUID

/**
 * 每个成员一个目录:`filesDir/tenants/<uuid>/{sessions,attachments,memory.json,medications.json}`。
 *
 * 目录隔离,不是给每条记录加 tenantId。这份清单就是「隔离」的定义。
 */
object TenantPaths {
    const val ROOT_NAME = "tenants"
    const val TENANTS_FILE = "tenants.json"

    data class Item(val name: String, val isDirectory: Boolean)

    val perTenantItems = listOf(
        Item("sessions", isDirectory = true),
        Item("attachments", isDirectory = true),
        Item("memory.json", isDirectory = false),
        Item("medications.json", isDirectory = false),
    )

    fun root(forId: UUID, parent: File): File =
        File(File(parent, ROOT_NAME), forId.toString())

    fun root(forId: String, parent: File): File =
        File(File(parent, ROOT_NAME), forId)

    fun ensureTenantLayout(root: File) {
        root.mkdirs()
        for (item in perTenantItems) {
            val target = File(root, item.name)
            if (item.isDirectory) {
                target.mkdirs()
            }
        }
    }
}
