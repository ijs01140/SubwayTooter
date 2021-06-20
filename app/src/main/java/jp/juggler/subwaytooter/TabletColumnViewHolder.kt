package jp.juggler.subwaytooter

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import jp.juggler.util.LogCategory

internal class TabletColumnViewHolder(
    activity: ActMain,
    parent: ViewGroup,
    val columnViewHolder: ColumnViewHolder = ColumnViewHolder(activity, parent)
) : RecyclerView.ViewHolder(columnViewHolder.viewRoot) {

    companion object {
        val log = LogCategory("TabletColumnViewHolder")
    }

    private var pageIndex = -1

    fun bind(column: Column, pageIndex: Int, columnCount: Int) {
        log.d("bind. ${this.pageIndex} => $pageIndex")

        columnViewHolder.onPageDestroy(this.pageIndex)

        this.pageIndex = pageIndex

        columnViewHolder.onPageCreate(column, pageIndex, columnCount)

        if (!column.bFirstInitialized) {
            column.startLoading()
        }
    }

    fun onViewRecycled() {
        log.d("onViewRecycled $pageIndex")
        columnViewHolder.onPageDestroy(pageIndex)
    }
}
