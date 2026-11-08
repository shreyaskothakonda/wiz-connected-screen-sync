package com.shreyas.wizsync.tv.wiz

import com.shreyas.wizsync.tv.sync.BulbCommand
import org.json.JSONException
import org.json.JSONObject

/** JSON payloads for the WiZ local UDP protocol; mirrors app/bulb.py. */
object WizJson {
    fun setPilot(cmd: BulbCommand): String = JSONObject()
        .put("method", "setPilot")
        .put(
            "params", JSONObject()
                .put("state", true)
                .put("r", cmd.r)
                .put("g", cmd.g)
                .put("b", cmd.b)
                .put("dimming", cmd.dimming.coerceIn(10, 100)),
        )
        .toString()

    fun setState(on: Boolean): String = JSONObject()
        .put("method", "setState")
        .put("params", JSONObject().put("state", on))
        .toString()

    val getPilot: String = JSONObject().put("method", "getPilot").put("params", JSONObject()).toString()

    /** getPilot carrying a request [id]; the bulb echoes it in its reply so callers can match replies to requests. */
    fun getPilot(id: Int): String = JSONObject().put("id", id).put("method", "getPilot").put("params", JSONObject()).toString()

    /** The request id echoed in a reply, or null when absent or the reply is malformed. */
    fun parseId(reply: String): Int? = try {
        JSONObject(reply).takeIf { it.has("id") }?.optInt("id")
    } catch (e: JSONException) {
        null
    }

    /** The bulb's MAC from a getPilot reply, or null when the reply is malformed or has no `result.mac`. */
    fun parseMac(reply: String): String? = try {
        JSONObject(reply).optJSONObject("result")?.optString("mac", "")?.takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }
}
