package app.daysemble.data

import app.daysemble.AppContainer
import app.daysemble.domain.model.TaskDraft
import kotlinx.coroutines.flow.first

/**
 * Data for the macrobenchmarks: sample events and tasks plus [EXTRA_TASKS] more tasks over the next
 * two weeks, so lists have something to scroll. Only the "benchmark" build type calls it
 * (BuildConfig.BENCHMARK_DATA), once, on a store with no tasks.
 */
object BenchmarkSeed {
    private const val EXTRA_TASKS = 150

    suspend fun seed(container: AppContainer) {
        container.settings.completeOnboarding("Sam")
        if (container.tasks.allTasks.first().isNotEmpty()) return
        SampleData.load(container)
        val list = container.defaults().taskListId
        val today = container.clock.today()
        repeat(EXTRA_TASKS) { i ->
            container.tasks.create(TaskDraft(title = "Benchmark task ${i + 1}", dueDate = today.plusDays((i % 14).toLong())), list)
        }
    }
}
