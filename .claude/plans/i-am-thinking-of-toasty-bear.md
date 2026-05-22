# Plan: Restructure into `content` and `proxy` domains

## Context

The current `:contracts` module mixes provider and proxy concerns. All navigation lives in `:app`. The goal is two clean feature domains — `content` and `proxy` — each owning their contracts, UI, and provider implementations. `:app` becomes a thin shell that wires them together.

---

## Target Structure

```
content/
  contract/         com.opentune.content.contract.*
  ui/               com.opentune.content.ui.*
  providers/
    emby/
    smb/
    js/

proxy/
  contract/         com.opentune.proxy.contract.*
  ui/               com.opentune.proxy.ui.*
  providers/
    http/

core/
  form/             com.opentune.core.form.*
                    FormFieldSpec, FormFieldKind, FormValidationResult
                    + FormRenderer composable

app/                thin shell — wires deps, composes NavHost
storage/            unchanged
server/             unchanged
player/             unchanged
```

---

## Navigation Pattern

Feature modules expose `NavGraphBuilder` extension functions. `:app` composes them:

```kotlin
// content/ui
fun NavGraphBuilder.contentRoutes(onNavigate: (String) -> Unit) {
    composable(Routes.BROWSE) { BrowseScreen(...) }
    composable(Routes.DETAIL) { DetailScreen(...) }
    composable(Routes.SEARCH) { SearchScreen(...) }
    composable(Routes.PLAYER) { PlayerShell(...) }
    composable(Routes.PROVIDER_ADD) { ProviderFormRoute(...) }
    composable(Routes.PROVIDER_EDIT) { ProviderFormRoute(...) }
}

// proxy/ui
fun NavGraphBuilder.proxyRoutes(onNavigate: (String) -> Unit) {
    composable(Routes.PROXY_ADD) { ProxyFormRoute(...) }
    composable(Routes.PROXY_EDIT) { ProxyFormRoute(...) }
}

// app — OpenTuneNavHost
NavHost(navController = nav, startDestination = Routes.HOME) {
    composable(Routes.HOME) { HomeRoute(...) }  // stays in :app
    contentRoutes(onNavigate = { nav.navigate(it) })
    proxyRoutes(onNavigate = { nav.navigate(it) })
}
```

Route string constants move to a shared location (e.g. `content/contract` and `proxy/contract`, or a minimal `:core:routes` if both domains need to reference each other's routes).

---

## Dependency Wiring

Modules pull from existing Holder singletons (`StreamRegistrarHolder`, `PlatformInfoHolder`, etc.). New holders added as needed. No new DI framework.

---

## Package Renames

| Old | New |
|---|---|
| `com.opentune.provider.ProviderFieldSpec` | `com.opentune.core.form.FormFieldSpec` |
| `com.opentune.provider.ProviderFieldKind` | `com.opentune.core.form.FormFieldKind` |
| `com.opentune.provider.ValidationResult` | split: `com.opentune.core.form.FormValidationResult` (sealed Success/Error, no domain fields) + `com.opentune.content.contract.EndpointValidationResult` (Success with hash/name/fields) |
| `com.opentune.provider.*` (rest) | `com.opentune.content.contract.*` |
| `com.opentune.proxy.*` | `com.opentune.proxy.contract.*` |

---

## Implementation Phases

### Phase 1 — Create `:core:form`
- New module `:core:form`
- Move from `contracts/provider/ProviderContracts.kt`:
  - `ProviderFieldSpec` → `FormFieldSpec`
  - `ProviderFieldKind` → `FormFieldKind`
  - `ValidationResult` → `FormValidationResult`
- Move `ProviderFormRoute.kt` composable (the field renderer portion — `OutlinedTextField` loop, `ProxySelector`) into `:core:form` as a reusable `FormRenderer` composable
- Both `:content:contract` and `:proxy:contract` depend on `:core:form`
- `OpenTuneProvider.getFieldsSpec()` returns `List<FormFieldSpec>`
- `ProxyProvider.getFieldsSpec()` returns `List<FormFieldSpec>`

### Phase 2 — Split `:contracts`
- Create `:content:contract` module, move `contracts/provider/` into it (excluding `FormFieldSpec/Kind/ValidationResult` now in `:core:form`), rename packages to `com.opentune.content.contract`
- Create `:proxy:contract` module, move `contracts/proxy/` into it, rename packages to `com.opentune.proxy.contract`
- Delete `:contracts` module
- Update `settings.gradle` and all `build.gradle` dependency declarations

### Phase 2 — Nest provider submodules
- Move `:providers:emby` → `:content:providers:emby`
- Move `:providers:smb` → `:content:providers:smb`
- Move `:providers:js` → `:content:providers:js`
- Move `:proxy:http` → `:proxy:providers:http`
- Update `META-INF/services/` files and `settings.gradle`

### Phase 3 — Extract `:content:ui`
- Create `:content:ui` module
- Move `BrowseScreen`, `DetailScreen`, `SearchScreen`, `PlayerShell`, `ProviderFormRoute` from `:app` into it
- Expose `fun NavGraphBuilder.contentRoutes(...)` extension

### Phase 4 — Extract `:proxy:ui`
- Create `:proxy:ui` module
- Move `ProxyFormRoute` from `:app` into it
- Expose `fun NavGraphBuilder.proxyRoutes(...)` extension

### Phase 5 — Thin `:app`
- `OpenTuneNavHost` calls `contentRoutes(...)` and `proxyRoutes(...)`
- `OpenTuneApplication.onCreate()` removes direct screen imports
- `:app` `build.gradle` depends on `:content:ui` and `:proxy:ui` instead of individual screens

---

## Critical Files

- [settings.gradle](settings.gradle)
- [contracts/build.gradle](contracts/build.gradle)
- [contracts/src/main/java/com/opentune/provider/ProviderContracts.kt](contracts/src/main/java/com/opentune/provider/ProviderContracts.kt)
- [app/src/main/java/com/opentune/app/ui/config/ProviderFormRoute.kt](app/src/main/java/com/opentune/app/ui/config/ProviderFormRoute.kt)
- [app/src/main/java/com/opentune/app/OpenTuneApplication.kt](app/src/main/java/com/opentune/app/OpenTuneApplication.kt)
- [app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt](app/src/main/java/com/opentune/app/navigation/OpenTuneNavHost.kt)
- [app/build.gradle](app/build.gradle)

---

## Verification

1. `./gradlew assembleDebug` — clean compile
2. `./gradlew test` — all unit tests pass
3. Manual: launch app → add endpoint → browse → play file (content module wiring)
4. Manual: add proxy → confirm routing (proxy module wiring)
