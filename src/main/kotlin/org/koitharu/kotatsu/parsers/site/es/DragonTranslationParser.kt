package org.koitharu.kotatsu.parsers.site.es

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("DRAGONTRANSLATION", "Dragon Translation", "es")
internal class DragonTranslationParser(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.DRAGONTRANSLATION, "dragontranslation.org")
