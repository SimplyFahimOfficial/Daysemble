package app.daysemble.data.sync

/** Notified after any local data change, so reminders can be re-planned. */
interface ChangeListener {
    fun onTaskChanged(taskId: String) {}
    fun onEventChanged(eventId: String) {}

    object None : ChangeListener
}

/** Fans out to several listeners. */
class CompositeChangeListener : ChangeListener {
    private val listeners = mutableListOf<ChangeListener>()
    fun add(l: ChangeListener) = synchronized(listeners) { listeners += l }
    private fun each(f: (ChangeListener) -> Unit) = synchronized(listeners) { listeners.toList() }.forEach(f)
    override fun onTaskChanged(taskId: String) = each { it.onTaskChanged(taskId) }
    override fun onEventChanged(eventId: String) = each { it.onEventChanged(eventId) }
}
