[android-components](../../index.md) / [mozilla.components.service.fxa](../index.md) / [SyncEngine](./index.md)

# SyncEngine

`enum class SyncEngine` [(source)](https://github.com/mozilla-mobile/android-components/blob/master/components/service/firefox-accounts/src/main/java/mozilla/components/service/fxa/Config.kt#L53)

Describes possible sync engines that device can support.

### Enum Values

| Name | Summary |
|---|---|
| [HISTORY](-h-i-s-t-o-r-y.md) |  |
| [BOOKMARKS](-b-o-o-k-m-a-r-k-s.md) |  |
| [PASSWORDS](-p-a-s-s-w-o-r-d-s.md) |  |

### Properties

| Name | Summary |
|---|---|
| [nativeName](native-name.md) | `val nativeName: `[`String`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/-string/index.html)<br>Internally, Rust SyncManager represents engines as strings. Forward-compatibility with new engines is one of the reasons for this. E.g. during any sync, an engine may appear that we do not know about. At the public API level, we expose a concrete [SyncEngine](./index.md) type to allow for more robust integrations. We do not expose "unknown" engines via our public API, but do handle them internally (by persisting their enabled/disabled status). |
