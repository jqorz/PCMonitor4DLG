package com.jqorz.pcmonitor4dlg

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform