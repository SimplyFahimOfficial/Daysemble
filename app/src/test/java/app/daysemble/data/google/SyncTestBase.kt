package app.daysemble.data.google

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.daysemble.core.time.FixedAppClock
import app.daysemble.data.db.DaysembleDatabase
import app.daysemble.data.google.auth.TokenProvider
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.data.sync.ChangeListener
import app.daysemble.data.sync.Outbox
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Before
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/** Routes requests by "METHOD path-prefix" to canned handlers and records every request. */
class Router : Dispatcher() {
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableListOf<Pair<(RecordedRequest) -> Boolean, (RecordedRequest) -> MockResponse>>()

    fun on(method: String, pathPrefix: String, handler: (RecordedRequest) -> MockResponse) {
        routes.add(0, { r: RecordedRequest -> r.method == method && (r.url.encodedPath).startsWith(pathPrefix) } to handler)
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        return routes.firstOrNull { it.first(request) }?.second?.invoke(request) ?: MockResponse.Builder().code(404).body("{}").build()
    }

    fun count(method: String, pathPrefix: String) = requests.count { it.method == method && it.url.encodedPath.startsWith(pathPrefix) }
}

fun json(code: Int = 200, body: String) = MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

class FakeTokens : TokenProvider {
    var tokens = ArrayDeque(listOf("t1", "t2", "t3"))
    val invalidated = mutableListOf<String>()
    var current: String? = null
    override suspend fun token(): String? { if (current == null) current = tokens.removeFirstOrNull(); return current }
    override suspend fun invalidate(token: String) { invalidated += token; if (current == token) current = null }
}

abstract class SyncTestBase {
    lateinit var server: MockWebServer
    val router = Router()
    lateinit var db: DaysembleDatabase
    lateinit var http: GoogleHttp
    val tokens = FakeTokens()
    val zone: ZoneId = ZoneId.of("Europe/Berlin")
    val clock = FixedAppClock(Instant.parse("2026-10-22T08:00:00Z"), zone)
    lateinit var events: EventRepository
    lateinit var tasks: TaskRepository

    fun base(path: String) = server.url(path).toString()

    @Before fun startServer() {
        server = MockWebServer()
        server.dispatcher = router
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DaysembleDatabase::class.java).allowMainThreadQueries().build()
        http = GoogleHttp(tokens)
        val outbox = Outbox(db.sync()) { clock.now().toEpochMilli() }
        events = EventRepository(db, outbox, clock, ChangeListener.None)
        tasks = TaskRepository(db, outbox, clock, ChangeListener.None)
    }

    @After fun stopServer() { server.close(); db.close() }
}
