package dev.sanex.wtspike

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.util.indexing.diagnostic.ProjectDumbIndexingHistory
import com.intellij.util.indexing.diagnostic.ProjectIndexingActivityHistoryListener
import com.intellij.util.indexing.diagnostic.ProjectScanningHistory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One finished scanning pass: what triggered it, how many files it looked at and how many it sent to indexing. */
data class ScanRecord(
    val reason: String?,
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val scannedFiles: Int,
    val filesForIndexing: Int,
    val dumbModeMs: Long,
    val cancelled: Boolean,
)

/** One finished dumb-mode indexing pass. */
data class IndexingRecord(val startMs: Long, val endMs: Long, val indexedFiles: Int, val cancelled: Boolean)

/** Everything the platform reports about scanning and indexing in one project, plus when anything last happened. */
@Service(Service.Level.PROJECT)
class IndexingRecorder(project: Project) : Disposable {
    val scans = CopyOnWriteArrayList<ScanRecord>()
    val indexings = CopyOnWriteArrayList<IndexingRecord>()
    private val running = AtomicInteger()

    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    val isBusy: Boolean get() = running.get() > 0

    init {
        project.messageBus.connect(this).subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun enteredDumbMode() = touch()
            override fun exitDumbMode() = touch()
        })
    }

    fun started() {
        running.incrementAndGet()
        touch()
    }

    fun finished(scan: ScanRecord? = null, indexing: IndexingRecord? = null) {
        scan?.let(scans::add)
        indexing?.let(indexings::add)
        running.updateAndGet { maxOf(0, it - 1) }
        touch()
    }

    private fun touch() {
        lastActivityMs = System.currentTimeMillis()
    }

    override fun dispose() = Unit

    companion object {
        fun of(project: Project): IndexingRecorder = project.service()
    }
}

class IndexingRecorderListener : ProjectIndexingActivityHistoryListener {
    override fun onStartedScanning(history: ProjectScanningHistory) {
        recorder(history.project)?.started()
    }

    override fun onFinishedScanning(history: ProjectScanningHistory) {
        val times = history.times
        recorder(history.project)?.finished(
            scan = ScanRecord(
                reason = history.scanningReason,
                type = times.scanningType.name,
                startMs = times.updatingStart.toInstant().toEpochMilli(),
                endMs = times.updatingEnd.toInstant().toEpochMilli(),
                scannedFiles = history.scanningStatistics.sumOf { it.numberOfScannedFiles },
                filesForIndexing = history.scanningStatistics.sumOf { it.numberOfFilesForIndexing },
                dumbModeMs = times.dumbModeWithoutPausesDuration.toMillis(),
                cancelled = times.isCancelled,
            ),
        )
    }

    override fun onStartedDumbIndexing(history: ProjectDumbIndexingHistory) {
        recorder(history.project)?.started()
    }

    override fun onFinishedDumbIndexing(history: ProjectDumbIndexingHistory) {
        val times = history.times
        recorder(history.project)?.finished(
            indexing = IndexingRecord(
                startMs = times.updatingStart.toInstant().toEpochMilli(),
                endMs = times.updatingEnd.toInstant().toEpochMilli(),
                indexedFiles = history.providerStatistics.sumOf { it.totalNumberOfIndexedFiles },
                cancelled = times.isCancelled,
            ),
        )
    }

    private fun recorder(project: Project): IndexingRecorder? = if (project.isDisposed) null else IndexingRecorder.of(project)
}
