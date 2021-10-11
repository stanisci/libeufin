import io.ktor.application.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.engine.*
import io.ktor.server.testing.*
import io.ktor.utils.io.pool.*
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledDirectByteBuf
import io.netty.channel.*
import io.netty.channel.epoll.EpollEventLoopGroup
import io.netty.channel.epoll.EpollServerDomainSocketChannel
import io.netty.channel.unix.DomainSocketAddress
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.stream.ChunkedInput
import io.netty.handler.stream.ChunkedStream
import io.netty.handler.stream.ChunkedWriteHandler
import io.netty.util.AttributeKey
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset

fun startServer(unixSocketPath: String, app: Application.() -> Unit) {
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
        ).addLast(LoggingHandler("tech.libeufin.util")
        ).addLast(HttpServerCodec() // in- and out- bound
        ).addLast(HttpObjectAggregator(Int.MAX_VALUE) // only in- bound
        ).addLast(ChunkedWriteHandler()
        ).addLast(LibeufinHttpHandler(app)) // in- bound, and triggers out- bound.
    }
}

class LibeufinHttpHandler(
    private val app: Application.() -> Unit
) : SimpleChannelInboundHandler<FullHttpRequest>() {
    @OptIn(EngineAPI::class)
    override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
        withTestApplication(app) {
            val httpVersion = msg.protocolVersion()
            // Proxying the request to Ktor API.
            val call = handleRequest {
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
            // Responding to Netty API.
            val response = DefaultHttpResponse(
                httpVersion,
                HttpResponseStatus.valueOf(statusCode)
            )
            var chunked = false
            call.response.headers.allValues().forEach { s, list ->
                if (s == HttpHeaders.TransferEncoding && list.contains("chunked"))
                    chunked = true
                response.headers().set(s, list.joinToString())
            }
            ctx.writeAndFlush(response)
            if (chunked) {
                ctx.writeAndFlush(
                    HttpChunkedInput(
                        ChunkedStream(
                            ByteArrayInputStream(call.response.byteContent)
                        )
                    )
                )
            } else {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(call.response.byteContent))
            }
        }
    }
}