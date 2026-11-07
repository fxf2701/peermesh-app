package app.peermesh.core.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalJsonTest {
    @Test
    fun canonicalize_sortsObjectKeys() {
        val element = buildJsonObject {
            put("b", JsonPrimitive(1))
            put("a", JsonPrimitive(2))
            put("c", JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y"))))
        }

        val canonical = CanonicalJson.canonicalize(element)
        assertEquals("{\"a\":2,\"b\":1,\"c\":[\"x\",\"y\"]}", canonical)
    }
}
