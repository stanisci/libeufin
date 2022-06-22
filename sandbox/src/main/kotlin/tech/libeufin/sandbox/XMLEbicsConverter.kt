package tech.libeufin.sandbox

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.libeufin.util.XMLUtil
import java.io.OutputStream
import java.nio.channels.ByteChannel

class XMLEbicsConverter : ContentConverter {
    override suspend fun convertForReceive(
        context: PipelineContext<ApplicationReceiveRequest, ApplicationCall>): Any? {
        val value = context.subject.value as? ByteReadChannel ?: return null
        return withContext(Dispatchers.IO) {
            receiveEbicsXmlInternal(value.toInputStream().reader().readText())
        }
    }
    override suspend fun convertForSend(
        context: PipelineContext<Any, ApplicationCall>,
        contentType: ContentType,
        value: Any
    ): Any? {
        val conv = try {
            XMLUtil.convertJaxbToString(value)
        } catch (e: Exception) {
            /**
             * Not always a error: the content negotiation might have
             * only checked if this handler could convert the response.
             */
            // logger.info("Could not use XML custom converter for this response.")
            return null
        }
        return OutputStreamContent({
            val out = this;
            withContext(Dispatchers.IO) {
                out.write(conv.toByteArray())
            }},
            contentType.withCharset(context.call.suitableCharset())
        )
    }
}