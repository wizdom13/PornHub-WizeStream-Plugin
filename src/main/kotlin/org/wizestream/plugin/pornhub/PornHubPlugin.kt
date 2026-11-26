/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.wizestream.plugin.pornhub

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.plugin.WizeStreamPlugin
import org.schabi.newpipe.extractor.plugin.WizeStreamPluginContext

/** Native API 2.0 entry point; this public class intentionally has a no-argument constructor. */
class PornHubPlugin : WizeStreamPlugin {
    override fun createService(serviceId: Int, context: WizeStreamPluginContext): StreamingService =
        PornHubService(serviceId)
}
