package app.peermesh.core.transport

object EnvelopeSourceTagger {
    private const val KEY = "src"

    fun tagMesh(json: String): String {
        return tag(json, "mesh")
    }

    fun tagServer(json: String): String {
        return tag(json, "server")
    }

    fun hasServerTag(json: String): Boolean {
        return json.contains("\"$KEY\":\"server\"")
    }

    private fun tag(json: String, value: String): String {
        if (json.contains("\"$KEY\":\"")) return json
        return if (json.trim().endsWith("}")) {
            json.trim().dropLast(1) + ",\"$KEY\":\"$value\"}"
        } else {
            json
        }
    }
}
