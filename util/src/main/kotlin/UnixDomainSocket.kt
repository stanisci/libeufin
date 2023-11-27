import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.server.application.*
import io.ktor.client.statement.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.stream.ChunkedStream
import io.netty.handler.stream.ChunkedWriteHandler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

private val logger: Logger = LoggerFactory.getLogger("tech.libeufin.util.UnixDomainSocket")

fun startServer(
    unixSocketPath: String,
    app: Application.() -> Unit
) {
    val boss = EpollEventLoopGroup()
    val worker = EpollEventLoopGroup()
    try {
        val serverBootstrap = ServerBootstrap()
        serverBootstrap.group(boss, worker).channel(
            EpollServerDomainSocketChannel::class.java
        ).childHandler(LibeufinHttpInit(app))
        val socketPath = DomainSocketAddress(unixSocketPath)
        logger.debug("Listening on $unixSocketPath ..")
        serverBootstrap.bind(socketPath).sync().channel().closeFuture().sync()
    } finally {
        boss.shutdownGracefully()
        worker.shutdownGracefully()
    }
}

class LibeufinHttpInit(
    private val app: Application.() -> Unit
) : ChannelInitializer<Channel>() {
    override fun initChannel(ch: Channel) {
        ch.pipeline(
        ).addLast(LoggingHandler("tech.libeufin.dev")
        ).addLast(HttpServerCodec() // in- and out- bound
        ).addLast(HttpObjectAggregator(Int.MAX_VALUE) // only in- bound
        ).addLast(ChunkedWriteHandler()
        ).addLast(LibeufinHttpHandler(app)) // in- bound, and triggers out- bound.
    }
}

class LibeufinHttpHandler(
    private val app: Application.() -> Unit
) : SimpleChannelInboundHandler<FullHttpRequest>() {
    // @OptIn(EngineAPI::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        testApplication {
            application(app)
            val httpVersion = msg.protocolVersion()
            // Proxying the request to Ktor API.
            val r = client.request(msg.uri()) {
                expectSuccess = false
                method = HttpMethod(msg.method().name())
                setBody(ByteBufInputStream(msg.content()).readAllBytes())
            }
            // Responding to Netty API.
            val response = DefaultHttpResponse(
                httpVersion,
                HttpResponseStatus.valueOf(r.status.value)
            )
            var chunked = false
            r.headers.forEach { s, list ->
                if (s == HttpHeaders.TransferEncoding && list.contains("chunked"))
                    chunked = true
                response.headers().set(s, list.joinToString())
            }
            ctx.writeAndFlush(response)
            if (chunked) {
                ctx.writeAndFlush(
                    HttpChunkedInput(
                        ChunkedStream(
                            ByteArrayInputStream(r.readBytes())
                        )
                    )
                )
            } else {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(r.readBytes()))
            }
        }
    }
}