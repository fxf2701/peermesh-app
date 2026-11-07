#!/usr/bin/env kotlin
// Read-only reconstruction utility for docs/diagnostics/query_dtn_inv_have_160.sql.
// Requires an SQLite JDBC driver on the script classpath. Example:
// kotlinc -script reconstruct_dtn_inv_have.kt -- /tmp/device-a-peer_mesh.db out.csv
// This script deliberately does not open the DB in read-write mode or execute mutations.

import java.io.File
import java.sql.DriverManager

val dbFile = args.getOrNull(0) ?: error("Usage: reconstruct_dtn_inv_have.kt <peer_mesh.db> <out.csv>")
val output = File(args.getOrNull(1) ?: error("Missing output CSV path"))
val gid = "H-MfE3z9pVj2M4FIOt5JLxLTguKOFp5q1kOQ-Mq0k4Q"
val now = 1785566919L // 2026-08-01 13:48:39 +07

fun csv(value: Any?): String = "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
fun priority(type: String) = when {
    type.startsWith("SYS.ALERT") -> 0
    type.startsWith("SYS.SAFETY") -> 1
    type.startsWith("MAP.") -> 2
    type.startsWith("CHAT.") -> 3
    else -> 4
}
fun category(type: String) = when {
    type.startsWith("CHAT.") -> "CHAT"
    type.startsWith("MAP.") && (type.endsWith(".ADD") || type.endsWith(".UPD") || type.endsWith(".DEL")) -> "MAP_CANONICAL"
    type.startsWith("MAP.") -> "MAP_ACTION"
    type.startsWith("SYS.") -> "SYSTEM"
    else -> "OTHER"
}

Class.forName("org.sqlite.JDBC")
DriverManager.getConnection("jdbc:sqlite:file:${File(dbFile).absolutePath}?mode=ro").use { connection ->
    connection.prepareStatement(
        """
        SELECT event_id,type,source,created_t,inserted_at,actor_did,ref_root,ref_parent,ref_target,invalid_at
        FROM events
        WHERE gid=? AND invalid_at IS NULL AND inserted_at<=? AND type<>'SYS.ACK'
          AND NOT(type IN ('CHAT.MSG','CHAT.REPLY','CHAT.EDIT','CHAT.DEL') AND created_t BETWEEN ? AND ?)
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, gid)
        statement.setLong(2, now)
        statement.setLong(3, now - 3600)
        statement.setLong(4, now)
        val rows = mutableListOf<List<Any?>>()
        statement.executeQuery().use { result ->
            while (result.next()) {
                val type = result.getString("type")
                rows += listOf(
                    result.getString("event_id"), type, result.getString("source"),
                    result.getLong("created_t"), result.getLong("inserted_at"),
                    result.getString("actor_did"), result.getString("ref_root"),
                    result.getString("ref_parent"), result.getString("ref_target"),
                    result.getObject("invalid_at"), priority(type), category(type),
                )
            }
        }
        val ordered = rows.sortedWith(compareBy<List<Any?>> { it[10] as Int }.thenBy { it[3] as Long }.thenBy { it[0] as String })
        require(ordered.size == 160) { "Expected 160 NORMAL-tier candidates; got ${ordered.size}" }
        output.printWriter().use { writer ->
            writer.println("ordinal,event_id,type,source,created_t,inserted_at,age_seconds_at_build,actor_did,ref_root,ref_parent,ref_target,invalid_at,inventory_tier,priority,suppressed_for_peer,category")
            ordered.forEachIndexed { index, row ->
                writer.println(listOf(index + 1, row[0], row[1], row[2], row[3], row[4], now - (row[3] as Long), row[5], row[6], row[7], row[8], row[9], "normal", row[10], 0, row[11]).joinToString(",", ::csv))
            }
        }
    }
}
