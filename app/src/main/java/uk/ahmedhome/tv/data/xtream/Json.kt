package uk.ahmedhome.tv.data.xtream

import com.squareup.moshi.JsonReader

/** Tolerant readers: Xtream panels mix strings, numbers, booleans and nulls for the same field. */
internal fun JsonReader.readScalar(): String? = when (peek()) {
    JsonReader.Token.NULL -> nextNull<Any>().let { null }
    JsonReader.Token.STRING -> nextString()
    JsonReader.Token.NUMBER -> nextString()
    JsonReader.Token.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

internal fun String?.cleanText(): String? = this?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
internal fun String?.asLong(): Long? = this?.trim()?.toDoubleOrNull()?.toLong()
internal fun String?.asInt(): Int? = this?.trim()?.toDoubleOrNull()?.toInt()
internal fun String?.asDouble(): Double? = this?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() }
internal fun String?.asBool(): Boolean = when (this?.trim()?.lowercase()) { "1", "true", "yes" -> true; else -> false }

/** Reads an object into a flat map of scalar fields; nested values are skipped unless [nested] handles them. */
internal inline fun JsonReader.readFlatObject(nested: (name: String, reader: JsonReader) -> Boolean = { _, _ -> false }): Map<String, String?> {
    val map = HashMap<String, String?>(24)
    beginObject()
    while (hasNext()) {
        val name = nextName()
        val t = peek()
        if (t == JsonReader.Token.BEGIN_OBJECT || t == JsonReader.Token.BEGIN_ARRAY) {
            if (!nested(name, this)) skipValue()
        } else {
            map[name] = readScalar()
        }
    }
    endObject()
    return map
}
