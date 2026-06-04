package eu.kanade.tachiyomi.animeextension.en.watchanimeworld

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class WatchAnimeWorldUrlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 0) {
            val query = intent.data!!.toString()
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${WatchAnimeWorld.PREFIX_SEARCH}$query")
                putExtra("filter", packageName)
            }
            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e("WatchAnimeWorldUrl", e.toString())
            }
        } else {
            Log.e("WatchAnimeWorldUrl", "could not parse uri from intent $intent")
        }
        finish()
        exitProcess(0)
    }
}
