package uk.ahmedhome.tv

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import uk.ahmedhome.tv.core.AppLog
import uk.ahmedhome.tv.data.account.AccountStore
import uk.ahmedhome.tv.data.account.SettingsStore
import uk.ahmedhome.tv.data.db.AppDatabase
import uk.ahmedhome.tv.data.repo.LibraryRepository
import uk.ahmedhome.tv.data.sync.LibrarySync
import uk.ahmedhome.tv.data.xtream.CleartextRefusedIOException
import uk.ahmedhome.tv.data.xtream.ProviderConfig
import uk.ahmedhome.tv.data.xtream.XtreamClient
import java.util.concurrent.TimeUnit

/** The signed-in provider account. accountId is a non-secret namespace for local data. */
data class Session(val config: ProviderConfig, val accountId: String, val client: XtreamClient)

/** Hand-wired dependencies; one instance per process. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val accountStore = AccountStore(context)
    val settings = SettingsStore(context)
    val database: AppDatabase by lazy { AppDatabase.create(context) }

    @Volatile private var allowCleartext = false

    /** Single HTTP stack shared by API, artwork and media so limits and the cleartext policy apply everywhere. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply { maxRequests = 16; maxRequestsPerHost = 4 })
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (req.url.scheme == "http" && !allowCleartext) throw CleartextRefusedIOException(req.url.host)
            chain.proceed(req)
        })
        .build()

    val sync: LibrarySync by lazy { LibrarySync(database.catalog(), appScope) }
    val library: LibraryRepository by lazy { LibraryRepository(database) }

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    fun restoreSession(): Session? = accountStore.load()?.let { activate(it) }

    fun activate(config: ProviderConfig): Session {
        allowCleartext = config.allowCleartext
        AppLog.secrets = arrayOf(config.username, config.password)
        val s = Session(config, AccountStore.accountId(config), XtreamClient(http, config))
        _session.value = s
        return s
    }

    fun clientFor(config: ProviderConfig): XtreamClient {
        allowCleartext = config.allowCleartext
        return XtreamClient(http, config)
    }

    /** Sign out: cancel work, forget credentials. Catalogue and user state stay namespaced on device. */
    fun signOut() {
        sync.cancel()
        accountStore.clear()
        _session.value = null
        allowCleartext = false
        AppLog.secrets = emptyArray()
    }
}
