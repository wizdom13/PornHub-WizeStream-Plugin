/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response

private val fixtureHttpClient = OkHttpClient()

/** A real cancelable transport handle that is never enqueued or executed. */
internal fun fixtureHttpCall(): Call = fixtureHttpClient.newCall(
    Request.Builder().url("https://example.invalid/offline-fixture").build()
)

internal fun completedFixtureCall(callback: Downloader.AsyncCallback, response: () -> Response): CancellableCall {
    val call = CancellableCall(fixtureHttpCall())
    try {
        callback.onSuccess(response())
    } catch (error: Exception) {
        callback.onError(error)
    } finally {
        call.setFinished()
    }
    return call
}
