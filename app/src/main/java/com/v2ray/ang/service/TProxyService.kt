package com.v2ray.ang.service

/**
 * Do NOT rename or move this class. The prebuilt libhev-socks5-tunnel.so registers its native
 * methods for exactly "com/v2ray/ang/service/TProxyService".
 */
class TProxyService {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}
