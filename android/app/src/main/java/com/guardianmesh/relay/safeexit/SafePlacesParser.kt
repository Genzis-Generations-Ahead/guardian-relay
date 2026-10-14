package com.guardianmesh.relay.safeexit

import org.json.JSONObject

object SafePlacesParser {
    /** Accepts {"places":[{id,name,type,lat,lng,open_now,hours_text}]}. Bad entries are skipped, not fatal. */
    fun parse(o: JSONObject): List<SafePlace> {
        val arr = o.optJSONArray("places") ?: return emptyList()
        val out = ArrayList<SafePlace>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            try {
                out += SafePlace(
                    id = p.optString("id", "place-$i"),
                    name = p.optString("name", "Safe place"),
                    type = p.optString("type", "unknown"),
                    lat = p.getDouble("lat"),
                    lng = p.getDouble("lng"),
                    openNow = if (!p.has("open_now") || p.isNull("open_now")) null else p.getBoolean("open_now"),
                    hoursText = p.optString("hours_text", "").ifEmpty { null }
                )
            } catch (_: Throwable) { /* skip malformed entry */ }
        }
        return out
    }
}
