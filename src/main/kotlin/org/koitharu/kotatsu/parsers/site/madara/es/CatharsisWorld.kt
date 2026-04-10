package org.koitharu.kotatsu.parsers.site.madara.es

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("CATHARSISWORLD", "CatharsisWorld", "es")
internal class CatharsisWorld(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.CATHARSISWORLD, "catharsisworld.dig-it.info")
