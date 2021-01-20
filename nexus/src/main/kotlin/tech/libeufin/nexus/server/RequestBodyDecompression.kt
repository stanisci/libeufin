package tech.libeufin.nexus.server

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.request.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.InflaterInputStream

/**
 * Decompress request bodies.
 */
class RequestBodyDecompression private constructor() {
    companion object Feature :
        ApplicationFeature<Application, RequestBodyDecompression.Configuration, RequestBodyDecompression> {
        override val key: AttributeKey<RequestBodyDecompression> = AttributeKey("Request Body Decompression")
        override fun install(
            pipeline: Application,
            configure: RequestBodyDecompression.Configuration.() -> Unit
        ): RequestBodyDecompression {
            pipeline.receivePipeline.intercept(ApplicationReceivePipeline.Before) {
                if (this.context.request.headers["Content-Encoding"] == "deflate") {
                    val deflated = this.subject.value as ByteReadChannel
                    val brc = withContext(Dispatchers.IO) {
                        val inflated = InflaterInputStream(deflated.toInputStream())
                        // False positive in current Kotlin version, we're already in Dispatchers.IO!
                        @Suppress("BlockingMethodInNonBlockingContext") val bytes = inflated.readAllBytes()
                        ByteReadChannel(bytes)
                    }
                    proceedWith(ApplicationReceiveRequest(this.subject.typeInfo, brc))
                    return@intercept
                }
                proceed()
                return@intercept
            }
            return RequestBodyDecompression()
        }
    }

    class Configuration {

    }
}