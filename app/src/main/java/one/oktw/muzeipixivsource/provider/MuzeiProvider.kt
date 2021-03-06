package one.oktw.muzeipixivsource.provider

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.URLUtil
import androidx.core.app.RemoteActionCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.google.android.apps.muzei.api.UserCommand
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Pipe
import okio.buffer
import one.oktw.muzeipixivsource.R
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.FETCH_MODE_BOOKMARK
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.FETCH_MODE_FALLBACK
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.FETCH_MODE_RANKING
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.FETCH_MODE_RECOMMEND
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_CLEANUP
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_FALLBACK
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_MIRROR
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_MODE
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_MODE_RANKING
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_NUMBER
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_ORIGIN
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FETCH_RANDOM
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FILTER_BOOKMARK
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FILTER_ILLUST
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FILTER_SAFE
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FILTER_SIZE
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_FILTER_VIEW
import one.oktw.muzeipixivsource.activity.fragment.SettingsFragment.Companion.KEY_PIXIV_ACCESS_TOKEN
import one.oktw.muzeipixivsource.pixiv.Pixiv
import one.oktw.muzeipixivsource.pixiv.PixivOAuth
import one.oktw.muzeipixivsource.pixiv.mode.RankingCategory.Monthly
import one.oktw.muzeipixivsource.pixiv.mode.RankingCategory.valueOf
import one.oktw.muzeipixivsource.pixiv.model.Illust
import one.oktw.muzeipixivsource.pixiv.model.IllustTypes.ILLUST
import one.oktw.muzeipixivsource.provider.Commands.COMMAND_OPEN
import one.oktw.muzeipixivsource.provider.Commands.COMMAND_SHARE
import one.oktw.muzeipixivsource.service.CommandHandler
import one.oktw.muzeipixivsource.util.HttpUtils.httpClient
import org.jsoup.Jsoup
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit.SECONDS

class MuzeiProvider : MuzeiArtProvider() {
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private lateinit var preference: SharedPreferences
    private lateinit var analytics: FirebaseAnalytics

    override fun onCreate(): Boolean {
        PreferenceManager.setDefaultValues(context, R.xml.prefragment, true)

        preference = PreferenceManager.getDefaultSharedPreferences(context)
        analytics = FirebaseAnalytics.getInstance(context!!)

        return super.onCreate()
    }

    override fun onLoadRequested(initial: Boolean) {
        runBlocking { updateToken() }

        val token: String? = preference.getString(KEY_PIXIV_ACCESS_TOKEN, null)
        val fallback = preference.getBoolean(KEY_FETCH_FALLBACK, false)
        val pixiv = Pixiv(token = token, number = preference.getInt(KEY_FETCH_NUMBER, 30))

        try {
            when (if (token == null) FETCH_MODE_FALLBACK else preference.getString(KEY_FETCH_MODE, "0")!!.toInt()) {
                FETCH_MODE_FALLBACK -> pixiv.getFallback().let(::publish)
                FETCH_MODE_RECOMMEND -> pixiv.getRecommend().let(::publish)
                FETCH_MODE_RANKING -> pixiv.getRanking(
                    valueOf(preference.getString(KEY_FETCH_MODE_RANKING, Monthly.name)!!)
                ).let(::publish)

                FETCH_MODE_BOOKMARK -> pixiv.getBookmark(
                    preference.getInt(SettingsFragment.KEY_PIXIV_USER_ID, -1),
                    preference.getBoolean(SettingsFragment.KEY_FETCH_MODE_BOOKMARK, false)
                ).let(::publish)
            }
        } catch (e1: Exception) {
            // TODO better except handle
            Log.e("fetch", "fetch update error: ${e1.printStackTrace()}", e1)
            crashlytics.recordException(e1)
            if (e1.suppressed.isNotEmpty()) e1.suppressed.forEach(crashlytics::recordException)

            if (!fallback) throw e1
            try {
                pixiv.getFallback().let(::publish)
            } catch (e2: Exception) {
                Log.e("fetch", "fetch update fallback error", e2)

                crashlytics.recordException(e2)
                if (e1.suppressed.isNotEmpty()) e1.suppressed.forEach(crashlytics::recordException)

                throw e2
            }
        }
    }

    override fun openFile(artwork: Artwork): InputStream {
        val mirror = preference.getString(KEY_FETCH_MIRROR, "")!!
            .let { if (it.isBlank() || URLUtil.isNetworkUrl(it)) it else "https://$it" }
            .let(Uri::parse)
        val uri = artwork.persistentUri!!
            .let { if (mirror.authority.isNullOrBlank()) it else it.buildUpon().authority(mirror.authority).build() }
        val stream = Pipe(DEFAULT_BUFFER_SIZE.toLong()).apply {
            source.timeout().timeout(10, SECONDS)
            sink.timeout().timeout(10, SECONDS)
        }

        Request.Builder()
            .url(uri.toString())
            .header("Referer", "https://app-api.pixiv.net/")
            .build()
            .let(httpClient::newCall)
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}

                override fun onResponse(call: Call, response: Response) {
                    response.body?.source()?.use { source -> stream.sink.use { source.readAll(it) } }
                }
            })

        return stream.source.buffer().inputStream()
    }

    // New version command dart
    override fun getCommandActions(artwork: Artwork): List<RemoteActionCompat> {
        val context = context!!
        return listOf(
            RemoteActionCompat(
                IconCompat.createWithResource(context, R.drawable.ic_open),
                context.getString(R.string.button_open),
                artwork.title ?: "",
                PendingIntent.getBroadcast(
                    context,
                    artwork.id.toInt(),
                    Intent(context, CommandHandler::class.java)
                        .setAction("open")
                        .putExtra(CommandHandler.INTENT_COMMAND, COMMAND_OPEN)
                        .putExtra(CommandHandler.INTENT_OPEN_URI, artwork.webUri),
                    0
                )
            ).apply { setShouldShowIcon(false) },
            RemoteActionCompat(
                IconCompat.createWithResource(context, R.drawable.ic_share),
                context.getString(R.string.button_share),
                artwork.title ?: "",
                PendingIntent.getBroadcast(
                    context,
                    artwork.id.toInt(),
                    Intent(context, CommandHandler::class.java)
                        .setAction("share")
                        .putExtra(CommandHandler.INTENT_COMMAND, COMMAND_SHARE)
                        .putExtra(CommandHandler.INTENT_SHARE_TITLE, artwork.title)
                        .putExtra(CommandHandler.INTENT_SHARE_TEXT, getShareText(artwork))
                        .putExtra(CommandHandler.INTENT_SHARE_FILENAME, artwork.persistentUri!!.pathSegments.last())
                        .putExtra(CommandHandler.INTENT_SHARE_CACHE_FILE, artwork.data),
                    0
                )
            )
            // TODO save image
//            RemoteActionCompat(
//                IconCompat.createWithResource(context, R.drawable.ic_save),
//                context.getString(R.string.button_save),
//                artwork.title ?: "",
//                PendingIntent.getBroadcast(
//                    context,
//                    artwork.id.toInt(),
//                    Intent(context, CommandHandler::class.java)
//                        .setAction("save"),
//                    0
//                )
//            )
        )
    }

    override fun getCommands(artwork: Artwork): MutableList<UserCommand> {
        return mutableListOf(
            UserCommand(COMMAND_OPEN.code, context!!.getString(R.string.button_open)),
            UserCommand(COMMAND_SHARE.code, context!!.getString(R.string.button_share))
//            UserCommand(COMMAND_DOWNLOAD.code, context!!.getString(R.string.button_download))
        )
    }

    override fun onCommand(artwork: Artwork, id: Int) {
        when (id) {
            COMMAND_OPEN.code -> getCommandActions(artwork)[0].actionIntent.send()
            COMMAND_SHARE.code -> getCommandActions(artwork)[1].actionIntent.send()
//            COMMAND_DOWNLOAD.code -> getCommandActions(artwork)[2].actionIntent.send()
        }
    }

    private suspend fun updateToken() {
        analytics.logEvent("update_token", null)

        try {
            PixivOAuth.refresh(
                preference.getString(SettingsFragment.KEY_PIXIV_DEVICE_TOKEN, null) ?: return,
                preference.getString(SettingsFragment.KEY_PIXIV_REFRESH_TOKEN, null) ?: return
            ).response?.let { PixivOAuth.save(preference, it) }
        } catch (e: Exception) {
            Log.e("update_token", "update token error", e)
            crashlytics.recordException(e)
        }
    }

    private fun publish(list: ArrayList<Illust>) {
        val cleanHistory = preference.getBoolean(KEY_FETCH_CLEANUP, true)
        val filterNSFW = preference.getBoolean(KEY_FILTER_SAFE, true)
        val filterIllust = preference.getBoolean(KEY_FILTER_ILLUST, true)
        val random = preference.getBoolean(KEY_FETCH_RANDOM, false)
        val filterSize = preference.getInt(KEY_FILTER_SIZE, 0)
        val originImage = if (filterSize > 1200) true else preference.getBoolean(KEY_FETCH_ORIGIN, false)
        val minView = preference.getInt(KEY_FILTER_VIEW, 0)
        val minBookmark = preference.getInt(KEY_FILTER_BOOKMARK, 0)

        val artworkList = ArrayList<Artwork>()

        list.forEach {
            if (filterNSFW && it.sanityLevel >= 4) return@forEach
            if (filterIllust && it.type != ILLUST) return@forEach
            if (filterSize > it.height && filterSize > it.width) return@forEach
            if (minView > it.totalView || minBookmark > it.totalBookmarks) return@forEach

            if (it.pageCount > 1) {
                it.metaPages.forEachIndexed { index, image ->
                    val imageUrl = if (originImage) {
                        image.imageUrls.original
                    } else {
                        image.imageUrls.large?.replace("/c/600x1200_90", "")
                    } ?: return@forEachIndexed

                    Artwork.Builder()
                        .title(it.title)
                        .byline(it.user.name)
                        .attribution(Jsoup.parse(it.caption).text())
                        .token("${it.id}_$index")
                        .webUri("https://www.pixiv.net/artworks/${it.id}".toUri())
                        .persistentUri(imageUrl.toUri())
                        .build()
                        .let(artworkList::add)
                }
            } else {
                val imageUrl = if (originImage) {
                    it.metaSinglePage.original_image_url
                } else {
                    it.image_urls.large?.replace("/c/600x1200_90", "")
                }?.toUri() ?: return@forEach

                Artwork.Builder()
                    .title(it.title)
                    .byline(it.user.name)
                    .attribution(Jsoup.parse(it.caption).text())
                    .token(it.id.toString())
                    .webUri("https://www.pixiv.net/artworks/${it.id}".toUri())
                    .persistentUri(imageUrl)
                    .build()
                    .let(artworkList::add)
            }
        }

        if (cleanHistory) delete(contentUri, null, null)
        if (random) artworkList.shuffle()

        addArtwork(artworkList)
    }

    private fun getShareText(artwork: Artwork) = "${artwork.title} | ${artwork.byline} #pixiv ${artwork.webUri}"
}
