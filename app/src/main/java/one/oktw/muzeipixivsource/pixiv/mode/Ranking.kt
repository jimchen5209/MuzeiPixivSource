package one.oktw.muzeipixivsource.pixiv.mode

import com.google.android.apps.muzei.api.RemoteMuzeiArtSource
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import one.oktw.muzeipixivsource.pixiv.mode.RankingCategory.*
import one.oktw.muzeipixivsource.pixiv.model.Illust
import one.oktw.muzeipixivsource.pixiv.model.IllustList

class Ranking(private val token: String, private val category: RankingCategory) {
    fun getImages(number: Int): ArrayList<Illust> {
        val list = ArrayList<Illust>()
        var url = "https://app-api.pixiv.net/v1/illust/ranking?mode=" + when (category) {
            Daily -> "day"
            Weekly -> "week"
            Monthly -> "month"
        }

        do {
            val res = request(url) ?: throw RemoteMuzeiArtSource.RetryException()

            if (res.nextUrl != null) url = res.nextUrl else break

            list += res.illusts
        } while (list.size < number)

        return list
    }

    private fun request(url: String): IllustList? {
        val httpClient = OkHttpClient()

        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
            .let(httpClient::newCall)
            .execute()
            .body?.let {
                GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
                    .fromJson<IllustList>(it.charStream(), IllustList::class.java)
            }
    }
}
