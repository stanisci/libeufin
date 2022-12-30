package tech.libeufin.sandbox

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.libeufin.util.XMLUtil

class XMLEbicsConverter : ContentConverter {
    override suspend fun deserialize(
        charset: io.ktor.utils.io.charsets.Charset,
        typeInfo: io.ktor.util.reflect.TypeInfo,
        content: ByteReadChannel
    ): Any? {
        return withContext(Dispatchers.IO) {
            try {
                receiveEbicsXmlInternal(content.toInputStream().reader().readText())
            } catch (e: Exception) {
                throw SandboxError(
                    HttpStatusCode.BadRequest,
                    "Document is invalid XML."
                )
            }
        }
    }

    // The following annotation was suggested by Intellij.
    @Deprecated(
        "Please override and use serializeNullable instead",
        replaceWith = ReplaceWith("serializeNullable(charset, typeInfo, contentType, value)"),
        level = DeprecationLevel.WARNING
    )
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any
    ): OutgoingContent? {
        return super.serializeNullable(contentType, charset, typeInfo, value)
    }

    override suspend fun serializeNullable(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?
    ): OutgoingContent? {
        val conv = try {
            XMLUtil.convertJaxbToString(value)
        } catch (e: Exception) {
            /**
             * Not always a error: the content negotiation might have
             * only checked if this handler could convert the response.
             */
            return null
        }
        return OutputStreamContent({
            val out = this;
            withContext(Dispatchers.IO) {
                out.write(conv.toByteArray())
            }},
            contentType.withCharset(charset)
        )
    }
}