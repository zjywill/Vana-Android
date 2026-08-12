package com.pinapia.vana

/**
 * 功能开关。关掉不等于删代码——对应实现还在,开回来改这一处。
 *
 * Health Connect:大陆发行版手机普遍没有 Google 的 HC(也装不了 Play 商店那条
 * sidecar)。在有稳定替代数据源之前,**不要**把健康工具挂进 agent,也不要在首启弹
 * HC 授权 / 安装引导。理由见 `CLAUDE.md`「Health Connect 暂缓」。
 */
object Features {
    const val HEALTH_CONNECT = false
}
