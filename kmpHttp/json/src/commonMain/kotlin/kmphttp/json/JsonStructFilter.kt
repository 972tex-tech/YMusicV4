package kmphttp.json

/**
 * Filter for selectively parsing JSON structures.
 * Allows skipping keys/indices to save parsing time and resources.
 */
interface JsonStructFilter {
    /** Whether to include this key when parsing a JsonObject. */
    fun include(key: String): Boolean

    /** Whether to include this index when parsing a JsonArray. */
    fun include(index: Int): Boolean

    /** Get nested filter for a specific key. For array indices, use index.toString(). */
    fun nested(key: String): JsonStructFilter?

    companion object {
        /** Include all keys/indices by default. Use builder to exclude specific ones. */
        inline fun includeAll(block: FilterBuilder.() -> Unit = {}): JsonStructFilter {
            return FilterBuilder(FilterMode.INCLUDE).apply(block).build()
        }

        /** Exclude all keys/indices by default. Use builder to include specific ones. */
        inline fun excludeAll(block: FilterBuilder.() -> Unit = {}): JsonStructFilter {
            return FilterBuilder(FilterMode.EXCLUDE).apply(block).build()
        }

        /** Filter that includes everything without any filtering. */
        val INCLUDE_ALL: JsonStructFilter = object : JsonStructFilter {
            override fun include(key: String) = true
            override fun include(index: Int) = true
            override fun nested(key: String): JsonStructFilter? = null
        }

        /** Filter that excludes everything. */
        val EXCLUDE_ALL: JsonStructFilter = object : JsonStructFilter {
            override fun include(key: String) = false
            override fun include(index: Int) = false
            override fun nested(key: String): JsonStructFilter? = null
        }
    }
}

enum class FilterMode { INCLUDE, EXCLUDE }

/**
 * Builder for creating JsonStructFilter with either include or exclude mode.
 *
 * ## Pattern Matching Behavior
 * For performance, first matching pattern wins and follows down the tree.
 *
 * Example - this does NOT work as expected:
 * ```
 * nested("*​/a", rule1) // ignore the space
 * nested("*​/b", rule2)
 * ```
 * Path `root/b` will NOT match rule2, because `*` matches "root" first,
 * then looks for "a" but finds "b", so no rule applies.
 *
 * Correct approach - nest the rules:
 * ```
 * nested("*", includeAll {
 *     nested("a", rule1)
 *     nested("b", rule2)
 * })
 * ```
 */
class FilterBuilder(@PublishedApi internal val mode: FilterMode) {
    private val keys = mutableSetOf<String>()
    private val indices = mutableSetOf<Int>()
    private var keyPredicate: ((String) -> Boolean)? = null
    private var indexPredicate: ((Int) -> Boolean)? = null
    // Ordered list of nested rules - first match wins
    internal val nestedRules = mutableListOf<NestedRule>()

    internal sealed class NestedRule {
        abstract fun getFilter(key: String): JsonStructFilter?

        data class ExactKey(val key: String, val filter: JsonStructFilter) : NestedRule() {
            override fun getFilter(key: String) = if (this.key == key) filter else null
        }

        class PathPattern(val segments: List<PathSegment>, val filter: JsonStructFilter) : NestedRule() {
            override fun getFilter(key: String): JsonStructFilter? {
                if (!matchesKey(segments, key)) return null
                val remaining = advanceSegments(segments)
                return if (remaining == null) filter else PathFilterWrapper(remaining, filter)
            }
        }
    }

    /**
     * Except specific key from the default mode.
     * - includeAll { except("password") } → include all except "password"
     * - excludeAll { except("name") } → exclude all except "name" (only include "name")
     */
    fun except(key: String) {
        keys.add(key)
    }

    /** Except keys matching predicate. */
    fun except(predicate: (String) -> Boolean) {
        val existing = keyPredicate
        keyPredicate = if (existing != null) {
            { k -> existing(k) || predicate(k) }
        } else {
            predicate
        }
    }

    /** Except specific index from the default mode. */
    fun exceptIndex(index: Int) {
        indices.add(index)
    }

    /** Except indices matching predicate. */
    fun exceptIndex(predicate: (Int) -> Boolean) {
        val existing = indexPredicate
        indexPredicate = if (existing != null) {
            { i -> existing(i) || predicate(i) }
        } else {
            predicate
        }
    }

    /** Define a nested filter for a key/path pattern. For array indices, use index.toString(). */
    fun nested(key: String, filter: JsonStructFilter) {
        if (key.contains("*") || key.contains("/")) {
            nestedRules.add(NestedRule.PathPattern(parseSegments(key), filter))
        } else {
            nestedRules.add(NestedRule.ExactKey(key, filter))
        }
    }

    fun build(): JsonStructFilter = StructFilter(
        mode = mode,
        keys = keys.toSet(),
        indices = indices.toSet(),
        keyPredicate = keyPredicate,
        indexPredicate = indexPredicate,
        nestedRules = nestedRules.toList()
    )
}

/** Path segment for pattern matching. */
internal sealed class PathSegment {
    data class Exact(val key: String) : PathSegment()
    data object AnyKey : PathSegment()      // *
    data object AnyDepth : PathSegment()    // **
}

/** Parse path pattern into segments. */
internal fun parseSegments(pattern: String): List<PathSegment> {
    return pattern.split("/").filter { it.isNotEmpty() }.map { part ->
        when (part) {
            "**" -> PathSegment.AnyDepth
            "*" -> PathSegment.AnyKey
            else -> PathSegment.Exact(part)
        }
    }
}

/** Check if first segment matches the key. For array indices, use index.toString(). */
internal fun matchesKey(segments: List<PathSegment>, key: String): Boolean {
    if (segments.isEmpty()) return false
    return when (val first = segments.first()) {
        is PathSegment.Exact -> first.key == key
        PathSegment.AnyKey -> true
        PathSegment.AnyDepth -> true
    }
}

/**
 * Advance segments after matching.
 * Returns null if terminal (last segment matched).
 * Returns remaining segments otherwise.
 * For **, returns segments that continue matching.
 */
internal fun advanceSegments(segments: List<PathSegment>): List<PathSegment>? {
    if (segments.isEmpty()) return null

    return when (segments.first()) {
        is PathSegment.Exact, PathSegment.AnyKey -> {
            if (segments.size == 1) null else segments.drop(1)
        }
        PathSegment.AnyDepth -> {
            // ** matches zero or more - keep ** in the list
            segments // Stay at ** to continue matching
        }
    }
}

// Single implementation class with mode
private class StructFilter(
    private val mode: FilterMode,
    private val keys: Set<String>,
    private val indices: Set<Int>,
    private val keyPredicate: ((String) -> Boolean)?,
    private val indexPredicate: ((Int) -> Boolean)?,
    private val nestedRules: List<FilterBuilder.NestedRule>
) : JsonStructFilter {

    override fun include(key: String): Boolean {
        val matched = key in keys || keyPredicate?.invoke(key) == true
        return when (mode) {
            FilterMode.INCLUDE -> !matched // Include all except matched
            FilterMode.EXCLUDE -> matched // Exclude all except matched
        }
    }

    override fun include(index: Int): Boolean {
        val matched = index in indices || indexPredicate?.invoke(index) == true
        return when (mode) {
            FilterMode.INCLUDE -> !matched
            FilterMode.EXCLUDE -> matched
        }
    }

    override fun nested(key: String): JsonStructFilter? {
        // First match wins
        for (rule in nestedRules) {
            rule.getFilter(key)?.let { return it }
        }
        return null
    }
}

/** Wrapper that applies path matching as nested filter. */
private class PathFilterWrapper(
    private val segments: List<PathSegment>,
    private val terminalFilter: JsonStructFilter,
    // Keep full pattern with ** for backtracking
    private val fullPatternWithAnyDepth: List<PathSegment>? = null
) : JsonStructFilter {

    // Terminal ** means apply terminalFilter at every level
    private val isTerminalAnyDepth = segments.size == 1 && segments.first() == PathSegment.AnyDepth

    override fun include(key: String): Boolean {
        // Terminal ** delegates to terminalFilter
        if (isTerminalAnyDepth) return terminalFilter.include(key)
        // Include if could potentially match the path OR the full pattern with **
        if (matchesKey(segments, key)) return true
        if (fullPatternWithAnyDepth != null && matchesKey(fullPatternWithAnyDepth, key)) return true
        return false
    }

    override fun include(index: Int): Boolean {
        // Terminal ** delegates to terminalFilter
        if (isTerminalAnyDepth) return terminalFilter.include(index)
        // * and ** both match indices (treat index as string)
        val key = index.toString()
        if (matchesKey(segments, key)) return true
        if (fullPatternWithAnyDepth != null && matchesKey(fullPatternWithAnyDepth, key)) return true
        return false
    }

    override fun nested(key: String): JsonStructFilter? {
        // Terminal ** - apply same filter at all depths
        if (isTerminalAnyDepth) return this

        // Handle ** specially - try to match next segment but keep full pattern active
        if (segments.first() == PathSegment.AnyDepth && segments.size > 1) {
            val nextSegment = segments[1]
            val nextMatches = when (nextSegment) {
                is PathSegment.Exact -> nextSegment.key == key
                PathSegment.AnyKey -> true
                PathSegment.AnyDepth -> true
            }

            if (nextMatches) {
                // Matched segment after ** - advance but keep full pattern for backtracking
                val remaining = if (segments.size == 2) null else segments.drop(2)
                return if (remaining == null) {
                    terminalFilter
                } else {
                    // Keep full pattern (with **) for potential backtracking
                    PathFilterWrapper(remaining, terminalFilter, fullPatternWithAnyDepth = segments)
                }
            }
            // Didn't match next segment, stay at **
            return this
        }

        // If current segment doesn't match but we have a full pattern with **, try that
        if (!matchesKey(segments, key)) {
            if (fullPatternWithAnyDepth != null) {
                // Fall back to full pattern with **
                return PathFilterWrapper(fullPatternWithAnyDepth, terminalFilter).nested(key)
            }
            return null
        }

        val remaining = advanceSegments(segments)
        return if (remaining == null) {
            terminalFilter
        } else {
            PathFilterWrapper(remaining, terminalFilter, fullPatternWithAnyDepth)
        }
    }
}
