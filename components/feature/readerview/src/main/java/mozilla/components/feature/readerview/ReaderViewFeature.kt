/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.readerview

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import mozilla.components.browser.session.SelectionAwareSessionObserver
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.feature.readerview.internal.ReaderViewControlsInteractor
import mozilla.components.feature.readerview.internal.ReaderViewControlsPresenter
import mozilla.components.feature.readerview.view.ReaderViewControlsView
import mozilla.components.feature.readerview.ReaderViewFeature.ColorScheme.LIGHT
import mozilla.components.feature.readerview.ReaderViewFeature.FontType.SERIF
import mozilla.components.support.base.feature.BackHandler
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.properties.Delegates.observable

typealias OnReaderViewAvailableChange = (available: Boolean) -> Unit

/**
 * Feature implementation that provides a reader view for the selected
 * session. This feature is implemented as a web extension and
 * needs to be installed prior to use (see [ReaderViewFeature.install]).
 *
 * @property context a reference to the context.
 * @property engine a reference to the application's browser engine.
 * @property sessionManager a reference to the application's [SessionManager].
 * @property onReaderViewAvailableChange a callback invoked to indicate whether
 * or not reader view is available for the page loaded by the currently selected
 * session. The callback will be invoked when a page is loaded or refreshed,
 * on any navigation (back or forward), and when the selected session
 * changes.
 */
@Suppress("TooManyFunctions")
class ReaderViewFeature(
    private val context: Context,
    private val engine: Engine,
    private val sessionManager: SessionManager,
    controlsView: ReaderViewControlsView,
    private val onReaderViewAvailableChange: OnReaderViewAvailableChange = { }
) : SelectionAwareSessionObserver(sessionManager), LifecycleAwareFeature, BackHandler {

    @VisibleForTesting
    internal val config = Config(context.getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE))
    private val controlsPresenter = ReaderViewControlsPresenter(controlsView, config)
    private val controlsInteractor = ReaderViewControlsInteractor(controlsView, config)

    enum class FontType(val value: String) { SANSSERIF("sans-serif"), SERIF("serif") }
    enum class ColorScheme { LIGHT, SEPIA, DARK }

    inner class Config(private val prefs: SharedPreferences) {

        var colorScheme by observable(ColorScheme.valueOf(prefs.getString(COLOR_SCHEME_KEY, LIGHT.name)!!)) {
            _, old, new -> if (old != new) {
                val message = JSONObject().put(ACTION_MESSAGE_KEY, ACTION_SET_COLOR_SCHEME).put(ACTION_VALUE, new.name)
                sendContentMessage(message)
                prefs.edit().putString(COLOR_SCHEME_KEY, new.name).apply()
            }
        }

        var fontType by observable(FontType.valueOf(prefs.getString(FONT_TYPE_KEY, SERIF.name)!!)) {
            _, old, new -> if (old != new) {
                val message = JSONObject().put(ACTION_MESSAGE_KEY, ACTION_SET_FONT_TYPE).put(ACTION_VALUE, new.value)
                sendContentMessage(message)
                prefs.edit().putString(FONT_TYPE_KEY, new.name).apply()
            }
        }

        var fontSize by observable(prefs.getInt(FONT_SIZE_KEY, FONT_SIZE_DEFAULT)) {
            _, old, new -> if (old != new) {
                val message = JSONObject().put(ACTION_MESSAGE_KEY, ACTION_CHANGE_FONT_SIZE).put(ACTION_VALUE, new - old)
                sendContentMessage(message)
                prefs.edit().putInt(FONT_SIZE_KEY, new).apply()
            }
        }
    }

    override fun start() {
        observeSelected()

        registerContentMessageHandler()

        if (ReaderViewFeature.installedWebExt == null) {
            ReaderViewFeature.install(engine)
        }

        if (portConnected()) {
            updateReaderViewState()
        }

        controlsInteractor.start()
    }

    override fun stop() {
        controlsInteractor.stop()
        super.stop()
    }

    override fun onBackPressed(): Boolean {
        activeSession?.let {
            if (it.readerMode) {
                if (controlsPresenter.areControlsVisible()) {
                    hideControls()
                } else {
                    hideReaderView()
                }
                return true
            }
        }
        return false
    }

    override fun onSessionSelected(session: Session) {
        super.onSessionSelected(session)
        updateReaderViewState(session)
    }

    override fun onSessionAdded(session: Session) {
        registerContentMessageHandler(session)
    }

    override fun onSessionRemoved(session: Session) {
        ports.remove(sessionManager.getEngineSession(session))
    }

    override fun onUrlChanged(session: Session, url: String) {
        session.readerable = false
        session.readerMode = false
        checkReaderable()
    }

    override fun onReaderableStateUpdated(session: Session, readerable: Boolean) {
        onReaderViewAvailableChange(readerable)
    }

    /**
     * Shows the reader view UI.
     */
    fun showReaderView(session: Session? = activeSession) {
        session?.let {
            showReaderView(sessionManager.getEngineSession(session), config)
            it.readerMode = true
        }
    }

    /**
     * Hides the reader view UI.
     */
    fun hideReaderView(session: Session? = activeSession) {
        session?.let {
            it.readerMode = false
            // We will re-determine if the original page is readerable when it's loaded.
            it.readerable = false
            hideReaderView(sessionManager.getEngineSession(session))
        }
    }

    /**
     * Shows the reader view appearance controls.
     */
    fun showControls() {
        controlsPresenter.show()
    }

    /**
     * Hides the reader view appearance controls.
     */
    fun hideControls() {
        controlsPresenter.hide()
    }

    @VisibleForTesting
    internal fun checkReaderable(session: Session? = activeSession) {
        session?.let {
            checkReaderable(sessionManager.getEngineSession(session))
        }
    }

    @VisibleForTesting
    internal fun registerContentMessageHandler(session: Session? = activeSession) {
        if (session == null) {
            return
        }

        val engineSession = sessionManager.getOrCreateEngineSession(session)
        val messageHandler = ReaderViewContentMessageHandler(session, engineSession, WeakReference(config))
        registerMessageHandler(engineSession, messageHandler)
    }

    @VisibleForTesting
    internal fun sendContentMessage(msg: Any, session: Session? = activeSession) {
        session?.let {
            sendContentMessage(msg, sessionManager.getEngineSession(session))
        }
    }

    @VisibleForTesting
    internal fun updateReaderViewState(session: Session? = activeSession) {
        if (session == null) {
            return
        }

        checkReaderable(session)
        if (session.readerMode) {
            showReaderView(session)
        }
    }

    @VisibleForTesting
    internal fun portConnected(session: Session? = activeSession): Boolean {
        return session?.let { portConnected(sessionManager.getEngineSession(session)) } ?: false
    }

    private class ReaderViewContentMessageHandler(
        private val session: Session,
        private val engineSession: EngineSession,
        // This needs to be a weak reference because the engine session this message handler will be
        // attached to has a longer lifespan than the feature instance i.e. a tab can remain open,
        // but we don't want to prevent the feature (and therefore its context/fragment) from
        // being garbage collected. The config has references to both the context and feature.
        private val config: WeakReference<Config>
    ) : MessageHandler {
        override fun onPortConnected(port: Port) {
            val config = config.get() ?: return

            ports[port.engineSession] = port

            checkReaderable(engineSession)
            if (session.readerMode) {
                showReaderView(engineSession, config)
            }
        }

        override fun onPortDisconnected(port: Port) {
            ports.remove(port.engineSession)
        }

        override fun onPortMessage(message: Any, port: Port) {
            if (message is JSONObject) {
                session.readerable = message.optBoolean(READERABLE_RESPONSE_MESSAGE_KEY, false)
            }
        }
    }

    @VisibleForTesting
    companion object {
        private val logger = Logger("mozac-readerview")

        internal const val READER_VIEW_EXTENSION_ID = "mozacReaderview"
        internal const val READER_VIEW_EXTENSION_URL = "resource://android/assets/extensions/readerview/"

        // Constants for building messages sent to the web extension:
        // Change the font type: {"action": "setFontType", "value": "sans-serif"}
        // Show reader view: {"action": "show", "value": {"fontSize": 3, "fontType": "serif", "colorScheme": "dark"}}
        internal const val ACTION_MESSAGE_KEY = "action"
        internal const val ACTION_SHOW = "show"
        internal const val ACTION_HIDE = "hide"
        internal const val ACTION_CHECK_READERABLE = "checkReaderable"
        internal const val ACTION_SET_COLOR_SCHEME = "setColorScheme"
        internal const val ACTION_CHANGE_FONT_SIZE = "changeFontSize"
        internal const val ACTION_SET_FONT_TYPE = "setFontType"
        internal const val ACTION_VALUE = "value"
        internal const val ACTION_VALUE_SHOW_FONT_SIZE = "fontSize"
        internal const val ACTION_VALUE_SHOW_FONT_TYPE = "fontType"
        internal const val ACTION_VALUE_SHOW_COLOR_SCHEME = "colorScheme"
        internal const val READERABLE_RESPONSE_MESSAGE_KEY = "readerable"

        // Constants for storing the reader mode config in shared preferences
        internal const val SHARED_PREF_NAME = "mozac_feature_reader_view"
        internal const val COLOR_SCHEME_KEY = "mozac-readerview-colorscheme"
        internal const val FONT_TYPE_KEY = "mozac-readerview-fonttype"
        internal const val FONT_SIZE_KEY = "mozac-readerview-fontsize"
        internal const val FONT_SIZE_DEFAULT = 3

        @Volatile
        internal var installedWebExt: WebExtension? = null

        @Volatile
        private var registerContentMessageHandler: (WebExtension) -> Unit? = { }

        internal var ports = WeakHashMap<EngineSession, Port>()

        /**
         * Installs the readerview web extension in the provided engine.
         *
         * @param engine a reference to the application's browser engine.
         */
        fun install(engine: Engine) {
            engine.installWebExtension(READER_VIEW_EXTENSION_ID, READER_VIEW_EXTENSION_URL,
                onSuccess = {
                    logger.debug("Installed extension: ${it.id}")
                    registerContentMessageHandler(it)
                    installedWebExt = it
                },
                onError = { ext, throwable ->
                    logger.error("Failed to install extension: $ext", throwable)
                }
            )
        }

        fun registerMessageHandler(session: EngineSession, messageHandler: MessageHandler) {
            registerContentMessageHandler = {
                it.registerContentMessageHandler(session, READER_VIEW_EXTENSION_ID, messageHandler)
            }

            installedWebExt?.let { registerContentMessageHandler(it) }
        }

        private fun checkReaderable(engineSession: EngineSession?) {
            engineSession?.let {
                if (portConnected(it)) {
                    sendContentMessage(JSONObject().put(ACTION_MESSAGE_KEY, ACTION_CHECK_READERABLE), it)
                }
            }
        }

        private fun portConnected(engineSession: EngineSession?): Boolean {
            return engineSession?.let { ports.containsKey(it) } ?: false
        }

        private fun sendContentMessage(msg: Any, engineSession: EngineSession?) {
            engineSession?.let {
                val port = ports[it]
                port?.postMessage(msg) ?: logger.error("No port connected for provided session. Message $msg not sent.")
            }
        }

        private fun showReaderView(engineSession: EngineSession?, config: Config) {
            engineSession?.let {
                val configJson = JSONObject()
                        .put(ACTION_VALUE_SHOW_FONT_SIZE, config.fontSize)
                        .put(ACTION_VALUE_SHOW_FONT_TYPE, config.fontType.name.toLowerCase())
                        .put(ACTION_VALUE_SHOW_COLOR_SCHEME, config.colorScheme.name.toLowerCase())

                val message = JSONObject()
                        .put(ACTION_MESSAGE_KEY, ACTION_SHOW)
                        .put(ACTION_VALUE, configJson)

                sendContentMessage(message, engineSession)
            }
        }

        private fun hideReaderView(engineSession: EngineSession?) {
            engineSession?.let {
                sendContentMessage(JSONObject().put(ACTION_MESSAGE_KEY, ACTION_HIDE), engineSession)
            }
        }
    }
}
