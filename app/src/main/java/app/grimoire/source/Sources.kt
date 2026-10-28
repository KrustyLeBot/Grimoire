package app.grimoire.source

import app.grimoire.data.Store

/** The [Source] objects of the configured sources, rebuilt when a source's settings change. */
class Sources(private val store: Store, private val http: Http) {
    private val built = mutableMapOf<SourceConfig, Source>()

    @Synchronized
    fun get(id: String): Source? {
        val config = store.data.value.sources.firstOrNull { it.id == id } ?: return null
        built[config]?.let { return it }
        val source = Catalog.type(config.type)?.create?.invoke(config, http) ?: return null
        built.keys.removeAll { it.id == id }
        built[config] = source
        return source
    }

    fun enabled(): List<Source> = store.data.value.sources.filter { it.enabled }.mapNotNull { get(it.id) }
}
