package cn.yiiguxing.plugin.translate.wordbook

import cn.yiiguxing.plugin.translate.TranslationPlugin
import cn.yiiguxing.plugin.translate.adaptedMessage
import cn.yiiguxing.plugin.translate.message
import cn.yiiguxing.plugin.translate.util.*
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowBalloonShowOptions
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.HyperlinkAdapter
import com.intellij.ui.scale.JBUIScale
import org.jetbrains.concurrency.runAsync
import javax.swing.event.HyperlinkEvent

/**
 * Word book tool window factory
 */
class WordBookToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Try to fix: https://github.com/YiiGuxing/TranslationPlugin/issues/1186
        if (project.isDisposed) {
            return
        }
        WordBookView.instance.setup(project, toolWindow)
    }

    override fun init(toolWindow: ToolWindow) {
        adaptedMessage("wordbook.window.title").let { title ->
            toolWindow.title = title
            toolWindow.stripeTitle = title
        }

        val project = (toolWindow as ToolWindowEx).project
        val toolWindowRef = DisposableRef.create(toolWindow.disposable, toolWindow)

        val isStripeButtonShown = toolWindow.isShowStripeButton

        val messageBusConnection = project.messageBus.connect(toolWindow.disposable)
        messageBusConnection.subscribe(RequireWordBookListener.TOPIC, object : RequireWordBookListener {
            override fun onRequire() {
                toolWindowRef.get()?.runIfSurvive {
                    setAvailable(true) {
                        if (isStripeButtonShown) {
                            showStripeButton()
                        } else {
                            isShowStripeButton = true
                            showGotItTooltipIfNeed()
                        }
                        show()
                    }
                }
            }
        })

        if (!isStripeButtonShown) {
            return
        }

        // 这里只是修改了状态的复本，这会导致真实的状态和已存储的状态不一致。见：`ToolWindow.showStripeButton()`
        toolWindow.isShowStripeButton = false

        messageBusConnection.subscribe(WordBookListener.TOPIC, object : WordBookListener {
            override fun onWordsAdded(service: WordBookService, words: List<WordBookItem>) {
                toolWindowRef.showStripeButton()
            }

            override fun onStoragePathChanged(service: WordBookService) {
                toolWindowRef.updateVisible()
            }
        })

        val disposable = Disposer.newDisposable(toolWindow.disposable, "Wordbook tool window availability state")
        WordBookService.instance.stateBinding.observe(disposable) { state, _ ->
            if (state == WordBookState.RUNNING) {
                toolWindowRef.updateVisible()
                Disposer.dispose(disposable)
            }
        }
        if (WordBookService.instance.isInitialized) {
            toolWindowRef.updateVisible()
            Disposer.dispose(disposable)
        }
    }

    private fun ToolWindowEx.showStripeButton() {
        // 由于在`init(ToolWindow)`中设置`isShowStripeButton`为`false`时，只修改了状态的复本，导致真实的状态和记录
        // 的状态（可能为`true`）不同步，直接设置为`true`可能无效，所以这里需要设置为`false`先同步一下状态。
        isShowStripeButton = false
        isShowStripeButton = true
        showGotItTooltipIfNeed()
    }

    private fun ToolWindowEx.showGotItTooltipIfNeed() {
        if (isGotItTooltipDisplayed || PropertiesComponent.getInstance().getBoolean(GOT_IT_TOOLTIP_KEY, false)) {
            isGotItTooltipDisplayed = true
            return
        }

        val toolWindowManager = ToolWindowManager.getInstance(project)
        if (!toolWindowManager.canShowNotification(TOOL_WINDOW_ID)) {
            return
        }

        if (Settings.wordbookStoragePath.isNullOrEmpty()) {
            val balloonLifeCycleDisposable = Disposer.newDisposable(disposable, "Wordbook tool window got it tooltip")
            val options = ToolWindowBalloonShowOptions(
                TOOL_WINDOW_ID,
                MessageType.INFO,
                message("got.it.tooltip.text.wordbook.storage.path", JBUIScale.scale(200)),
                listener = object : HyperlinkAdapter() {
                    override fun hyperlinkActivated(e: HyperlinkEvent) {
                        Disposer.dispose(balloonLifeCycleDisposable)
                        Hyperlinks.handleDefaultHyperlinkActivated(e)
                    }
                },
                balloonCustomizer = {
                    it.setDisposable(balloonLifeCycleDisposable)
                        .setHideOnLinkClick(true)
                        .setCloseButtonEnabled(false)
                        .setAnimationCycle(0)
                        .setFadeoutTime(0)
                }
            )
            toolWindowManager.notifyByBalloon(options)
        }

        PropertiesComponent.getInstance().setValue(GOT_IT_TOOLTIP_KEY, true)
        isGotItTooltipDisplayed = true
    }

    private fun DisposableRef<ToolWindowEx>.showStripeButton() {
        get()?.runIfSurvive { showStripeButton() }
    }

    private fun DisposableRef<ToolWindowEx>.updateVisible() {
        runAsync { with(WordBookService.instance) { isInitialized && hasWords() } }
            .successOnUiThread(this, ModalityState.NON_MODAL) { toolWindow, available ->
                if (available) {
                    toolWindow.showStripeButton()
                }
            }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true

    companion object {
        const val TOOL_WINDOW_ID = "Translation.Wordbook"

        private const val GOT_IT_TOOLTIP_KEY = "${TranslationPlugin.PLUGIN_ID}.got.it.tooltip.wordbook.storage.path"

        private val requirePublisher: RequireWordBookListener by lazy {
            Application.messageBus.syncPublisher(RequireWordBookListener.TOPIC)
        }

        private var isGotItTooltipDisplayed: Boolean = false

        fun requireWordBook() {
            checkDispatchThread { "Must only be invoked from the Event Dispatch Thread." }
            requirePublisher.onRequire()
        }

        private inline fun ToolWindowEx.runIfSurvive(crossinline action: ToolWindowEx.() -> Unit) {
            if (isDisposed) {
                return
            }
            invokeLater(ModalityState.NON_MODAL, project.disposed) {
                if (!isDisposed) {
                    action()
                }
            }
        }
    }
}