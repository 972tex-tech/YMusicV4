# :projectCommon

Small helpers shared by the app's modules, grouped by package. Pure Kotlin, no platform code.

- `projectcommon.json` — `JsonObject` field readers over kotlinx.serialization. Required readers
  (`string`, `int`, `long`, `bool`) throw when the key is missing or the wrong type; `*OrNull`
  readers return null instead. Numbers and booleans parse the primitive's content, so a JSON number
  and a numeric string both read. `objects(key)` is the JSON-object elements of an array field.

Library-shaped code with its own identity (`:kmpCommon`, `:kmpHttp`) stays out of this module.
