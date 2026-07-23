package hev.htproxy

class TProxyService {
    private external fun TProxyStartService(configPath: String, fileDescriptor: Int)
    private external fun TProxyStopService()
    private external fun TProxyGetStats(): LongArray

    fun start(configPath: String, fileDescriptor: Int) {
        TProxyStartService(configPath, fileDescriptor)
    }

    fun stop() {
        TProxyStopService()
    }

    fun stats(): LongArray = TProxyGetStats()

    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}
