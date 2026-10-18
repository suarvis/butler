package com.suarvis.butler.core.domain.generic

/**
 * Offset/limit window used by gateways. Primary adaptors translate their own paging parameters
 * (page number and size) into this, so storage never sees presentation concepts.
 */
data class PageRequest(val offset: Int, val limit: Int) {
    init {
        ensure(offset >= 0) { "Page offset must not be negative" }
        ensure(limit in 1..MAX_LIMIT) { "Page limit must be between 1 and $MAX_LIMIT" }
    }

    companion object {
        const val MAX_LIMIT = 500
        const val DEFAULT_LIMIT = 50

        fun firstPage(limit: Int = DEFAULT_LIMIT): PageRequest = PageRequest(offset = 0, limit = limit)
    }
}

data class Page<out T>(val items: List<T>, val totalItems: Long, val request: PageRequest) {
    init {
        ensure(totalItems >= 0) { "Total items must not be negative" }
        ensure(items.size <= request.limit) { "A page cannot hold more items than its limit" }
    }

    val hasNext: Boolean
        get() = request.offset.toLong() + items.size < totalItems

    fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), totalItems, request)

    companion object {
        fun <T> empty(request: PageRequest): Page<T> = Page(emptyList(), 0, request)
    }
}
