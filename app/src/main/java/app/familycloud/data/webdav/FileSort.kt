package app.familycloud.data.webdav

enum class SortField { Name, Date, Size }

data class SortOrder(val field: SortField = SortField.Name, val ascending: Boolean = true)

fun sortEntries(entries: List<RemoteEntry>, order: SortOrder): List<RemoteEntry> {
    val byValue = Comparator<RemoteEntry> { left, right ->
        when (order.field) {
            SortField.Name -> {
                val compared = left.label.lowercase().compareTo(right.label.lowercase())
                if (order.ascending) compared else -compared
            }
            SortField.Date -> compareLong(left.modifiedEpochMs, right.modifiedEpochMs, order.ascending)
            SortField.Size -> compareLong(left.size, right.size, order.ascending)
        }
    }
    return entries.sortedWith(compareBy<RemoteEntry> { !it.isFolder }.then(byValue))
}

private fun compareLong(left: Long?, right: Long?, ascending: Boolean): Int {
    return when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        ascending -> left.compareTo(right)
        else -> right.compareTo(left)
    }
}
