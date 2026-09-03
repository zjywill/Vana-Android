package com.pinapia.vana.demo

import android.content.Context

/**
 * release 侧的空实现。演示数据只存在于 debug source set，正式包里连那些 JSON
 * 都没有打进去——这个文件的存在只是为了让 [VanaApplication] 能无条件调它。
 */
object DemoSeed {
    fun applyIfRequested(context: Context) = Unit
}
