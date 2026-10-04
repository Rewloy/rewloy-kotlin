package com.rewloy

/**
 * Walks a paged list item by item, asking for the next page while the answer's `meta` says there is one: it
 * stops after a page that is short, empty or the last by `total`. Nothing is requested until the iteration starts,
 * and each call of `iterator()` starts a new walk from the first page asked for.
 */
internal class PagedIterable<T>(
    private val startPage: Long,
    private val fetch: (Long) -> RewloyResponse<List<T>>,
) : Iterable<T> {
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var page = startPage
        private var items: List<T> = emptyList()
        private var index = 0
        private var last = false

        override fun hasNext(): Boolean {
            while (index >= items.size) {
                if (last) return false
                load()
            }
            return true
        }

        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            return items[index++]
        }

        private fun load() {
            val response = fetch(page)
            items = response.data
            index = 0
            val meta = response.meta
            if (meta == null || items.isEmpty() || items.size < meta.pageSize || meta.page * meta.pageSize >= meta.total) {
                last = true
            } else {
                page = meta.page + 1
            }
        }
    }
}
