# JSON handling in the JS provider

JS providers hand JSON strings across the QuickJS boundary; Kotlin decodes them
into contract types. Two mechanisms coexist: `kotlinx.serialization` `@Serializable`
DTOs + `decodeFromJsonElement`, and manual `jsonObject`/`jsonPrimitive` extraction.
The split is intentional — pick by the rule below, not by habit.

## The rule

| Shape source | Approach |
|---|---|
| **Fixed schema, mirrored in TS** (`EntryInfo`, `EntryList`, `EntryEmission`, `PlaybackSource`, test/QR responses) | `@Serializable` DTO + `decodeFromJsonElement`. The TS type is the source of truth; field names match exactly, so hand-mirroring is boilerplate that drifts. |
| **Runtime-determined by JVM reflection** (JarLoader: target types come from `Method.parameterTypes`, not a Kotlin schema) | Manual, unavoidable. `jsonToAny`/`convertToParam` cannot be replaced by a DTO. |
| **Raw forward to a downstream decoder** (`host.notification.send` result) | Forward the `JsonObject` as-is. This is *not* a parse decision — it is a deferral. Whether the downstream decoder uses a DTO or manual parsing is decided there, by the same rule. |
| **1–2 ad-hoc fields inside a `when` branch** (`host.fs`/`host.crypto`/`host.log`/`host.timer`) | Manual is lower-boilerplate than a one-field DTO. Don't spin up a data class for a single `args["path"]?.jsonPrimitive?.content`. |

## Applying it

- The codec (`EntryInfoCodec`) is the home for DTO-backed decoding of contract
  types. Screens and ViewModels call it; they do not re-implement parsing.
- DTOs for method-local shapes (e.g. JS-client responses) live as
  `private @Serializable data class`es in the class that owns the method,
  mirroring the TS type one field at a time. Strict-by-design: a malformed
  element in a list is dropped (`mapNotNull`); a malformed standalone response
  throws and surfaces the provider bug.
- The `kotlinx.serialization.json.x` import block staying long is not a smell
  on its own — `buildJsonObject`/`put`/`encodeToJsonElement` are arg-*building*,
  and reflection-driven conversion needs the `JsonElement` family. Only treat
  imports as removable when the *parse* paths they served move behind a DTO.
