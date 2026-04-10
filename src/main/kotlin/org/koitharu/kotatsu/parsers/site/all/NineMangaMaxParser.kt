package org.koitharu.kotatsu.parsers.site.all

import androidx.collection.ArrayMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

internal abstract class NineMangaMaxParser(
	context: MangaLoaderContext,
	source: MangaParserSource,
	defaultDomain: String,
) : PagedMangaParser(context, source, pageSize = 26), Interceptor {

	override val configKeyDomain = ConfigKey.Domain(defaultDomain)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	init {
		context.cookieJar.insertCookies(domain, "ninemanga_template_desk=yes")
	}

	override fun getRequestHeaders() = super.getRequestHeaders().newBuilder()
		.add("Accept-Language", "es-ES,es;q=0.9,en-US;q=0.7,en;q=0.3")
		.build()

	override val availableSortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.POPULARITY,
		SortOrder.UPDATED,
		SortOrder.ALPHABETICAL,
	)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchWithFiltersSupported = true,
			isSearchSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = getOrCreateTagMap().values.toSet(),
		availableStates = EnumSet.of(
			MangaState.ONGOING,
			MangaState.FINISHED,
		),
	)

	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val newRequest = if (request.url.host == domain) {
			request.newBuilder().removeHeader("Referer").build()
		} else {
			request
		}
		return chain.proceed(newRequest)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)

			if (filter.tags.isNotEmpty() || filter.tagsExclude.isNotEmpty() || filter.states.isNotEmpty() || !filter.query.isNullOrEmpty()) {
				append("/search/")
				append("?page=")
				append(page.toString())
				append(".html")

				filter.query?.let {
					append("&name_sel=contain&wd=")
					append(filter.query.urlEncoded())
				}

				append("&category_id=")
				append(filter.tags.joinToString(separator = ",") { it.key })

				append("&out_category_id=")
				append(filter.tagsExclude.joinToString(separator = ",") { it.key })

				filter.states.oneOrThrowIfMany()?.let {
					append("&completed_series=")
					when (it) {
						MangaState.ONGOING -> append("no")
						MangaState.FINISHED -> append("yes")
						else -> append("either")
					}
				}
			} else {
				when (order) {
					SortOrder.UPDATED -> {
						append("/category/updated_")
						append(page.toString())
					}
					SortOrder.ALPHABETICAL -> {
						append("/category/index_")
						append(page.toString())
					}
					else -> {
						append("/category/index_")
						append(page.toString())
					}
				}
			}
		}
		val doc = webClient.httpGet(url).parseHtml()
		val root = doc.body().selectFirstOrThrow("ul.direlist")
		val baseHost = root.baseUri().toHttpUrl().host
		return root.select("li").map { node ->
			val href = node.selectFirstOrThrow("a").attrAsAbsoluteUrl("href")
			val relUrl = href.toRelativeUrl(baseHost)
			val dd = node.selectFirst("dd")
			Manga(
				id = generateUid(relUrl),
				url = relUrl,
				publicUrl = href,
				title = dd?.selectFirst("a.bookname")?.text()?.toCamelCase().orEmpty(),
				altTitles = emptySet(),
				coverUrl = node.selectFirst("img")?.src(),
				rating = RATING_UNKNOWN,
				authors = emptySet(),
				contentRating = null,
				tags = emptySet(),
				state = null,
				source = source,
				description = dd?.selectFirst("p")?.html(),
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(
			manga.url.toAbsoluteUrl(domain) + "?waring=1",
		).parseHtml()
		val root = doc.body().selectFirstOrThrow("div.manga")
		val infoRoot = root.selectFirstOrThrow("div.bookintro")
		val tagMap = getOrCreateTagMap()
		val selectTag = infoRoot.getElementsByAttributeValue("itemprop", "genre").first()?.select("a")
		val tags = selectTag?.mapNotNullToSet { tagMap[it.text()] }
		val author = infoRoot.getElementsByAttributeValue("itemprop", "author").first()?.textOrNull()
		val coverImg = infoRoot.selectFirst("a.bookface img")?.src()
			?: infoRoot.selectFirst("img[itemprop=image]")?.src()
		return manga.copy(
			title = root.selectFirst("h1[itemprop=name]")?.textOrNull()?.removeSuffix("Manga")?.trimEnd()
				?: manga.title,
			tags = tags.orEmpty(),
			authors = setOfNotNull(author),
			coverUrl = coverImg ?: manga.coverUrl,
			state = parseStatus(infoRoot.select("li a.red").text()),
			description = infoRoot.getElementsByAttributeValue("itemprop", "description").first()?.html()
				?.substringAfter("</b>"),
			chapters = root.selectFirst("div.chapterbox")?.select("ul.sub_vol_ul > li")
				?.mapChapters(reversed = true) { i, li ->
					val a = li.selectFirstOrThrow("a.chapter_list_a")
					val href = a.attrAsRelativeUrl("href").replace("%20", " ")
					val chapterTitle = a.textOrNull()
					MangaChapter(
						id = generateUid(href),
						title = chapterTitle,
						number = parseChapterNumber(chapterTitle, i),
						volume = 0,
						url = href,
						uploadDate = parseChapterDate(li.selectFirst("span")?.text().orEmpty()),
						source = source,
						scanlator = null,
						branch = null,
					)
				},
		)
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val doc = webClient.httpGet(chapter.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.body().requireElementById("page").select("option").map { option ->
			val url = option.attr("value")
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
		return doc.body().selectFirstOrThrow("a.pic_download").attrAsAbsoluteUrl("href")
	}

	// -- Tag cache --

	private var tagCache: ArrayMap<String, MangaTag>? = null
	private val mutex = Mutex()

	private suspend fun getOrCreateTagMap(): Map<String, MangaTag> = mutex.withLock {
		tagCache?.let { return@withLock it }
		val tagMap = ArrayMap<String, MangaTag>()
		val tagElements = webClient.httpGet("https://${domain}/search/?type=high").parseHtml().select("li.cate_list")
		for (el in tagElements) {
			if (el.text().isEmpty()) continue
			val cateId = el.attr("cate_id")
			val a = el.selectFirstOrThrow("a")
			tagMap[el.text()] = MangaTag(
				title = a.text().toTitleCase(sourceLocale),
				key = cateId,
				source = source,
			)
		}
		tagCache = tagMap
		return@withLock tagMap
	}

	// -- Helpers --

	private fun parseChapterNumber(title: String?, index: Int): Float {
		if (title == null) return (index + 1).toFloat()
		val match = Regex("""(\d+(?:\.\d+)?)""").find(title)
		return match?.groupValues?.get(1)?.toFloatOrNull() ?: (index + 1).toFloat()
	}

	private fun parseStatus(status: String) = when {
		status.contains("Ongoing", ignoreCase = true) -> MangaState.ONGOING
		status.contains("Completed", ignoreCase = true) -> MangaState.FINISHED
		status.contains("En curso", ignoreCase = true) -> MangaState.ONGOING
		status.contains("Completado", ignoreCase = true) -> MangaState.FINISHED
		status.contains("постоянный", ignoreCase = true) -> MangaState.ONGOING
		status.contains("завершенный", ignoreCase = true) -> MangaState.FINISHED
		status.contains("Laufende", ignoreCase = true) -> MangaState.ONGOING
		status.contains("Abgeschlossen", ignoreCase = true) -> MangaState.FINISHED
		status.contains("Completo", ignoreCase = true) -> MangaState.FINISHED
		status.contains("Em tradução", ignoreCase = true) -> MangaState.ONGOING
		status.contains("In corso", ignoreCase = true) -> MangaState.ONGOING
		status.contains("Completato", ignoreCase = true) -> MangaState.FINISHED
		status.contains("En cours", ignoreCase = true) -> MangaState.ONGOING
		status.contains("Complété", ignoreCase = true) -> MangaState.FINISHED
		else -> null
	}

	private fun parseChapterDate(date: String): Long {
		val dateWords = date.split(" ")
		if (dateWords.size == 3) {
			if (dateWords[1].contains(",")) {
				return SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH).parseSafe(date)
			}
			val timeAgo = dateWords[0].toIntOrNull() ?: return 0L
			return Calendar.getInstance().apply {
				when (dateWords[1].lowercase()) {
					"minutes", "minutos", "минут", "minuti" -> Calendar.MINUTE
					"hours", "horas", "hora", "часа", "stunden", "ore", "heures" -> Calendar.HOUR
					"days", "días", "dias", "дней", "tage", "giorni", "jours" -> Calendar.DAY_OF_YEAR
					else -> null
				}?.let {
					add(it, -timeAgo)
				}
			}.timeInMillis
		}
		return 0L
	}

	// -- Variants --

	@MangaSourceParser("NINEMANGA_MAX_ES", "NineManga Max Español", "es")
	class Spanish(context: MangaLoaderContext) : NineMangaMaxParser(
		context,
		MangaParserSource.NINEMANGA_MAX_ES,
		"es.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_MAX_EN", "NineManga Max English", "en")
	class English(context: MangaLoaderContext) : NineMangaMaxParser(
		context,
		MangaParserSource.NINEMANGA_MAX_EN,
		"www.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_MAX_RU", "NineManga Max Русский", "ru")
	class Russian(context: MangaLoaderContext) : NineMangaMaxParser(
		context,
		MangaParserSource.NINEMANGA_MAX_RU,
		"ru.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_MAX_DE", "NineManga Max Deutsch", "de")
	class Deutsch(context: MangaLoaderContext) : NineMangaMaxParser(
		context,
		MangaParserSource.NINEMANGA_MAX_DE,
		"de.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_MAX_BR", "NineManga Max Brasil", "pt")
	class Brazil(context: MangaLoaderContext) : NineMangaMaxParser(
		context,
		MangaParserSource.NINEMANGA_MAX_BR,
		"br.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_MAX_IT", "NineManga Max Italiano", "it")
	class Italiano(context: MangaLoaderContext) : NineMangaMaxParser(
		context,
		MangaParserSource.NINEMANGA_MAX_IT,
		"it.ninemanga.com",
	)

	@MangaSourceParser("NINEMANGA_MAX_FR", "NineManga Max Français", "fr")
	class Francais(context: MangaLoaderContext) : NineMangaMaxParser(
		context,
		MangaParserSource.NINEMANGA_MAX_FR,
		"fr.ninemanga.com",
	)
}
