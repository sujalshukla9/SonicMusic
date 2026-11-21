package com.sonicmusic.app.data.remote.source

import android.content.Context
import com.sonicmusic.app.domain.model.HomeContent
import com.sonicmusic.app.domain.model.HomeContinuation
import com.sonicmusic.app.domain.model.HomeShelf
import com.sonicmusic.app.domain.model.HomeShelfItem
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal class YtmHomeCatalog(context: Context) {
    private val catalog by lazy {
        context.assets.open("ytm-home-functions.json").bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun function(name: String): JSONObject = catalog.getJSONObject("functions").getJSONObject(name)

    data class RequestDefinition(val endpoint: String, val body: Map<String, String>) {
        fun applyBody(target: JSONObject) {
            body.forEach { (key, value) -> target.put(key, value) }
        }
    }

    fun bind(name: String, bindings: Map<String, String> = emptyMap()): RequestDefinition {
        val function = function(name)
        fun resolve(fields: JSONObject): Map<String, String> = fields.keys().asSequence().associateWith { key ->
            PLACEHOLDER.replace(fields.getString(key)) { bindings.getValue(it.groupValues[1]) }
        }
        val endpoint = catalog.getString("baseUrl") + function.getString("endpoint")
        val url = endpoint.toHttpUrl().newBuilder()
        function.optJSONObject("query")?.let(::resolve)?.forEach { (key, value) ->
            url.addEncodedQueryParameter(key, value)
        }
        return RequestDefinition(url.build().toString(), resolve(function.getJSONObject("body")))
    }

    fun request(
        name: String,
        language: String,
        region: String,
        acceptLanguage: String,
        bindings: Map<String, String> = emptyMap(),
        customize: JSONObject.() -> Unit = {}
    ): Request {
        val definition = bind(name, bindings)
        val body = Innertube.webRemixBody(language, region) {
            definition.applyBody(this)
            customize()
        }
        return Innertube.musicPost(
            endpoint = definition.endpoint,
            body = body,
            acceptLanguage = acceptLanguage
        )
    }

    fun lookup(root: JSONObject, name: String, pathName: String): Any? {
        val path = function(name).optJSONObject("paths")?.optString(pathName).orEmpty()
        if (path.isEmpty()) return null
        return PATH_TOKEN.findAll(path).fold(root as Any?) { node, match ->
            val key = match.groupValues[1]
            when (node) {
                is JSONObject -> node.opt(key)
                is JSONArray -> match.groupValues[2].toIntOrNull()?.let { index ->
                    node.opt(if (index < 0) node.length() + index else index)
                }
                else -> null
            }
        }
    }

    fun parseFeed(
        root: JSONObject,
        text: (JSONObject?) -> String,
        pageArrays: (JSONObject) -> List<JSONArray>,
        sectionToken: (JSONObject) -> String?,
        itemToken: (Any?) -> String?,
        parseItem: (JSONObject, Boolean) -> HomeShelfItem?
    ): HomeContent {
        val contents = root.optJSONObject("contents")
        val singleColumn = contents?.optJSONObject("singleColumnBrowseResultsRenderer")
        val twoColumn = contents?.optJSONObject("twoColumnBrowseResultsRenderer")
        val sectionList = (lookup(root, "home_more", "sectionList") as? JSONObject)
            ?: sectionListFromTabs(singleColumn?.optJSONArray("tabs"), selectedOnly = true)
            ?: (lookup(root, "home", "twoColumnSectionList") as? JSONObject)
            ?: sectionListFromTabs(twoColumn?.optJSONArray("tabs"))
            ?: (lookup(root, "home", "directSectionList") as? JSONObject)
            ?: (lookup(root, "home", "sectionList") as? JSONObject)
            ?: sectionListFromTabs(singleColumn?.optJSONArray("tabs"))
        val rows = sectionList?.optJSONArray("contents")?.let { listOf(it) } ?: pageArrays(root)
        check(rows.isNotEmpty()) {
            "Unrecognized Home response fields: ${root.keys().asSequence().take(8).joinToString()}"
        }

        val shelves = linkedMapOf<String, HomeShelf>()
        val continuation = root.optJSONObject("continuationContents")
        val queryToken = sectionList?.let(sectionToken)
            ?: SHELF_CONTINUATIONS.firstNotNullOfOrNull { continuation?.optJSONObject(it)?.let(sectionToken) }
        var nextPage = queryToken?.takeIf(String::isNotBlank)?.let { HomeContinuation(it, useQueryParameters = true) }
        for (array in rows) {
            val looseItems = mutableListOf<HomeShelfItem>()
            fun flushLooseItems() {
                if (looseItems.isEmpty()) return
                val entries = looseItems.distinctBy(HomeShelfItem::key)
                val id = homeDigest(entries.first().key).take(24)
                addShelf(shelves, HomeShelf(id, "", layout = HomeShelf.Layout.List, items = entries))
                looseItems.clear()
            }
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                if (nextPage == null) {
                    nextPage = row.optJSONObject("continuationItemRenderer")?.let(itemToken)
                        ?.takeIf(String::isNotBlank)?.let { HomeContinuation(it, useQueryParameters = false) }
                }
                val looseItem = parseWrappedItem(row, parseItem)
                if (looseItem != null) {
                    looseItems.add(looseItem)
                    continue
                }
                flushLooseItems()
                val nested = row.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
                if (nested == null) {
                    parseShelf(row, text, parseItem)?.let { addShelf(shelves, it) }
                } else {
                    for (child in 0 until nested.length()) {
                        val section = nested.optJSONObject(child) ?: continue
                        parseShelf(section, text, parseItem)?.let { addShelf(shelves, it) }
                    }
                }
            }
            flushLooseItems()
        }
        return HomeContent(shelves.values.toList(), nextPage)
    }

    private fun sectionListFromTabs(tabs: JSONArray?, selectedOnly: Boolean = false): JSONObject? {
        if (tabs == null) return null
        var firstSectionList: JSONObject? = null
        for (index in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(index)?.optJSONObject("tabRenderer") ?: continue
            val list = tab.optJSONObject("content")?.optJSONObject("sectionListRenderer") ?: continue
            if (tab.optBoolean("selected")) return list
            if (!selectedOnly && firstSectionList == null) firstSectionList = list
        }
        return firstSectionList
    }

    private fun addShelf(shelves: MutableMap<String, HomeShelf>, shelf: HomeShelf) {
        val previous = shelves[shelf.id]
        if (previous == null) {
            shelves[shelf.id] = shelf
        } else if (previous.items != shelf.items) {
            val id = "${shelf.id}_${homeDigest(shelf.items.joinToString("|") { it.key }).take(20)}"
            shelves.putIfAbsent(id, shelf.copy(id = id))
        }
    }

    private fun parseShelf(
        row: JSONObject,
        text: (JSONObject?) -> String,
        parseItem: (JSONObject, Boolean) -> HomeShelfItem?
    ): HomeShelf? {
        val carousel = row.optJSONObject("musicCarouselShelfRenderer")
            ?: row.optJSONObject("musicImmersiveCarouselShelfRenderer")
        val grid = row.optJSONObject("gridRenderer") ?: row.optJSONObject("musicGridRenderer")
        val list = row.optJSONObject("musicShelfRenderer") ?: row.optJSONObject("musicPlaylistShelfRenderer")
        val card = row.optJSONObject("musicCardShelfRenderer")
        val renderer = carousel ?: grid ?: list ?: card
            ?: row.keys().asSequence().filter { it.endsWith("Renderer") }
                .mapNotNull(row::optJSONObject)
                .firstOrNull { it.optJSONArray("contents") != null || it.optJSONArray("items") != null }
            ?: return null
        val header = renderer.optJSONObject("header")
        val metadata = header?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            ?: header?.optJSONObject("gridHeaderRenderer")
            ?: header?.optJSONObject("musicCardShelfHeaderBasicRenderer")
            ?: header
        val titleData = metadata?.optJSONObject("title") ?: renderer.optJSONObject("title")
        val title = text(titleData).trim()
        val subtitle = text(metadata?.optJSONObject("strapline") ?: metadata?.optJSONObject("subtitle"))
            .trim().takeIf(String::isNotBlank)
        val children = renderer.optJSONArray("contents") ?: renderer.optJSONArray("items")
        val items = buildList {
            if (card != null) parseItem(card, false)?.let(::add)
            if (children != null) {
                for (index in 0 until children.length()) {
                    val item = children.optJSONObject(index) ?: continue
                    parseWrappedItem(item, parseItem)?.let(::add)
                }
            }
        }.distinctBy(HomeShelfItem::key)
        if (items.isEmpty()) return null

        val endpoint = titleData?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
            ?: metadata?.optJSONObject("moreContentButton")?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")
        val browse = endpoint?.optJSONObject("browseEndpoint")
        val id = homeDigest(
            listOf(
                renderer.optString("targetId"), title, subtitle.orEmpty(),
                browse?.optString("browseId").orEmpty(), browse?.optString("params").orEmpty(),
                if (title.isBlank()) items.first().key else ""
            ).joinToString("\u001f")
        ).take(24)
        val kind = shelfKind(renderer.optString("shelfId"), title)
        val layout = when {
            grid != null || kind == HomeShelf.Kind.ListenAgain -> HomeShelf.Layout.Grid
            list != null -> HomeShelf.Layout.List
            else -> HomeShelf.Layout.Carousel
        }
        return HomeShelf(id, title, subtitle, layout, items, kind)
    }

    fun shelfKind(shelfId: String, title: String): HomeShelf.Kind {
        val definitions = catalog.getJSONObject("homeShelfTypes")
        fun matches(kind: String): Boolean {
            val definition = definitions.getJSONObject(kind)
            val ids = definition.optJSONArray("shelfIds")
            if ((0 until (ids?.length() ?: 0)).any { ids?.optString(it) == shelfId }) return true
            val titles = definition.getJSONArray("titles")
            return (0 until titles.length()).any { titles.optString(it).equals(title, ignoreCase = true) }
        }
        return when {
            matches("listen_again") -> HomeShelf.Kind.ListenAgain
            matches("quick_picks") -> HomeShelf.Kind.QuickPicks
            else -> HomeShelf.Kind.Other
        }
    }

    fun parseWrappedItem(
        item: JSONObject,
        parseItem: (JSONObject, Boolean) -> HomeShelfItem?
    ): HomeShelfItem? {
        val responsive = item.optJSONObject("musicResponsiveListItemRenderer")
        val renderer = responsive ?: item.optJSONObject("musicTwoRowItemRenderer")
            ?: item.optJSONObject("musicMultiRowListItemRenderer") ?: return null
        return parseItem(renderer, responsive != null)
    }

    fun parseCollection(
        root: JSONObject,
        sectionToken: (JSONObject) -> String?,
        itemToken: (Any?) -> String?,
        parseItem: (JSONObject, Boolean) -> HomeShelfItem?
    ): Pair<List<HomeShelfItem>, HomeContinuation?> {
        val items = linkedMapOf<String, HomeShelfItem>()
        var next: HomeContinuation? = null
        var recognized = false

        fun read(node: Any?, depth: Int) {
            if (depth > 20) return
            when (node) {
                is JSONArray -> for (index in 0 until node.length()) read(node.opt(index), depth + 1)
                is JSONObject -> {
                    if (CARD_RENDERERS.any(node::has)) {
                        recognized = true
                        parseWrappedItem(node, parseItem)?.let { items.putIfAbsent(it.key, it) }
                        return
                    }
                    if (next == null) {
                        next = sectionToken(node)?.takeIf(String::isNotBlank)?.let { HomeContinuation(it, true) }
                            ?: node.optJSONObject("continuationItemRenderer")?.let(itemToken)
                                ?.takeIf(String::isNotBlank)?.let { HomeContinuation(it, false) }
                    }
                    for (key in node.keys()) {
                        if (key == "musicNotifierShelfRenderer") recognized = true
                        if (key in COLLECTION_NODES) {
                            val child = node.opt(key)
                            if (key in COLLECTION_LISTS) {
                                val rows = when (child) {
                                    is JSONArray -> child
                                    is JSONObject -> child.optJSONArray("contents") ?: child.optJSONArray("items")
                                    else -> null
                                }
                                if (rows?.length() == 0) recognized = true
                            }
                            read(child, depth + 1)
                        }
                    }
                }
            }
        }

        for (key in COLLECTION_ROOTS) read(root.opt(key), 0)
        check(recognized || next != null) { "Unrecognized YouTube Music collection response" }
        val visitor = root.optJSONObject("responseContext")?.optString("visitorData")?.takeIf(String::isNotBlank)
        return items.values.toList() to next?.copy(visitorData = visitor)
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{([a-zA-Z]+)\}""")
        val PATH_TOKEN = Regex("""([a-zA-Z][a-zA-Z0-9]*)|\[(-?\d+)\]""")
        val SHELF_CONTINUATIONS = listOf(
            "musicShelfContinuation", "musicPlaylistShelfContinuation", "gridContinuation", "musicCarouselShelfContinuation"
        )
        val CARD_RENDERERS = setOf("musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "musicMultiRowListItemRenderer")
        val COLLECTION_ROOTS = listOf(
            "contents", "continuationContents", "onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints"
        )
        val COLLECTION_LISTS = SHELF_CONTINUATIONS.toSet() + setOf(
            "sectionListRenderer", "sectionListContinuation", "musicShelfRenderer", "musicPlaylistShelfRenderer",
            "gridRenderer", "musicGridRenderer", "musicCarouselShelfRenderer", "continuationItems"
        )
        val COLLECTION_NODES = COLLECTION_LISTS + setOf(
            "singleColumnBrowseResultsRenderer", "twoColumnBrowseResultsRenderer", "secondaryContents",
            "tabs", "tabRenderer", "content", "contents", "items", "itemSectionRenderer",
            "appendContinuationItemsAction", "reloadContinuationItemsCommand"
        )
    }
}

private val PUBLIC_PLAYLIST_ID = Regex("""(?:PL|RD|OLAK5uy_|UU|LL|LM|FL|UL)[A-Za-z0-9_-]*""")

internal fun canonicalPublicPlaylistId(id: String): String? =
    id.trim().removePrefix("VL").takeIf(PUBLIC_PLAYLIST_ID::matches)

internal fun homeDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
