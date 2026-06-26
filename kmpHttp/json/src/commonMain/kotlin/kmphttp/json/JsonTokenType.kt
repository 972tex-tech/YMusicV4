package kmphttp.json

/**
 * Represents the type of a JSON value at the current position.
 */
enum class JsonTokenType {
    OBJECT,
    ARRAY,
    STRING,
    UNQUOTED_LITERAL // null/boolean/number
}
