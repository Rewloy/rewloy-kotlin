package com.rewloy

/**
 * Reads the server-sent event wire format (the HTML standard's "event stream interpretation") from text that
 * arrives in pieces of any size: a line, a CR LF pair or a multi-byte character may be split between pieces.
 * Public so that it can be tested and reused; a stream uses it for you.
 */
public class SseParser @JvmOverloads constructor(lastEventId: String = "") {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var eventType = ""
    private var id: String = lastEventId
    private var afterCr = false
    private var started = false
    private var hasData = false

    /** The reconnection delay the server asked for with `retry:`, in milliseconds; `null` until it does. */
    public var retryMilliseconds: Long? = null
        private set

    /** The last event ID the stream gave, to send as `Last-Event-ID` on a reconnection. */
    public var lastEventId: String = lastEventId
        private set

    /** Feeds a piece of text and returns the events it completed. */
    public fun push(text: String): List<ServerSentEvent> {
        val events = ArrayList<ServerSentEvent>(2)
        var i = 0
        if (!started && text.isNotEmpty()) {
            started = true
            if (text[0] == '﻿') i = 1
        }
        // A CR ended the previous piece: an LF right after it belongs to the same line ending.
        if (afterCr && i < text.length) {
            if (text[i] == '\n') i++
            afterCr = false
        }
        while (i < text.length) {
            var j = i
            while (j < text.length && text[j] != '\n' && text[j] != '\r') j++
            if (j == text.length) {
                line.append(text, i, text.length)
                break
            }
            line.append(text, i, j)
            val complete = line.toString()
            line.setLength(0)
            if (text[j] == '\r') {
                if (j + 1 < text.length) {
                    if (text[j + 1] == '\n') j++
                } else {
                    afterCr = true
                }
            }
            i = j + 1
            take(complete, events)
        }
        return events
    }

    /** Ends the stream: an event that was not completed by a blank line is dropped, as the standard says. */
    public fun end() {
        line.setLength(0)
        data.setLength(0)
        hasData = false
        eventType = ""
        afterCr = false
    }

    private fun take(line: String, events: MutableList<ServerSentEvent>) {
        if (line.isEmpty()) {
            lastEventId = id
            if (!hasData) {
                eventType = ""
                return
            }
            var text = data.toString()
            if (text.endsWith("\n")) text = text.substring(0, text.length - 1)
            events.add(ServerSentEvent(if (eventType.isNotEmpty()) eventType else "message", text, lastEventId))
            data.setLength(0)
            hasData = false
            eventType = ""
            return
        }
        if (line[0] == ':') return
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventType = value
            "data" -> {
                data.append(value).append('\n')
                hasData = true
            }
            "id" -> if (value.indexOf('\u0000') < 0) id = value
            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) value.toLongOrNull()?.let { retryMilliseconds = it }
        }
    }
}
