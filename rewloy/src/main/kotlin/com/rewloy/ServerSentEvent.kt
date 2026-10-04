package com.rewloy

import com.rewloy.json.JsonValue

/** One event of a server-sent event stream (`text/event-stream`). */
public class ServerSentEvent(
    /** The event's type; `message` when the stream gave none. The API's live feed uses `event`, and `hello`-style notices. */
    public val event: String,
    /** The event's data, with its lines joined by `\n`. For the API's streams it is JSON: see [json]. */
    public val data: String,
    /** The stream's last event ID as of this event (`""` when it has none). */
    public val id: String,
) {
    /** The data parsed as JSON. @throws com.rewloy.json.JsonParseException when it is not JSON. */
    public fun json(): JsonValue = JsonValue.parse(data)

    override fun toString(): String = "$event: $data"
}
