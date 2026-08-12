package com.pinapia.vana.tenant

import com.pinapia.vana.medications.MedicationSnapshot

/** 家人成员的首屏：那句话和那三颗 chip。完全本地拼，一次模型调用都不发。 */
object TenantOpening {
    fun quickSummary(tenant: Tenant, medications: MedicationSnapshot): String {
        val name = tenant.displayName
        if (medications.items.isEmpty()) {
            return "这里是${name}的记录。拍一张${name}的化验单或报告，或者先把${name}在吃的药记下来。"
        }
        return "${name}的清单里记着 ${medications.items.size} 样东西。" +
            "读不到${name}的健康数据，要看具体数值就拍一张化验单给我。"
    }

    fun questions(tenant: Tenant, medications: MedicationSnapshot): List<String> {
        val name = tenant.displayName
        val questions = mutableListOf<String>()
        if (medications.items.isEmpty()) {
            questions += "把${name}在吃的药记下来"
        } else {
            questions += "${name}在吃的这些一起吃有问题吗"
            questions += "这几样有什么要注意的"
        }
        questions += "化验单上哪几项要重点看"
        tenant.ageBand?.let { questions += "${it.label}体检要重点查什么" }
        return questions.take(3)
    }
}
