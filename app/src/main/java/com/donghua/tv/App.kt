package com.donghua.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.donghua.tv.data.Prefs
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // The thumbnail host refuses requests that arrive without a Referer.
            // That is why every card in the chapter grid came up as an empty
            // square: the request failed and the placeholder showed through.
            // Sending it unconditionally is harmless, since it names this site's
            // own origin.
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Referer", "https://www.mundodonghua.com/")
                    .header("Accept", "image/*,*/*;q=0.8")
                    .build()
                chain.proceed(request)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
