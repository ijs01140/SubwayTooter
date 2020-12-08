package jp.juggler.subwaytooter

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import jp.juggler.subwaytooter.api.TootApiCallback
import jp.juggler.subwaytooter.api.TootApiClient
import jp.juggler.subwaytooter.api.TootApiResult
import jp.juggler.subwaytooter.api.TootParser
import jp.juggler.subwaytooter.api.entity.*
import jp.juggler.subwaytooter.table.*
import jp.juggler.subwaytooter.table.NotificationCache.Companion.getEntityOrderId
import jp.juggler.subwaytooter.table.NotificationCache.Companion.parseNotificationType
import jp.juggler.subwaytooter.util.*
import jp.juggler.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.tasks.await
import okhttp3.Call
import okhttp3.Request
import ru.gildor.coroutines.okhttp.await
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

class PollingWorker private constructor(contextArg: Context) {

    enum class TrackingType(val str: String) {
        All("all"),
        Reply("reply"),
        NotReply("notReply");

        companion object {

            fun parseStr(str: String?): TrackingType {
                for (v in values()) {
                    if (v.str == str) return v
                }
                return All
            }
        }

    }

    internal class Data(val access_info: SavedAccount, val notification: TootNotification)

    internal class InjectData {

        var account_db_id: Long = 0
        val list = ArrayList<TootNotification>()
    }

    companion object {

        internal val log = LogCategory("PollingWorker")

        // private const val FCM_SENDER_ID = "433682361381"
        // private const val FCM_SCOPE = "FCM"

        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_ID_ERROR = 3

        val mBusyAppDataImportBefore = AtomicBoolean(false)
        val mBusyAppDataImportAfter = AtomicBoolean(false)

        const val EXTRA_DB_ID = "db_id"
        const val EXTRA_TAG = "tag"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_NOTIFICATION_TYPE = "notification_type"
        const val EXTRA_NOTIFICATION_ID = "notificationId"

        const val APP_SERVER = "https://mastodon-msg.juggler.jp"

        internal val inject_queue = ConcurrentLinkedQueue<InjectData>()

        // ジョブID
        const val JOB_POLLING = 1
        private const val JOB_TASK = 2
        const val JOB_FCM = 3

        // タスクID
        const val TASK_POLLING = 1
        const val TASK_DATA_INJECTED = 2
        const val TASK_NOTIFICATION_CLEAR = 3
        const val TASK_APP_DATA_IMPORT_BEFORE = 4
        const val TASK_APP_DATA_IMPORT_AFTER = 5
        const val TASK_FCM_DEVICE_TOKEN = 6
        const val TASK_FCM_MESSAGE = 7
        const val TASK_BOOT_COMPLETED = 8
        const val TASK_PACKAGE_REPLACED = 9
        const val TASK_NOTIFICATION_DELETE = 10
        const val TASK_NOTIFICATION_CLICK = 11
        private const val TASK_UPDATE_NOTIFICATION = 12

        @SuppressLint("StaticFieldLeak")
        private var sInstance: PollingWorker? = null

        fun getInstance(applicationContext: Context): PollingWorker {
            var s = sInstance
            if (s == null) {
                s = PollingWorker(applicationContext)
                sInstance = s
            }
            return s
        }

        suspend fun getFirebaseMessagingToken(context: Context): String? {
            val prefDevice = PrefDevice.prefDevice(context)
            // 設定ファイルに保持されていたらそれを使う
            prefDevice
                .getString(PrefDevice.KEY_DEVICE_TOKEN, null)
                ?.notEmpty()?.let { return it }

            // 古い形式
            // return FirebaseInstanceId.getInstance().getToken(FCM_SENDER_ID, FCM_SCOPE)

            // com.google.firebase:firebase-messaging.20.3.0 以降
            // implementation "org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$kotlinx_coroutines_version"
            try {
                val sv = FirebaseMessaging.getInstance().token.await()
                return if (sv.isNullOrBlank()) {
                    log.e("getFirebaseMessagingToken: missing device token.")
                    null
                } else {
                    prefDevice
                        .edit()
                        .putString(PrefDevice.KEY_DEVICE_TOKEN, sv)
                        .apply()
                    sv
                }
            } catch (ex: Throwable) {
                log.trace(ex, "getFirebaseMessagingToken: could not get device token.")
                return null
            }
        }


        // インストールIDを生成する前に、各データの通知登録キャッシュをクリアする
        // トークンがまだ生成されていない場合、このメソッドは null を返します。
        @Suppress("BlockingMethodInNonBlockingContext")
        suspend fun prepareInstallId(
            context: Context,
            job: JobItem? = null
        ): String? {
            val prefDevice = PrefDevice.prefDevice(context)

            var sv = prefDevice.getString(PrefDevice.KEY_INSTALL_ID, null)
            if (sv?.isNotEmpty() == true) return sv

            SavedAccount.clearRegistrationCache()

            try {
                val device_token = getFirebaseMessagingToken(context)
                    ?: return null

                val request = Request.Builder()
                    .url("$APP_SERVER/counter")
                    .build()

                val call = App1.ok_http_client.newCall(request)
                job?.currentCall = WeakReference(call)
                val response = call.await()

                val body = response.body?.string()

                if (!response.isSuccessful || body?.isEmpty() != false) {
                    log.e(
                        TootApiClient.formatResponse(
                            response,
                            "getInstallId: get/counter failed."
                        )
                    )
                    return null
                }

                sv = (device_token + UUID.randomUUID() + body).digestSHA256Base64Url()
                prefDevice.edit().putString(PrefDevice.KEY_INSTALL_ID, sv).apply()

                return sv

            } catch (ex: Throwable) {
                log.trace(ex, "prepareInstallId failed.")
            }
            return null
        }

        //////////////////////////////////////////////////////////////////////
        // タスクの管理

        val task_list = TaskList()

        fun scheduleJob(context: Context, job_id: Int) {

            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
                as? JobScheduler
                ?: throw NotImplementedError("missing JobScheduler system service")

            val component = ComponentName(context, PollingService::class.java)

            val builder = JobInfo.Builder(job_id, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)

            if (job_id == JOB_POLLING) {

                val minute = 60000L

                val intervalMillis = max(
                    minute * 5L,
                    minute * Pref.spPullNotificationCheckInterval.toInt(context.pref())
                )

                val flexMillis = max(
                    minute,
                    intervalMillis shr 1
                )

                fun JobInfo.Builder.setPeriodicCompat(intervalMillis: Long, flexMillis: Long) =
                    this.apply {
                        if (Build.VERSION.SDK_INT >= 24) {
                            builder.setPeriodic(intervalMillis, flexMillis)
                        } else {
                            builder.setPeriodic(intervalMillis)
                        }
                    }

                builder
                    .setPeriodicCompat(intervalMillis, flexMillis)
                    .setPersisted(true)

            } else {
                builder
                    .setMinimumLatency(0)
                    .setOverrideDeadline(60000L)
            }
            val jobInfo = builder.build()

            val rv = scheduler.schedule(jobInfo)
            if (rv != JobScheduler.RESULT_SUCCESS) {
                log.w("scheduler.schedule failed. rv=$rv")
            }
        }

        // タスクの追加
        private fun addTask(
            context: Context,
            removeOld: Boolean,
            task_id: Int,
            taskDataArg: JsonObject?
        ) {
            try {
                task_list.addLast(
                    context,
                    removeOld,
                    (taskDataArg ?: JsonObject()).apply {
                        put(EXTRA_TASK_ID, task_id)
                    }
                )
                scheduleJob(context, JOB_TASK)
            } catch (ex: Throwable) {
                log.trace(ex)
            }

        }

        fun queueUpdateNotification(context: Context) {
            addTask(context, true, TASK_UPDATE_NOTIFICATION, null)
        }

        fun injectData(
            context: Context,
            account: SavedAccount,
            src: List<TootNotification>
        ) {

            if (src.isEmpty()) return

            val id = InjectData()
            id.account_db_id = account.db_id
            id.list.addAll(src)
            inject_queue.add(id)

            addTask(context, true, TASK_DATA_INJECTED, null)
        }

        fun queueNotificationCleared(context: Context, db_id: Long) {
            try {
                val data = jsonObject {
                    putNotNull(EXTRA_DB_ID, db_id)
                }
                addTask(context, true, TASK_NOTIFICATION_CLEAR, data)
            } catch (ex: JsonException) {
                log.trace(ex)
            }

        }

        private fun decodeNotificationUri(uri: Uri?): JsonObject? {
            uri ?: return null
            return jsonObject {
                putNotNull(
                    EXTRA_DB_ID,
                    uri.getQueryParameter("db_id")?.toLongOrNull()
                )
                putNotNull(
                    EXTRA_NOTIFICATION_TYPE,
                    uri.getQueryParameter("type")?.notEmpty()
                )
                putNotNull(
                    EXTRA_NOTIFICATION_ID,
                    uri.getQueryParameter("notificationId")?.notEmpty()
                )
            }
        }

        fun queueNotificationDeleted(context: Context, uri: Uri?) {
            try {
                val params = decodeNotificationUri(uri) ?: return
                addTask(context, false, TASK_NOTIFICATION_DELETE, params)
            } catch (ex: JsonException) {
                log.trace(ex)
            }
        }

        fun queueNotificationClicked(context: Context, uri: Uri?) {
            try {
                val params = decodeNotificationUri(uri) ?: return
                addTask(context, true, TASK_NOTIFICATION_CLICK, params)
            } catch (ex: JsonException) {
                log.trace(ex)
            }
        }

        fun queueAppDataImportBefore(context: Context) {
            mBusyAppDataImportBefore.set(true)
            mBusyAppDataImportAfter.set(true)
            addTask(context, false, TASK_APP_DATA_IMPORT_BEFORE, null)
        }

        fun queueAppDataImportAfter(context: Context) {
            addTask(context, false, TASK_APP_DATA_IMPORT_AFTER, null)
        }

        fun queueFCMTokenUpdated(context: Context) {
            addTask(context, true, TASK_FCM_DEVICE_TOKEN, null)
        }

        fun queueBootCompleted(context: Context) {
            addTask(context, true, TASK_BOOT_COMPLETED, null)
        }

        fun queuePackageReplaced(context: Context) {
            addTask(context, true, TASK_PACKAGE_REPLACED, null)
        }

        private val job_status = AtomicReference<String>(null)

        private var workerStatus: String
            get() = job_status.get()
            set(x) {
                log.d("workerStatus:$x")
                job_status.set(x)
            }

        // IntentServiceが作ったスレッドから呼ばれる
        suspend fun handleFCMMessage(
            context: Context,
            tag: String?,
            progress: (String) -> Unit
        ) {
            log.d("handleFCMMessage: start. tag=$tag")

            val time_start = SystemClock.elapsedRealtime()

            // この呼出でIntentServiceがstartForegroundする
            progress("=>")

            // タスクを追加
            task_list.addLast(
                context,
                true,
                JsonObject().apply {
                    this[EXTRA_TASK_ID] = TASK_FCM_MESSAGE
                    if (tag != null) this[EXTRA_TAG] = tag
                }
            )

            progress("==>")

            // 疑似ジョブを開始
            val pw = getInstance(context)

            pw.addJobFCM()

            // 疑似ジョブが終了するまで待機する
            while (true) {
                // ジョブが完了した？
                val now = SystemClock.elapsedRealtime()
                if (!pw.hasJob(JOB_FCM)) {
                    log.d(
                        "handleFCMMessage: JOB_FCM completed. time=%.2f",
                        (now - time_start) / 1000f
                    )
                    break
                }

                // ジョブの状況を通知する
                progress(job_status.get() ?: "(null)")

                // 少し待機
                delay(50L)
            }
        }
    }


    internal val context: Context
    private val appState: AppState
    internal val pref: SharedPreferences
    private val connectivityManager: ConnectivityManager
    internal val notification_manager: NotificationManager
    internal val scheduler: JobScheduler
    private val power_manager: PowerManager?
    private val power_lock: PowerManager.WakeLock
    private val wifi_manager: WifiManager?
    private val wifi_lock: WifiManager.WifiLock

    internal val job_list = LinkedList<JobItem>()

    private val workerNotifier = Channel<Unit>(capacity = Channel.CONFLATED)

    init {
        log.d("init")

        val context = contextArg.applicationContext

        this.context = context

        // クラッシュレポートによると App1.onCreate より前にここを通る場合がある
        // データベースへアクセスできるようにする
        this.appState = App1.prepare(context, "PollingWorker.init")
        this.pref = App1.pref

        this.connectivityManager = systemService(context)
            ?: error("missing ConnectivityManager system service")


        this.notification_manager = systemService(context)
            ?: error("missing NotificationManager system service")

        this.scheduler = systemService(context)
            ?: error("missing JobScheduler system service")

        this.power_manager = systemService(context)
            ?: error("missing PowerManager system service")

        // WifiManagerの取得時はgetApplicationContext を使わないとlintに怒られる
        this.wifi_manager = systemService(context.applicationContext)
            ?: error("missing WifiManager system service")

        power_lock = power_manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            PollingWorker::class.java.name
        )
        power_lock.setReferenceCounted(false)

        wifi_lock = if (Build.VERSION.SDK_INT >= 29) {
            wifi_manager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                PollingWorker::class.java.name
            )
        } else {
            @Suppress("DEPRECATION")
            wifi_manager.createWifiLock(PollingWorker::class.java.name)
        }

        wifi_lock.setReferenceCounted(false)

        GlobalScope.launch(Dispatchers.Default) { worker() }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquirePowerLock() {
        log.d("acquire power lock...")
        try {
            if (!power_lock.isHeld) {
                power_lock.acquire()
            }
        } catch (ex: Throwable) {
            log.trace(ex)
        }

        try {
            if (!wifi_lock.isHeld) {
                wifi_lock.acquire()
            }
        } catch (ex: Throwable) {
            log.trace(ex)
        }
    }

    private fun releasePowerLock() {
        log.d("release power lock...")
        try {
            if (power_lock.isHeld) {
                power_lock.release()
            }
        } catch (ex: Throwable) {
            log.trace(ex)
        }

        try {
            if (wifi_lock.isHeld) {
                wifi_lock.release()
            }
        } catch (ex: Throwable) {
            log.trace(ex)
        }
    }

    private suspend fun worker() {
        workerStatus = "worker start."
        try {
            suspend fun isActive() = coroutineContext[Job]?.isActive == true
            while (isActive()) {
                while (true) {
                    handleJobItem(synchronized(job_list) {
                        for (ji in job_list) {
                            if (ji.mJobCancelled_.get()) continue
                            if (ji.mWorkerAttached.compareAndSet(false, true)) {
                                return@synchronized ji
                            }
                        }
                        null
                    } ?: break)
                }
                try {
                    workerNotifier.receive()
                } catch (ex: ClosedReceiveChannelException) {
                }
            }
        } finally {
            workerStatus = "worker end."
        }
    }

    private suspend fun handleJobItem(item: JobItem) {
        try {
            workerStatus = "start job ${item.jobId}"
            acquirePowerLock()
            try {
                item.run()
            } finally {
                releasePowerLock()
            }
        } catch (ex: Throwable) {
            log.trace(ex)
        } finally {
            workerStatus = "end job ${item.jobId}"
        }
    }

    //////////////////////////////////////////////////////////////////////
    // ジョブの管理

    // JobService#onDestroy から呼ばれる
    fun onJobServiceDestroy() {
        log.d("onJobServiceDestroy")

        synchronized(job_list) {
            val it = job_list.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.jobId != JOB_FCM) {
                    it.remove()
                    item.cancel(false)
                }
            }
        }
    }

    // JobService#onStartJob から呼ばれる
    fun onStartJob(jobService: JobService, params: JobParameters): Boolean {
        val item = JobItem(jobService, params)
        addJob(item, true)
        return true
        // return True if your context needs to process the work (on a separate thread).
        // return False if there's no more work to be done for this job.
    }

    // FCMメッセージイベントから呼ばれる
    private fun hasJob(@Suppress("SameParameterValue") jobId: Int): Boolean {
        synchronized(job_list) {
            for (item in job_list) {
                if (item.jobId == jobId) return true
            }
        }
        return false
    }

    // FCMメッセージイベントから呼ばれる
    private fun addJobFCM() {
        addJob(JobItem(JOB_FCM), false)
    }

    private fun addJob(item: JobItem, bRemoveOld: Boolean) {
        val jobId = item.jobId

        // 同じジョブ番号がジョブリストにあるか？
        synchronized(job_list) {
            if (bRemoveOld) {
                val it = job_list.iterator()
                while (it.hasNext()) {
                    val itemOld = it.next()
                    if (itemOld.jobId == jobId) {
                        log.w("addJob: jobId=$jobId, old job cancelled.")
                        // 同じジョブをすぐに始めるのだからrescheduleはfalse
                        itemOld.cancel(false)
                        it.remove()
                    }
                }
            }
            log.d("addJob: jobId=$jobId, add to list.")
            job_list.add(item)
        }

        GlobalScope.launch(Dispatchers.Default){
            workerNotifier.send(Unit)
        }
    }

    // JobService#onStopJob から呼ばれる
    fun onStopJob(params: JobParameters): Boolean {
        val jobId = params.jobId

        // 同じジョブ番号がジョブリストにあるか？
        synchronized(job_list) {
            val it = job_list.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.jobId == jobId) {
                    log.w("onStopJob: jobId=${jobId}, set cancel flag.")
                    // リソースがなくてStopされるのだからrescheduleはtrue
                    item.cancel(true)
                    it.remove()
                    return item.mReschedule.get()
                }
            }
        }

        // 該当するジョブを依頼されていない
        log.w("onStopJob: jobId=${jobId}, not started..")
        return false
        // return True to indicate to the JobManager whether you'd like to reschedule this job based on the retry criteria provided at job creation-time.
        // return False to drop the job. Regardless of the value returned, your job must stop executing.
    }

    internal class JobCancelledException : RuntimeException("job is cancelled.")

    inner class JobItem {

        val jobId: Int
        private val refJobService: WeakReference<JobService>?
        private val jobParams: JobParameters?
        val mJobCancelled_ = AtomicBoolean()
        val mReschedule = AtomicBoolean()
        val mWorkerAttached = AtomicBoolean()

        val bPollingRequired = AtomicBoolean(false)
        lateinit var muted_app: HashSet<String>
        lateinit var muted_word: WordTrieTree
        lateinit var favMuteSet: HashSet<Acct>
        var bPollingComplete = false
        var install_id: String? = null

        var currentCall: WeakReference<Call>? = null

        val isJobCancelled: Boolean
            get() = mJobCancelled_.get()

        constructor(jobService: JobService, params: JobParameters) {
            this.jobParams = params
            this.jobId = params.jobId
            this.refJobService = WeakReference(jobService)
        }

        constructor(jobId: Int) {
            this.jobId = jobId
            this.jobParams = null
            this.refJobService = null
        }

        fun cancel(bReschedule: Boolean) {
            mJobCancelled_.set(true)
            mReschedule.set(bReschedule)
            currentCall?.get()?.cancel()
            runBlocking { workerNotifier.send(Unit) }
        }

        suspend fun run() = coroutineScope {
            workerStatus = "job start."
            try {
                log.d("(JobItem.run jobId=${jobId}")
                if (isJobCancelled) throw JobCancelledException()

                workerStatus = "check network status.."

                var connectionState: String? = null
                try {
                    withTimeout(10000L) {
                        while (true) {
                            if (isJobCancelled) throw JobCancelledException()
                            connectionState = App1.getAppState(context, "PollingWorker.JobItem.run()")
                                .networkTracker.connectionState
                                ?: break // null if connected
                            delay(333L)
                        }
                    }
                } catch (ex: TimeoutCancellationException) {
                    log.d("network state timeout. $connectionState")
                }

                muted_app = MutedApp.nameSet
                muted_word = MutedWord.nameSet
                favMuteSet = FavMute.acctSet

                // タスクがあれば処理する
                while (true) {
                    if (isJobCancelled) throw JobCancelledException()
                    val data = task_list.next(context) ?: break
                    val task_id = data.optInt(EXTRA_TASK_ID, 0)
                    TaskRunner().runTask(this@JobItem, task_id, data)
                }

                if (!isJobCancelled && !bPollingComplete && jobId == JOB_POLLING) {
                    // タスクがなかった場合でも定期実行ジョブからの実行ならポーリングを行う
                    TaskRunner().runTask(this@JobItem, TASK_POLLING, JsonObject())
                }

                workerStatus = "make next schedule."

                log.d("pollingComplete=${bPollingComplete},isJobCancelled=${isJobCancelled},bPollingRequired=${bPollingRequired.get()}")

                if (!isJobCancelled && bPollingComplete) {
                    // ポーリングが完了した
                    if (!bPollingRequired.get()) {
                        // Pull通知を必要とするアカウントが存在しないなら、スケジュール登録を解除する
                        log.d("polling job is no longer required.")
                        try {
                            scheduler.cancel(JOB_POLLING)
                        } catch (ex: Throwable) {
                            log.trace(ex)
                        }
                    } else if (!scheduler.allPendingJobs.any { it.id == JOB_POLLING }) {
                        // まだスケジュールされてないなら登録する
                        log.d("registering polling job…")
                        scheduleJob(context, JOB_POLLING)
                    }
                }
            } catch (ex: JobCancelledException) {
                log.e("job execution cancelled.")
            } catch (ex: Throwable) {
                log.trace(ex)
                log.e(ex, "job execution failed.")
            } finally {
                workerStatus = "job finished."
            }

            log.d(")JobItem.run jobId=${jobId}, cancel=${isJobCancelled}")

            launch(Dispatchers.Main) {
                if (isJobCancelled) return@launch

                synchronized(job_list) {
                    job_list.remove(this@JobItem)
                }

                refJobService?.get()?.let { jobService ->
                    try {
                        // ジョブ終了報告
                        val willReschedule = mReschedule.get()
                        log.d("sending jobFinished. willReschedule=$willReschedule")
                        jobService.jobFinished(jobParams, willReschedule)
                    } catch (ex: Throwable) {
                        log.trace(ex, "jobFinished failed(1).")
                    }
                }
            }
        }
    }


    private fun TrackingType.trackingTypeName() = when (this) {
        TrackingType.NotReply -> NotificationHelper.TRACKING_NAME_DEFAULT
        TrackingType.Reply -> NotificationHelper.TRACKING_NAME_REPLY
        TrackingType.All -> NotificationHelper.TRACKING_NAME_DEFAULT
    }

    internal inner class TaskRunner {

        lateinit var job: JobItem
        private var taskId: Int = 0

        val error_instance = ArrayList<String>()

        suspend fun runTask(job: JobItem, taskId: Int, taskData: JsonObject) {
            workerStatus = "start task $taskId"

            this.job = job
            this.taskId = taskId
            var process_db_id = -1L //

            coroutineScope {
                try {
                    when (taskId) {
                        TASK_APP_DATA_IMPORT_BEFORE -> {
                            scheduler.cancelAll()
                            for (a in SavedAccount.loadAccountList(context)) {
                                try {
                                    val notification_tag = a.db_id.toString()
                                    notification_manager.cancel(notification_tag, NOTIFICATION_ID)
                                } catch (ex: Throwable) {
                                    log.trace(ex)
                                }

                            }
                            mBusyAppDataImportBefore.set(false)
                            return@coroutineScope
                        }

                        TASK_APP_DATA_IMPORT_AFTER -> {
                            mBusyAppDataImportAfter.set(false)
                            mBusyAppDataImportBefore.set(false)
                            NotificationTracking.resetPostAll()
                            // fall
                        }

                    }

                    // アプリデータのインポート処理がビジーな間、他のジョブは実行されない
                    if (mBusyAppDataImportBefore.get() || mBusyAppDataImportAfter.get())
                        return@coroutineScope

                    // タスクによってはポーリング前にすることがある
                    when (taskId) {
                        TASK_DATA_INJECTED -> processInjectedData()

                        TASK_BOOT_COMPLETED -> NotificationTracking.resetPostAll()

                        TASK_PACKAGE_REPLACED -> NotificationTracking.resetPostAll()

                        // デバイストークンが更新された
                        TASK_FCM_DEVICE_TOKEN -> {
                        }

                        // プッシュ通知が届いた
                        TASK_FCM_MESSAGE -> {
                            var bDone = false
                            val tag = taskData.string(EXTRA_TAG)
                            if (tag != null) {
                                if (tag.startsWith("acct<>")) {
                                    val acct = tag.substring(6)
                                    val sa = SavedAccount.loadAccountByAcct(context, acct)
                                    if (sa != null) {
                                        NotificationCache.resetLastLoad(sa.db_id)
                                        process_db_id = sa.db_id
                                        bDone = true
                                    }
                                }
                                if (!bDone) {
                                    for (sa in SavedAccount.loadByTag(context, tag)) {
                                        NotificationCache.resetLastLoad(sa.db_id)
                                        process_db_id = sa.db_id
                                        bDone = true
                                    }
                                }
                            }
                            if (!bDone) {
                                // タグにマッチする情報がなかった場合、全部読み直す
                                NotificationCache.resetLastLoad()
                            }
                        }

                        TASK_NOTIFICATION_CLEAR -> {
                            val db_id = taskData.long(EXTRA_DB_ID)
                            log.d("Notification clear! db_id=$db_id")
                            if (db_id != null) {
                                deleteCacheData(db_id)
                            }
                        }

                        TASK_NOTIFICATION_DELETE -> {
                            val db_id = taskData.long(EXTRA_DB_ID)
                            val type = TrackingType.parseStr(taskData.string(EXTRA_NOTIFICATION_TYPE))
                            val typeName = type.trackingTypeName()
                            val id = taskData.string(EXTRA_NOTIFICATION_ID)
                            log.d("Notification deleted! db_id=$db_id,type=$type,id=$id")
                            if (db_id != null) {
                                NotificationTracking.updateRead(db_id, typeName)
                            }
                            return@coroutineScope
                        }

                        TASK_NOTIFICATION_CLICK -> {
                            val db_id = taskData.long(EXTRA_DB_ID)
                            val type = TrackingType.parseStr(taskData.string(EXTRA_NOTIFICATION_TYPE))
                            val typeName = type.trackingTypeName()
                            val id = taskData.string(EXTRA_NOTIFICATION_ID).notEmpty()
                            log.d("Notification clicked! db_id=$db_id,type=$type,id=$id")
                            if (db_id != null) {
                                // 通知をキャンセル
                                val notification_tag = when (typeName) {
                                    "" -> "${db_id}/_"
                                    else -> "${db_id}/$typeName"
                                }
                                if (id != null) {
                                    val itemTag = "$notification_tag/$id"
                                    notification_manager.cancel(itemTag, NOTIFICATION_ID)
                                } else {
                                    notification_manager.cancel(notification_tag, NOTIFICATION_ID)
                                }
                                // DB更新処理
                                NotificationTracking.updateRead(db_id, typeName)
                            }
                            return@coroutineScope
                        }
                    }

                    workerStatus = "make install id"

                    // インストールIDを生成する
                    // インストールID生成時にSavedAccountテーブルを操作することがあるので
                    // アカウントリストの取得より先に行う
                    if (job.install_id == null) {
                        job.install_id = prepareInstallId(context, job)
                    }

                    // アカウント別に処理スレッドを作る
                    workerStatus = "create account thread"
                    val thread_list = LinkedList<AccountRunner>()
                    suspend fun startForAccount(_a: SavedAccount) {
                        if (_a.isPseudo) return
                        thread_list.add(AccountRunner(_a).apply { start() })
                    }
                    if (process_db_id != -1L) {
                        // process_db_id が指定されているなら、そのdb_idだけ処理する
                        SavedAccount.loadAccount(context, process_db_id)?.let { startForAccount(it) }
                    } else {
                        // 全てのアカウントを処理する
                        SavedAccount.loadAccountList(context).forEach { startForAccount(it) }
                    }

                    while (true) {
                        // 同じホスト名が重複しないようにSetに集める
                        val liveSet = TreeSet<Host>()
                        for (t in thread_list) {
                            if (!t.isActive) continue
                            if (job.isJobCancelled) t.cancel()
                            liveSet.add(t.account.apiHost)
                        }
                        if (liveSet.isEmpty()) break
                        workerStatus = "waiting ${liveSet.joinToString(", ") { it.pretty }}"
                        delay(if (job.isJobCancelled) 100L else 1000L)
                    }

                    synchronized(error_instance) {
                        createErrorNotification(error_instance)
                    }

                    if (!job.isJobCancelled) job.bPollingComplete = true

                } catch (ex: Throwable) {
                    log.trace(ex, "task execution failed.")
                } finally {
                    log.d(")runTask: taskId=$taskId")
                    workerStatus = "end task $taskId"
                }
            }
        }

        internal inner class AccountRunner(val account: SavedAccount) {

            private var suspendJob: Job? = null

            private lateinit var parser: TootParser

            private lateinit var cache: NotificationCache

            private var currentCall: WeakReference<Call>? = null

            ///////////////////

            val isActive: Boolean
                get() = suspendJob?.isActive ?: true

            private val onCallCreated: (Call) -> Unit =
                { currentCall = WeakReference(it) }

            private val client = TootApiClient(context, callback = object : TootApiCallback {
                override val isApiCancelled: Boolean
                    get() = job.isJobCancelled || (suspendJob?.isCancelled == true)
            }).apply {
                currentCallCallback = onCallCreated
            }

            private val favMuteSet: HashSet<Acct> get() = job.favMuteSet

            fun cancel() {
                try {
                    currentCall?.get()?.cancel()
                } catch (ex: Throwable) {
                    log.trace(ex)
                }
            }


            suspend fun start() {
                coroutineScope {
                    this@AccountRunner.suspendJob = launch(Dispatchers.IO) {
                        runSuspend()
                    }
                }
            }

            private val onError: (TootApiResult) -> Unit = { result ->
                val sv = result.error
                if (sv?.contains("Timeout") == true && !account.dont_show_timeout) {
                    synchronized(error_instance) {
                        if (!error_instance.any { it == sv }) error_instance.add(sv)
                    }
                }
            }

            private suspend fun runSuspend() {
                try {
                    // 疑似アカウントはチェック対象外
                    if (account.isPseudo) return

                    // 未確認アカウントはチェック対象外
                    if (!account.isConfirmed) return

                    client.account = account

                    val wps = PushSubscriptionHelper(context, account)

                    if (wps.flags != 0) {
                        job.bPollingRequired.set(true)

                        val (instance, instanceResult) = TootInstance.get(client)
                        if (instance == null) {
                            if (instanceResult != null) {
                                log.e("${instanceResult.error} ${instanceResult.requestInfo}".trim())
                                account.updateNotificationError("${instanceResult.error} ${instanceResult.requestInfo}".trim())
                            }
                            return
                        }

                        if (job.isJobCancelled) return
                    }

                    wps.updateSubscription(client) ?: return // cancelled.

                    val wps_log = wps.log
                    if (wps_log.isNotEmpty())
                        log.d("PushSubscriptionHelper: ${account.acct.pretty} $wps_log")

                    if (job.isJobCancelled) return

                    if (wps.flags == 0) {
                        if (account.last_notification_error != null) {
                            account.updateNotificationError(null)
                        }
                        return
                    }

                    this.cache = NotificationCache(account.db_id).apply {
                        load()
                        requestAsync(
                            client,
                            account,
                            wps.flags,
                            onError = onError,
                            isCancelled = { job.isJobCancelled }
                        )
                    }

                    if (job.isJobCancelled) return

                    this.parser = TootParser(context, account)

                    if (Pref.bpSeparateReplyNotificationGroup(pref)) {
                        var tr = TrackingRunner(
                            trackingType = TrackingType.NotReply,
                            trackingName = NotificationHelper.TRACKING_NAME_DEFAULT
                        )
                        tr.checkAccount()
                        if (job.isJobCancelled) return
                        tr.updateNotification()
                        //
                        tr = TrackingRunner(
                            trackingType = TrackingType.Reply,
                            trackingName = NotificationHelper.TRACKING_NAME_REPLY
                        )
                        tr.checkAccount()
                        if (job.isJobCancelled) return
                        tr.updateNotification()

                    } else {
                        val tr = TrackingRunner(
                            trackingType = TrackingType.All,
                            trackingName = NotificationHelper.TRACKING_NAME_DEFAULT
                        )
                        tr.checkAccount()
                        if (job.isJobCancelled) return
                        tr.updateNotification()
                    }

                } catch (ex: Throwable) {
                    log.trace(ex)
                } finally {
                    workerNotifier.send(Unit)
                }
            }

            inner class TrackingRunner(
                var trackingType: TrackingType = TrackingType.All,
                var trackingName: String = ""
            ) {

                private lateinit var nr: NotificationTracking
                private val duplicate_check = HashSet<EntityId>()
                private val dstListData = LinkedList<Data>()

                internal fun checkAccount() {

                    this.nr = NotificationTracking.load(account.db_id, trackingName)

                    val jsonList = when (trackingType) {
                        TrackingType.All -> cache.data
                        TrackingType.Reply -> cache.data.filter {
                            when (parseNotificationType(account, it)) {
                                TootNotification.TYPE_REPLY, TootNotification.TYPE_MENTION -> true
                                else -> false
                            }
                        }
                        TrackingType.NotReply -> cache.data.filter {
                            !when (parseNotificationType(account, it)) {
                                TootNotification.TYPE_REPLY, TootNotification.TYPE_MENTION -> true
                                else -> false
                            }
                        }
                    }

                    // 新しい順に並んでいる。先頭から10件までを処理する。ただし処理順序は古い方から
                    val size = min(10, jsonList.size)
                    for (i in (0 until size).reversed()) {
                        if (job.isJobCancelled) return
                        update_sub(jsonList[i])
                    }
                    if (job.isJobCancelled) return

                    // 種別チェックより先に、cache中の最新のIDを「最後に表示した通知」に指定する
                    // nid_show は通知タップ時に参照されるので、通知を表示する際は必ず更新・保存する必要がある
                    // 種別チェックより優先する
                    if (cache.sinceId != null) nr.nid_show = cache.sinceId
                    nr.save()
                }

                private fun update_sub(src: JsonObject) {

                    val id = getEntityOrderId(account, src)
                    if (id.isDefault || duplicate_check.contains(id)) return
                    duplicate_check.add(id)

                    // タップ・削除した通知のIDと同じか古いなら対象外
                    if (!id.isNewerThan(nr.nid_read)) return

                    log.d("update_sub: found data that id=${id}, > read id ${nr.nid_read}")

                    val notification = parser.notification(src) ?: return

                    // アプリミュートと単語ミュート
                    if (notification.status?.checkMuted() == true) return

                    // ふぁぼ魔ミュート
                    when (notification.type) {
                        TootNotification.TYPE_REBLOG,
                        TootNotification.TYPE_FAVOURITE,
                        TootNotification.TYPE_FOLLOW,
                        TootNotification.TYPE_FOLLOW_REQUEST,
                        TootNotification.TYPE_FOLLOW_REQUEST_MISSKEY -> {
                            val who = notification.account
                            if (who != null && favMuteSet.contains(account.getFullAcct(who))) {
                                log.d("${account.getFullAcct(who)} is in favMuteSet.")
                                return
                            }
                        }
                    }

                    // 後から処理したものが先頭に来る
                    dstListData.add(0, Data(account, notification))
                }

                internal fun updateNotification() {


                    val notification_tag = when (trackingName) {
                        "" -> "${account.db_id}/_"
                        else -> "${account.db_id}/$trackingName"
                    }

                    val nt = NotificationTracking.load(account.db_id, trackingName)
                    val dataList = dstListData
                    val first = dataList.firstOrNull()
                    if (first == null) {
                        log.d("showNotification[${account.acct.pretty}/$notification_tag] cancel notification.")
                        if (Build.VERSION.SDK_INT >= 23 && Pref.bpDivideNotification(pref)) {
                            notification_manager.activeNotifications?.forEach {
                                if (it != null &&
                                    it.id == NOTIFICATION_ID &&
                                    it.tag.startsWith("$notification_tag/")
                                ) {
                                    log.d("cancel: ${it.tag} context=${account.acct.pretty} $notification_tag")
                                    notification_manager.cancel(it.tag, NOTIFICATION_ID)
                                }
                            }
                        } else {
                            notification_manager.cancel(notification_tag, NOTIFICATION_ID)
                        }
                        return
                    }

                    val lastPostTime = nt.post_time
                    val lastPostId = nt.post_id
                    if (first.notification.time_created_at == lastPostTime
                        && first.notification.id == lastPostId
                    ) {
                        // 先頭にあるデータが同じなら、通知を更新しない
                        // このマーカーは端末再起動時にリセットされるので、再起動後は通知が出るはず
                        log.d("showNotification[${account.acct.pretty}] id=${first.notification.id} is already shown.")
                        return
                    }

                    if (Build.VERSION.SDK_INT >= 23 && Pref.bpDivideNotification(pref)) {
                        val activeNotificationMap = HashMap<String, StatusBarNotification>().apply {
                            notification_manager.activeNotifications?.forEach {
                                if (it != null &&
                                    it.id == NOTIFICATION_ID &&
                                    it.tag.startsWith("$notification_tag/")
                                ) {
                                    put(it.tag, it)
                                }
                            }
                        }
                        for (item in dstListData.reversed()) {
                            val itemTag = "$notification_tag/${item.notification.id}"

                            if (lastPostId != null &&
                                item.notification.time_created_at <= lastPostTime &&
                                item.notification.id <= lastPostId
                            ) {
                                // 掲載済みデータより古い通知は再表示しない
                                log.d("ignore $itemTag ${item.notification.time_created_at} <= $lastPostTime && ${item.notification.id} <= $lastPostId")
                                continue
                            }

                            // ignore if already showing
                            if (activeNotificationMap.remove(itemTag) != null) {
                                log.d("ignore $itemTag is in activeNotificationMap")
                                continue
                            }

                            createNotification(
                                itemTag,
                                notificationId = item.notification.id.toString()
                            ) { builder ->

                                builder.setWhen(item.notification.time_created_at)

                                val summary = getNotificationLine(item)
                                builder.setContentTitle(summary)
                                val content = item.notification.status?.decoded_content?.notEmpty()
                                if (content != null) {
                                    builder.setStyle(
                                        NotificationCompat.BigTextStyle()
                                            .setBigContentTitle(summary)
                                            .setSummaryText(item.access_info.acct.pretty)
                                            .bigText(content)
                                    )
                                } else {
                                    builder.setContentText(item.access_info.acct.pretty)
                                }

                                if (Build.VERSION.SDK_INT < 26) {
                                    var iv = 0

                                    if (Pref.bpNotificationSound(pref)) {

                                        var sound_uri: Uri? = null

                                        try {
                                            val whoAcct =
                                                account.getFullAcct(item.notification.account)
                                            sound_uri =
                                                AcctColor.getNotificationSound(whoAcct).mayUri()
                                        } catch (ex: Throwable) {
                                            log.trace(ex)
                                        }

                                        if (sound_uri == null) {
                                            sound_uri = account.sound_uri.mayUri()
                                        }

                                        var bSoundSet = false
                                        if (sound_uri != null) {
                                            try {
                                                builder.setSound(sound_uri)
                                                bSoundSet = true
                                            } catch (ex: Throwable) {
                                                log.trace(ex)
                                            }

                                        }
                                        if (!bSoundSet) {
                                            iv = iv or NotificationCompat.DEFAULT_SOUND
                                        }
                                    }

                                    if (Pref.bpNotificationVibration(pref)) {
                                        iv = iv or NotificationCompat.DEFAULT_VIBRATE
                                    }

                                    if (Pref.bpNotificationLED(pref)) {
                                        iv = iv or NotificationCompat.DEFAULT_LIGHTS
                                    }

                                    builder.setDefaults(iv)
                                }
                            }
                        }
                        // リストにない通知は消さない。ある通知をユーザが指で削除した際に他の通知が残ってほしい場合がある
                    } else {
                        log.d("showNotification[${account.acct.pretty}] creating notification(1)")
                        createNotification(notification_tag) { builder ->

                            builder.setWhen(first.notification.time_created_at)

                            var a = getNotificationLine(first)

                            if (dataList.size == 1) {
                                builder.setContentTitle(a)
                                builder.setContentText(account.acct.pretty)
                            } else {
                                val header =
                                    context.getString(R.string.notification_count, dataList.size)
                                builder.setContentTitle(header)
                                    .setContentText(a)

                                val style = NotificationCompat.InboxStyle()
                                    .setBigContentTitle(header)
                                    .setSummaryText(account.acct.pretty)
                                for (i in 0..4) {
                                    if (i >= dataList.size) break
                                    val item = dataList[i]
                                    a = getNotificationLine(item)
                                    style.addLine(a)
                                }
                                builder.setStyle(style)
                            }

                            if (Build.VERSION.SDK_INT < 26) {

                                var iv = 0

                                if (Pref.bpNotificationSound(pref)) {

                                    var sound_uri: Uri? = null

                                    try {
                                        val whoAcct =
                                            account.getFullAcct(first.notification.account)
                                        sound_uri = AcctColor.getNotificationSound(whoAcct).mayUri()
                                    } catch (ex: Throwable) {
                                        log.trace(ex)
                                    }

                                    if (sound_uri == null) {
                                        sound_uri = account.sound_uri.mayUri()
                                    }

                                    var bSoundSet = false
                                    if (sound_uri != null) {
                                        try {
                                            builder.setSound(sound_uri)
                                            bSoundSet = true
                                        } catch (ex: Throwable) {
                                            log.trace(ex)
                                        }

                                    }
                                    if (!bSoundSet) {
                                        iv = iv or NotificationCompat.DEFAULT_SOUND
                                    }
                                }

                                if (Pref.bpNotificationVibration(pref)) {
                                    iv = iv or NotificationCompat.DEFAULT_VIBRATE
                                }

                                if (Pref.bpNotificationLED(pref)) {
                                    iv = iv or NotificationCompat.DEFAULT_LIGHTS
                                }

                                builder.setDefaults(iv)
                            }
                        }
                    }
                    nt.updatePost(first.notification.id, first.notification.time_created_at)
                }

                private fun createNotification(
                    notification_tag: String,
                    notificationId: String? = null,
                    setContent: (builder: NotificationCompat.Builder) -> Unit
                ) {
                    log.d("showNotification[${account.acct.pretty}] creating notification(1)")

                    val builder = if (Build.VERSION.SDK_INT >= 26) {
                        // Android 8 から、通知のスタイルはユーザが管理することになった
                        // NotificationChannel を端末に登録しておけば、チャネルごとに管理画面が作られる
                        val channel = NotificationHelper.createNotificationChannel(
                            context,
                            account,
                            trackingName
                        )
                        NotificationCompat.Builder(context, channel.id)
                    } else {
                        NotificationCompat.Builder(context, "not_used")
                    }

                    builder.apply {

                        val params = listOf(
                            "db_id" to account.db_id.toString(),
                            "type" to trackingType.str,
                            "notificationId" to notificationId
                        ).mapNotNull {
                            val second = it.second
                            if (second == null) {
                                null
                            } else {
                                "${it.first.encodePercent()}=${second.encodePercent()}"
                            }
                        }.joinToString("&")

                        setContentIntent(
                            PendingIntent.getActivity(
                                context,
                                257,
                                Intent(context, ActCallback::class.java).apply {
                                    data =
                                        "subwaytooter://notification_click/?$params".toUri()

                                    // FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY を付与してはいけない
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        )

                        setDeleteIntent(
                            PendingIntent.getBroadcast(
                                context,
                                257,
                                Intent(context, EventReceiver::class.java).apply {
                                    action = EventReceiver.ACTION_NOTIFICATION_DELETE
                                    data =
                                        "subwaytooter://notification_delete/?$params".toUri()
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT
                            )
                        )

                        setAutoCancel(true)

                        // 常に白テーマのアイコンを使う
                        setSmallIcon(R.drawable.ic_notification)

                        // 常に白テーマの色を使う
                        builder.color = ContextCompat.getColor(context, R.color.Light_colorAccent)

                        // Android 7.0 ではグループを指定しないと勝手に通知が束ねられてしまう。
                        // 束ねられた通知をタップしても pi_click が実行されないので困るため、
                        // アカウント別にグループキーを設定する
                        setGroup(context.packageName + ":" + account.acct.ascii)

                    }

                    log.d("showNotification[${account.acct.pretty}] creating notification(3)")

                    setContent(builder)

                    log.d("showNotification[${account.acct.pretty}] set notification...")

                    notification_manager.notify(notification_tag, NOTIFICATION_ID, builder.build())
                }
            }
        }
    }


    private fun getNotificationLine(item: Data): String {

        val name = when (Pref.bpShowAcctInSystemNotification(pref)) {
            false -> item.notification.accountRef?.decoded_display_name

            true -> {
                val acctPretty = item.notification.accountRef?.get()?.acct?.pretty
                if (acctPretty?.isNotEmpty() == true) {
                    "@$acctPretty"
                } else {
                    null
                }
            }
        } ?: "?"

        return "- " + when (item.notification.type) {
            TootNotification.TYPE_MENTION,
            TootNotification.TYPE_REPLY ->
                context.getString(R.string.display_name_replied_by, name)

            TootNotification.TYPE_RENOTE,
            TootNotification.TYPE_REBLOG ->
                context.getString(R.string.display_name_boosted_by, name)

            TootNotification.TYPE_QUOTE ->
                context.getString(R.string.display_name_quoted_by, name)

            TootNotification.TYPE_STATUS ->
                context.getString(R.string.display_name_posted_by, name)

            TootNotification.TYPE_FOLLOW ->
                context.getString(R.string.display_name_followed_by, name)

            TootNotification.TYPE_UNFOLLOW ->
                context.getString(R.string.display_name_unfollowed_by, name)

            TootNotification.TYPE_FAVOURITE ->
                context.getString(R.string.display_name_favourited_by, name)

            TootNotification.TYPE_REACTION ->
                context.getString(R.string.display_name_reaction_by, name)

            TootNotification.TYPE_VOTE,
            TootNotification.TYPE_POLL_VOTE_MISSKEY ->
                context.getString(R.string.display_name_voted_by, name)

            TootNotification.TYPE_FOLLOW_REQUEST,
            TootNotification.TYPE_FOLLOW_REQUEST_MISSKEY ->
                context.getString(R.string.display_name_follow_request_by, name)

            TootNotification.TYPE_FOLLOW_REQUEST_ACCEPTED_MISSKEY ->
                context.getString(R.string.display_name_follow_request_accepted_by, name)

            TootNotification.TYPE_POLL ->
                context.getString(R.string.end_of_polling_from, name)

            else -> "?"
        }
    }

    private fun processInjectedData() {
        while (true) {
            val data = inject_queue.poll() ?: break
            val account = SavedAccount.loadAccount(context, data.account_db_id) ?: continue
            val list = data.list
            NotificationCache(data.account_db_id).apply {
                load()
                inject(account, list)
            }
        }
    }

    private fun deleteCacheData(db_id: Long) {
        SavedAccount.loadAccount(context, db_id) ?: return
        NotificationCache.deleteCache(db_id)
    }

    private fun createErrorNotification(error_instance: ArrayList<String>) {
        if (error_instance.isEmpty()) {
            return
        }

        // 通知タップ時のPendingIntent
        val intent_click = Intent(context, ActCallback::class.java)
        // FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY を付与してはいけない
        intent_click.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi_click = PendingIntent.getActivity(
            context,
            3,
            intent_click,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            // Android 8 から、通知のスタイルはユーザが管理することになった
            // NotificationChannel を端末に登録しておけば、チャネルごとに管理画面が作られる
            val channel = NotificationHelper.createNotificationChannel(
                context,
                "ErrorNotification",
                "Error",
                null,
                2 /* NotificationManager.IMPORTANCE_LOW */
            )
            NotificationCompat.Builder(context, channel.id)
        } else {
            NotificationCompat.Builder(context, "not_used")
        }

        builder
            .setContentIntent(pi_click)
            .setAutoCancel(true)
            .setSmallIcon(R.drawable.ic_notification) // ここは常に白テーマのアイコンを使う
            .setColor(
                ContextCompat.getColor(
                    context,
                    R.color.Light_colorAccent
                )
            ) // ここは常に白テーマの色を使う
            .setWhen(System.currentTimeMillis())
            .setGroup(context.packageName + ":" + "Error")

        run {
            val header = context.getString(R.string.error_notification_title)
            val summary = context.getString(R.string.error_notification_summary)

            builder
                .setContentTitle(header)
                .setContentText(summary + ": " + error_instance[0])

            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(header)
                .setSummaryText(summary)
            for (i in 0..4) {
                if (i >= error_instance.size) break
                style.addLine(error_instance[i])
            }
            builder.setStyle(style)
        }
        notification_manager.notify(NOTIFICATION_ID_ERROR, builder.build())
    }
}