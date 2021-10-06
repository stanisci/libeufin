import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpMethod
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerDomainSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.util.AttributeKey

fun startServer(unixSocketPath: String, app: Application.() -> Unit) {

    val boss = EpollEventLoopGroup()
    val worker = EpollEventLoopGroup()
    val serverBootstrap = ServerBootstrap()
    serverBootstrap.group(boss, worker).channel(
        EpollServerDomainSocketChannel::class.java
    ).childHandler(LibeufinHttpInit(app))

    val socketPath = DomainSocketAddress(unixSocketPath)
    serverBootstrap.bind(socketPath).sync().channel().closeFuture().sync()
}

private val ktorApplicationKey = AttributeKey.newInstance<Application.() -> Unit>("KtorApplicationCall")

class LibeufinHttpInit(private val app: Application.() -> Unit) : ChannelInitializer<Channel>() {
    override fun initChannel(ch: Channel) {
        val libeufinHandler = LibeufinHttpHandler()
        ch.pipeline(
        ).addLast(
            HttpServerCodec()
        ).addLast(
            HttpObjectAggregator(Int.MAX_VALUE)
        ).addLast(
            libeufinHandler
        )
        val libeufinCtx: ChannelHandlerContext = ch.pipeline().context(libeufinHandler)
        libeufinCtx.attr(ktorApplicationKey).set(app)
    }
}

class LibeufinHttpHandler : SimpleChannelInboundHandler<FullHttpRequest>() {

    @OptIn(EngineAPI::class)
    override fun channelRead0(ctx: ChannelHandlerContext?, msg: FullHttpRequest) {
        val app = ctx?.attr(ktorApplicationKey)?.get()
        if (app == null) throw UtilError(
            HttpStatusCode.InternalServerError,
            "custom libEufin Unix-domain-socket+HTTP handler lost its Web app",
            null
        )
        /**
         * Below is only a echo of what euFin gets from the network.  All
         * the checks should then occur at the Web app + Ktor level.  Hence,
         * a HTTP call of GET with a non-empty body is not to be blocked / warned
         * at this level.
         *
         * The only exception is the HTTP version value in the response, as the
         * response returned by the Web app does not set it.  Therefore, this
         * proxy echoes back the HTTP version that was read in the request.
         */
        withTestApplication(app) {
            val httpVersion = msg.protocolVersion()
            // Proxying the request with Ktor API.
            val call = handleRequest(closeRequest = false) {
                msg.headers().forEach { addHeader(it.key, it.value) }
                method = HttpMethod(msg.method().name())
                uri = msg.uri()
                version = httpVersion.text()
                setBody(ByteBufInputStream(msg.content()).readAllBytes())
            }
            val statusCode: Int = call.response.status()?.value ?: throw UtilError(
                HttpStatusCode.InternalServerError,
                "app proxied via Unix domain socket did not include a response status code",
                ec = null // FIXME: to be defined.
            )
            // Responding with Netty API.
            val response = DefaultFullHttpResponse(
                httpVersion,
                HttpResponseStatus.valueOf(statusCode),
                Unpooled.wrappedBuffer(call.response.byteContent ?: ByteArray(0))
            )
            call.response.headers.allValues().forEach { s, list ->
                response.headers().set(s, list.joinToString()) // joinToString() separates with ", " by default.
            }
            ctx.write(response)
            ctx.flush()
        }
    }
}