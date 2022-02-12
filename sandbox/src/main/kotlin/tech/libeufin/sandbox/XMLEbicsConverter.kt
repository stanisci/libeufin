package tech.libeufin.sandbox

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.libeufin.util.XMLUtil
import java.io.OutputStream

public class EbicsConverter : ContentConverter {
    override suspend fun convertForReceive(context: PipelineContext<ApplicationReceiveRequest, ApplicationCall>): Any {
        return context.context.receiveEbicsXml()
    }

    override suspend fun convertForSend(
        context: PipelineContext<Any, ApplicationCall>,
        contentType: ContentType,
        value: Any
    ): Any? {
        val conv = try {
            XMLUtil.convertJaxbToString(value)
        } catch (e: Exception) {
            logger.warn("Could not convert XML to string with custom converter.")
            return null
        }
        return OutputStreamContent({
            suspend fun writeAsync(out: OutputStream) {
                withContext(Dispatchers.IO) {
                    out.write(conv.toByteArray())
                }
            }
            writeAsync(this)
        })
    }
}