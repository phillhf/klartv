package io.github.phillhf.iptv.ui

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.phillhf.iptv.data.Http
import okhttp3.OkHttpClient

/** Bilder mit derselben Kennung laden wie die Streams; manche Bildserver der Anbieter lassen sonst nichts durch. */
fun installImageLoader() {
    val client = OkHttpClient.Builder()
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", Http.USER_AGENT).build()) }
        .build()
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }.build()
    }
}
