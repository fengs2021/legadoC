package io.legado.app.ui.widget

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypeface
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.surface.SurfaceStyles
import io.legado.app.utils.SurfaceBackdrop
import io.legado.app.utils.applyAdaptiveDim
import io.legado.app.utils.dpToPx

object SourceSelectDialog {

    /**
     * 通用书源选择弹窗。
     *
     * [typeFilters] 非空时在搜索框上方显示一行分类 chip；每项为 (类型值, 标签字符串资源)，
     * 类型值为 null 表示“全部”。调用方需同时提供 [typeOf] 从条目取类型值。
     * RSS 等没有类型概念的调用方不传这两个参数，界面与旧版完全一致。
     */
    fun <T> show(
        context: android.content.Context,
        title: CharSequence,
        items: List<T>,
        selectedKey: String?,
        displayName: (T) -> String,
        searchTexts: (T) -> List<String>,
        searchHint: String?,
        itemKey: (T) -> String,
        typeFilters: List<Pair<Int?, Int>>? = null,
        typeOf: ((T) -> Int)? = null,
        onSelect: (T) -> Unit
    ) {
        if (items.isEmpty()) return
        var dialog: AlertDialog? = null
        var keyword = ""
        var activeType: Int? = null
        var filteredItems = items.toList()

        val adapter = object : RecyclerView.Adapter<SourceViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
                return SourceViewHolder(SourceOptionView(parent.context))
            }

            override fun getItemCount(): Int = filteredItems.size

            override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
                val item = filteredItems[position]
                val selectedPrefix = if (itemKey(item) == selectedKey) "✓ " else ""
                holder.bind(selectedPrefix + displayName(item)) {
                    dialog?.dismiss()
                    onSelect(item)
                }
            }
        }

        fun applyFilter() {
            filteredItems = items.filter { item ->
                val typeOk = activeType == null || (typeOf?.invoke(item) == activeType)
                if (!typeOk) return@filter false
                if (keyword.isBlank()) {
                    true
                } else {
                    searchTexts(item).any { text -> text.contains(keyword, true) }
                }
            }
            adapter.notifyDataSetChanged()
        }

        val searchView = SearchView(context).apply {
            queryHint = searchHint ?: context.getString(R.string.screen)
            setIconifiedByDefault(false)
            isIconified = false
            isSubmitButtonEnabled = false
            background = GradientDrawable().apply {
                cornerRadius = UiCorner.searchRadius(10f)
                setColor(
                    UiCorner.dialogSurfaceColor(
                        UiCorner.themeSurfaceMutedColor(context)
                    )
                )
            }
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = true

                override fun onQueryTextChange(newText: String?): Boolean {
                    keyword = newText.orEmpty().trim()
                    applyFilter()
                    return true
                }
            })
            setOnCloseListener {
                setQuery("", false)
                isIconified = false
                true
            }
        }
        searchView.applyUiBodyTypeface(context)
        val recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                360.dpToPx()
            ).apply {
                topMargin = 10.dpToPx()
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            SurfaceBackdrop.installStatic(this, SurfaceStyles.dialog(context))
            setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 12.dpToPx())
            addView(
                TextView(context).apply {
                    text = title
                    applyUiSectionTitleStyle(context)
                    textSize = 18f
                    includeFontPadding = false
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(2.dpToPx(), 0, 2.dpToPx(), 12.dpToPx())
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    32.dpToPx()
                )
            )
            addView(
                searchView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    42.dpToPx()
                )
            )
            if (!typeFilters.isNullOrEmpty() && typeOf != null) {
                val chipRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8.dpToPx() }
                }
                val chips = mutableListOf<TextView>()
                typeFilters.forEach { (type, labelRes) ->
                    val chip = TextView(context).apply {
                        text = context.getString(labelRes)
                        applyUiLabelStyle(context)
                        textSize = 13f
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                        isSelected = type == activeType
                        setTextColor(if (isSelected) Color.WHITE else primaryTextColor(context))
                        background = UiCorner.dialogActionSelector(
                            UiCorner.themeSurfaceMutedColor(context),
                            context.accentColor,
                            UiCorner.actionRadius(context)
                        )
                        setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = 8.dpToPx() }
                        setOnClickListener {
                            activeType = type
                            chips.forEach { it.isSelected = (it == this@apply) }
                            chips.forEach { c ->
                                c.setTextColor(if (c.isSelected) Color.WHITE else primaryTextColor(context))
                            }
                            applyFilter()
                        }
                    }
                    chips.add(chip)
                    chipRow.addView(chip)
                }
                addView(chipRow)
            }
            addView(recyclerView)
        }
        dialog = AlertDialog.Builder(context)
            .setView(container)
            .create()
        dialog.setOnShowListener {
            container.requestFocus()
            searchView.clearFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.applyAdaptiveDim(container)
    }

    private class SourceOptionView(context: android.content.Context) : TextView(context) {
        init {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            minHeight = 48.dpToPx()
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            applyUiLabelStyle(context)
            textSize = 15f
            setPadding(18.dpToPx(), 0, 18.dpToPx(), 0)
            background = UiCorner.dialogActionSelector(
                Color.TRANSPARENT,
                UiCorner.themeSurfaceMutedColor(context),
                UiCorner.actionRadius(context)
            )
            isClickable = true
            isFocusable = true
        }
    }

    private class SourceViewHolder(private val rowView: SourceOptionView) : RecyclerView.ViewHolder(rowView) {
        fun bind(title: CharSequence, onClick: () -> Unit) {
            rowView.text = title
            rowView.setOnClickListener { onClick() }
        }
    }
}
