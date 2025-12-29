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
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

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
        val courseUrl = preferences.getString(PREF_LAST_COURSE_URL, "/Content/ContentSubject?CourseTypeId=2&masterCourseId=82")
        return GET("$baseUrl$courseUrl", headers)
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
                val request = GET("$baseUrl${selectedCourse.url}", headers)
                return client.newCall(request).awaitSuccess().use(::popularAnimeParse)
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
        val chaptersRequest = GET("$baseUrl${anime.url}", headers)
        val chaptersResponse = client.newCall(chaptersRequest).execute()
        val chaptersDoc = Jsoup.parse(chaptersResponse.body?.string().orEmpty())

        val episodes = mutableListOf<SEpisode>()

        chaptersDoc.select("a[href*=masterChapterId=]").forEach { chapter ->
            val chapterName = chapter.text().trim()
            val chapterUrl = chapter.attr("href")

            val typesRequest = GET("$baseUrl$chapterUrl", headers)
            val typesResponse = client.newCall(typesRequest).execute()
            val typesDoc = Jsoup.parse(typesResponse.body?.string().orEmpty())

            typesDoc.select("a[href*=masterContentTypeId=3]").forEach { type ->
                val typeUrl = type.attr("href")

                val cardsRequest = GET("$baseUrl$typeUrl", headers)
                val cardsResponse = client.newCall(cardsRequest).execute()
                val cardsDoc = Jsoup.parse(cardsResponse.body?.string().orEmpty())

                cardsDoc.select("a[href*=contentButtonType=video]").forEach { video ->
                    episodes.add(
                        SEpisode.create().apply {
                            val vTitle = video.parent()?.parent()?.select("h5")?.first()?.ownText()?.trim() ?: "Video"
                            name = "$chapterName - $vTitle"
                            url = video.attr("href")
                        },
                    )
                }
            }
        }

        return episodes.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val response = client.newCall(GET("$baseUrl${episode.url}", headers)).execute()
        val doc = Jsoup.parse(response.body?.string().orEmpty())

        val videoSourcesAttr = doc.select("[data-all-video-source]").attr("data-all-video-source")
        if (videoSourcesAttr.isEmpty()) return emptyList()

        return videoSourcesAttr.split(",").mapIndexed { index, url ->
            Video(url, "Source ${index + 1}", url)
        }
    }

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList {
        val courses = getMyCourses()
        return AnimeFilterList(
            AnimeFilter.Header("Course Selection"),
            CourseFilter(courses),
        )
    }

    private fun getMyCourses(): List<Course> {
        val list = mutableListOf(Course("Default", ""))
        try {
            val response = client.newCall(GET("$baseUrl/Content/Index?id=2", headers)).execute()
            val doc = Jsoup.parse(response.body?.string().orEmpty())
            doc.select("a[href*=masterCourseId=]").forEach {
                list.add(Course(it.text().trim(), it.attr("href")))
            }
        } catch (e: Exception) {
            // Logged out or network error
        }
        return list
    }

    private data class Course(val name: String, val url: String) {
        override fun toString(): String = name
    }

    private class CourseFilter(val courses: List<Course>) : AnimeFilter.Select<Course>(
        "Select Course",
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
