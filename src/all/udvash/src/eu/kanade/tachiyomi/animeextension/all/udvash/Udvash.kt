package eu.kanade.tachiyomi.animeextension.all.udvash

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Collections

class Udvash : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Udvash"

    override val baseUrl = "https://online.udvash-unmesh.com"

    override val lang = "all"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val response = chain.proceed(originalRequest)

            if (response.request.url.encodedPath.contains("Account/Login") && !originalRequest.url.encodedPath.contains("Account/Login")) {
                response.close()
                login()
                chain.proceed(originalRequest)
            } else {
                response
            }
        }
        .build()

    private fun login() {
        val regNo = preferences.getString(PREF_REG_NO, "") ?: ""
        val password = preferences.getString(PREF_PASSWORD, "") ?: ""

        if (regNo.isEmpty() || password.isEmpty()) {
            throw Exception("Please set Registration Number and Password in extension settings.")
        }

        // Step 1: Get Token from Login Page
        val loginPageRequest = GET("$baseUrl/Account/Login")
        val loginPageResponse = network.client.newCall(loginPageRequest).execute()
        val loginPageDoc = Jsoup.parse(loginPageResponse.body?.string().orEmpty())
        val token1 = loginPageDoc.select("input[name=__RequestVerificationToken]").attr("value")

        // Step 2: POST to Password page
        val passwordForm = FormBody.Builder()
            .add("RegistrationNumber", regNo)
            .add("returnUrl", "")
            .add("__RequestVerificationToken", token1)
            .build()

        val passwordPageRequest = POST("$baseUrl/Account/Password", headers, passwordForm)
        val passwordPageResponse = network.client.newCall(passwordPageRequest).execute()
        val passwordPageDoc = Jsoup.parse(passwordPageResponse.body?.string().orEmpty())
        val token2 = passwordPageDoc.select("input[name=__RequestVerificationToken]").attr("value")

        // Step 3: Final Login
        val loginForm = FormBody.Builder()
            .add("RegistrationNumber", regNo)
            .add("Password", password)
            .add("RememberMe", "true")
            .add("returnUrl", "")
            .add("__RequestVerificationToken", token2)
            .build()

        val finalLoginRequest = POST("$baseUrl/Account/Login", headers, loginForm)
        network.client.newCall(finalLoginRequest).execute().close()
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val courseUrl = preferences.getString(PREF_LAST_COURSE_URL, "")
        val url = if (courseUrl.isNullOrEmpty()) {
            val courses = getMyCourses()
            if (courses.size > 1) {
                // courses[0] is "Select a Course", so courses[1] or last is better
                val selectedCourse = courses.last()
                preferences.edit().putString(PREF_LAST_COURSE_URL, selectedCourse.url).apply()
                selectedCourse.url
            } else {
                "/Content/ContentSubject?CourseTypeId=2&masterCourseId=82" // Fallback
            }
        } else {
            courseUrl
        }
        return GET("$baseUrl$url", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val doc = Jsoup.parse(response.body?.string().orEmpty())
        val items = doc.select("a[href*=subjectId=]").map { element ->
            SAnime.create().apply {
                title = element.text().trim()
                url = element.attr("href")
                thumbnail_url = "https://online.udvash-unmesh.com/Content/UmsTheme/assets/images/favicon.png"
            }
        }
        return AnimesPage(items, false)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val courseFilter = filters.filterIsInstance<CourseFilter>().firstOrNull()
        if (courseFilter != null && courseFilter.state > 0) {
            val selectedCourse = courseFilter.courses[courseFilter.state]
            if (selectedCourse.url.isNotEmpty()) {
                preferences.edit().putString(PREF_LAST_COURSE_URL, selectedCourse.url).apply()

                val sectionFilter = filters.filterIsInstance<SectionFilter>().firstOrNull()
                val sectionId = if (sectionFilter != null && sectionFilter.state > 0) {
                    sectionFilter.sections[sectionFilter.state].id
                } else {
                    null
                }

                val request = GET("$baseUrl${selectedCourse.url}", headers)
                val response = client.newCall(request).awaitSuccess()
                val doc = Jsoup.parse(response.body?.string().orEmpty())

                val items = doc.select("a[href*=subjectId=]").map { element ->
                    SAnime.create().apply {
                        title = element.text().trim()
                        var u = element.attr("href")
                        if (sectionId != null) {
                            u += if (u.contains("?")) "&" else "?"
                            u += "fixedSectionId=$sectionId"
                        }
                        url = u
                        thumbnail_url = "https://online.udvash-unmesh.com/Content/UmsTheme/assets/images/favicon.png"
                    }
                }
                return AnimesPage(items, false)
            }
        }
        return super.getSearchAnime(page, query, filters)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return popularAnimeRequest(page)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val fixedSectionId = anime.url.substringAfter("fixedSectionId=", "").substringBefore("&").toIntOrNull()
        val visitedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        return recursiveEpisodeFetch(anime.url, "", fixedSectionId, visitedUrls, 0).reversed()
    }

    private suspend fun recursiveEpisodeFetch(
        url: String,
        parentName: String,
        fixedSectionId: Int?,
        visited: MutableSet<String>,
        depth: Int,
        isInsideSection: Boolean = false,
    ): List<SEpisode> = coroutineScope {
        if (depth > 10) return@coroutineScope emptyList() // Deep search

        val absoluteUrl = if (url.startsWith("http")) url else "$baseUrl$url"

        val sectionIdInUrl = absoluteUrl.substringAfter("masterContentTypeId=", "")
            .substringBefore("&").ifEmpty {
                absoluteUrl.substringAfter("ContentTypeId=", "").substringBefore("&")
            }.toIntOrNull()

        if (fixedSectionId != null && sectionIdInUrl != null && sectionIdInUrl != fixedSectionId) {
            // Only skip if it's explicitly a section landing page
            if (absoluteUrl.contains("ContentType")) {
                return@coroutineScope emptyList()
            }
        }

        if (!visited.add(absoluteUrl)) return@coroutineScope emptyList()

        val currentlyInside = isInsideSection || (fixedSectionId != null && sectionIdInUrl == fixedSectionId)

        val doc = try {
            val response = client.newCall(GET(absoluteUrl, headers)).awaitSuccess()
            Jsoup.parse(response.body?.string().orEmpty())
        } catch (e: Exception) {
            return@coroutineScope emptyList()
        }

        val episodes = mutableListOf<SEpisode>()

        // 1. Extract Videos (Strictly ONLY contentButtonType=video)
        if (fixedSectionId == null || currentlyInside) {
            doc.select("a[href*=contentButtonType=video]").forEach { video ->
                val vTitle = video.parent()?.parent()?.select("h2, h5, h3, .card-body h3, .card-title")
                    ?.firstOrNull()?.text()?.trim() ?: "Video"
                episodes.add(
                    SEpisode.create().apply {
                        name = if (parentName.isNotEmpty()) "$parentName - $vTitle" else vTitle
                        this.url = video.attr("href")
                    },
                )
            }
        }

        // 2. Recurse in Parallel
        val nextTasks = mutableListOf<Deferred<List<SEpisode>>>()

        // Subjects / Chapters / Subjects-in-course
        val folderSelectors = "a[href*=masterChapterId=], a[href*=subjectId=], a[href*=ContentChapter], a[href*=DisplayContentType], a[href*=DisplayContentCard]"
        doc.select(folderSelectors).forEach { element ->
            val nextUrl = element.attr("href")
            val name = element.text().trim()

            // Skip notes/PDFs and other sections if filter is active
            val ln = nextUrl.lowercase()
            if (!ln.contains("contentbuttontype=note") && !ln.contains(".pdf")) {
                val nextSectionId = nextUrl.substringAfter("masterContentTypeId=", "")
                    .substringBefore("&").ifEmpty {
                        nextUrl.substringAfter("ContentTypeId=", "").substringBefore("&")
                    }.toIntOrNull()

                if (fixedSectionId == null || nextSectionId == null || nextSectionId == fixedSectionId) {
                    val cleanName = if (parentName.isNotEmpty() && name.isNotEmpty()) {
                        "$parentName > $name"
                    } else if (name.isNotEmpty()) {
                        name
                    } else {
                        parentName
                    }
                    nextTasks.add(async { recursiveEpisodeFetch(nextUrl, cleanName, fixedSectionId, visited, depth + 1, currentlyInside) })
                }
            }
        }

        episodes + nextTasks.awaitAll().flatten()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).execute()
        val doc = Jsoup.parse(response.body?.string().orEmpty())

        val videoSourcesAttr = doc.select("[data-all-video-source]").attr("data-all-video-source")
        if (videoSourcesAttr.isEmpty()) return emptyList()

        return videoSourcesAttr.split(",").mapIndexed { index, url ->
            val decodedUrl = url.replace("&amp;", "&")
            val quality = when {
                decodedUrl.contains("1080P", true) -> "1080p"
                decodedUrl.contains("720P", true) -> "720p"
                decodedUrl.contains("480P", true) -> "480p"
                decodedUrl.contains("360P", true) -> "360p"
                else -> "Source ${index + 1}"
            }
            Video(decodedUrl, quality, decodedUrl)
        }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        val courses = getMyCourses()
        val lastCourseUrl = preferences.getString(PREF_LAST_COURSE_URL, "") ?: ""
        val sections = getSections(lastCourseUrl)

        return AnimeFilterList(
            AnimeFilter.Header("Course Selection"),
            CourseFilter(courses),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Section Filter"),
            SectionFilter(sections),
        )
    }

    private var coursesCache: List<Course>? = null
    private val sectionsCache = mutableMapOf<String, List<Section>>()

    private fun getMyCourses(): List<Course> {
        coursesCache?.let { return it }

        val list = mutableListOf(Course("Select a Course", ""))
        try {
            val response = client.newCall(GET("$baseUrl/Dashboard", headers)).execute()
            val doc = Jsoup.parse(response.body?.string().orEmpty())

            doc.select("a[href*=masterCourseId=]").forEach {
                val name = it.select("h3").text().trim()
                val url = it.attr("href")
                if (name.isNotEmpty() && url.isNotEmpty() && list.none { c -> c.url == url }) {
                    list.add(Course(name, url))
                }
            }

            if (list.size == 1) {
                listOf("/Content/Index?id=1", "/Content/Index?id=2").forEach { path ->
                    try {
                        val res2 = client.newCall(GET("$baseUrl$path", headers)).execute()
                        val doc2 = Jsoup.parse(res2.body?.string().orEmpty())
                        doc2.select("a[href*=masterCourseId=]").forEach {
                            val name = it.text().trim()
                            val url = it.attr("href")
                            if (name.isNotEmpty() && url.isNotEmpty() && list.none { c -> c.url == url }) {
                                list.add(Course(name, url))
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
        coursesCache = list
        return list
    }

    private fun getSections(courseUrl: String): List<Section> {
        if (courseUrl.isEmpty()) return listOf(Section("All Sections", 0))
        sectionsCache[courseUrl]?.let { return it }

        val list = mutableListOf(Section("All Sections", 0))
        try {
            val response = client.newCall(GET("$baseUrl$courseUrl", headers)).execute()
            val doc = Jsoup.parse(response.body?.string().orEmpty())

            val urlsToPeek = mutableSetOf<String>()
            doc.select("a[href*=subjectId=], a[href*=masterChapterId=]").take(3).forEach {
                urlsToPeek.add(it.attr("href"))
            }
            urlsToPeek.add(courseUrl)

            urlsToPeek.forEach { u ->
                try {
                    val absUrl = if (u.startsWith("http")) u else "$baseUrl$u"
                    val res = client.newCall(GET(absUrl, headers)).execute()
                    val d = Jsoup.parse(res.body?.string().orEmpty())

                    d.select("a[href*=masterContentTypeId=], a[href*=ContentTypeId=]").forEach {
                        val name = it.text().trim()
                        val id = it.attr("href").substringAfter("masterContentTypeId=", "")
                            .substringBefore("&").ifEmpty {
                                it.attr("href").substringAfter("ContentTypeId=", "").substringBefore("&")
                            }.toIntOrNull()
                        if (name.isNotEmpty() && id != null && list.none { s -> s.id == id }) {
                            val ln = name.lowercase()
                            // Skip Practice Sheet and Note from filter list as requested
                            if (!ln.contains("practice sheet") && !ln.contains("note")) {
                                list.add(Section(name, id))
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}

        if (list.size > 1) {
            sectionsCache[courseUrl] = list
        }
        return list
    }

    private data class Section(val name: String, val id: Int) {
        override fun toString(): String = name
    }

    private class SectionFilter(val sections: List<Section>) : AnimeFilter.Select<Section>(
        "Section",
        sections.toTypedArray(),
    )

    private data class Course(val name: String, val url: String) {
        override fun toString(): String = name
    }

    private class CourseFilter(val courses: List<Course>) : AnimeFilter.Select<Course>(
        "My Courses",
        courses.toTypedArray(),
    )

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_REG_NO
            title = "Registration Number"
            summary = "Your Udvash Registration Number"
            setDefaultValue("")
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_PASSWORD
            title = "Password"
            summary = "Your Udvash Password"
            setDefaultValue("")
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_REG_NO = "registration_number"
        private const val PREF_PASSWORD = "password"
        private const val PREF_LAST_COURSE_URL = "last_course_url"
    }
}
