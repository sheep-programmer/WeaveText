package com.weavetext.ime.stickers

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.weavetext.ime.R
import java.io.File

/**
 * 收纳袋配色：键盘里取键盘当前风格的颜色，悬浮窗用自带的浅色 / 深色。
 * Shelf colours: the keyboard passes its current style; the floating window uses the built-in light/dark set.
 */
data class ShelfColors(
    val surface: Int, val tile: Int, val chip: Int, val label: Int, val muted: Int,
    val accent: Int, val onAccent: Int, val accentSoft: Int, val danger: Int, val stroke: Int,
) {
    companion object {
        val LIGHT = ShelfColors(Color.rgb(246, 247, 251), Color.WHITE, Color.rgb(232, 236, 245), Color.rgb(30, 33, 40),
            Color.rgb(104, 111, 126), Color.rgb(75, 105, 205), Color.WHITE, Color.rgb(222, 230, 250), Color.rgb(214, 69, 65), Color.rgb(226, 230, 238))
        val DARK = ShelfColors(Color.rgb(24, 26, 32), Color.rgb(38, 42, 52), Color.rgb(45, 49, 59), Color.rgb(232, 233, 237),
            Color.rgb(164, 169, 181), Color.rgb(104, 137, 240), Color.WHITE, Color.rgb(44, 54, 84), Color.rgb(242, 110, 104), Color.rgb(58, 63, 76))
    }
}

/**
 * 表情收纳袋（键盘面板与悬浮窗共用）：上方一行是分组标签与导入 / 悬浮 / 管理按钮，长按某张后这一行换成它的操作；
 * 下方是自适应列数的圆角图块，收藏与动图有角标。完整的整理、搜索、批量操作在管理页。
 * The sticker shelf shared by the keyboard panel and the floating window: one top row with group chips and the
 * import / float / manage buttons, which turns into the actions for a long-pressed sticker; below, rounded tiles in
 * as many columns as fit, badged for favourites and animation. Full organising, search and batch work live in the
 * manager screen.
 */
class StickerShelfView(
    ctx: Context, private val pick: (Sticker) -> Unit,
    private val manage: () -> Unit, private val import: () -> Unit, private val overlay: () -> Unit,
    private val notice: (String) -> Unit, private val allowed: () -> Boolean = { true },
) : LinearLayout(ctx) {
    private val repository = StickerRepository.get(ctx)
    private var filter = "all"
    private var items = emptyList<Sticker>()
    private val nav = LinearLayout(ctx)
    private val tabs = LinearLayout(ctx)
    private val tools = LinearLayout(ctx)
    private val actionBar = LinearLayout(ctx)
    private val grid = GridView(ctx)
    private val adapter = Tiles()
    private val empty = LinearLayout(ctx)
    private val emptyIcon = ImageView(ctx)
    private val emptyTitle = TextView(ctx)
    private val emptyText = TextView(ctx)
    private val emptyButton = TextView(ctx)
    private var actionItem: Sticker? = null
    private val changed: () -> Unit = { reload() }

    /** 键盘按自己的风格给色；为空时按 [dark] 用自带配色。 Colours from the keyboard style, else the built-in set. */
    var colors: ShelfColors? = null
        set(value) { field = value; applyColors() }
    var dark = ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
        set(value) { field = value; applyColors() }
    private val c get() = colors ?: if (dark) ShelfColors.DARK else ShelfColors.LIGHT

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(4))
        // 第一行：分组标签（可横滑）+ 右侧图标按钮；长按某张后换成操作条。
        // Row one: scrolling group chips + icon buttons on the right; replaced by the action bar after a long-press.
        val top = FrameLayout(ctx)
        nav.gravity = Gravity.CENTER_VERTICAL
        tabs.gravity = Gravity.CENTER_VERTICAL
        nav.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = false; addView(tabs)
        }, LayoutParams(0, -1, 1f))
        tools.gravity = Gravity.CENTER_VERTICAL
        tools.addView(iconButton(R.drawable.ic_plus, "导入图片") { import() })
        tools.addView(iconButton(R.drawable.ic_float, "悬浮收纳窗") { overlay() })
        tools.addView(iconButton(R.drawable.ic_settings, "管理表情") { manage() })
        nav.addView(tools, LayoutParams(-2, -1))
        top.addView(nav, FrameLayout.LayoutParams(-1, -1))
        actionBar.gravity = Gravity.CENTER_VERTICAL
        actionBar.visibility = GONE
        top.addView(actionBar, FrameLayout.LayoutParams(-1, -1))
        addView(top, LayoutParams(-1, dp(40)))

        val body = FrameLayout(ctx)
        grid.numColumns = GridView.AUTO_FIT
        grid.columnWidth = dp(74)
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.verticalSpacing = dp(6); grid.horizontalSpacing = dp(6)
        grid.setPadding(0, dp(4), 0, dp(4)); grid.clipToPadding = false
        grid.selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        grid.isVerticalScrollBarEnabled = false
        grid.adapter = adapter
        grid.setRecyclerListener { view -> (view.tag as? Tile)?.image?.stop() }
        grid.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { if (actionItem != null) { actionItem = null; reload() } else pick(it) }
        }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { actionItem = it; reload() }; true
        }
        body.addView(grid, FrameLayout.LayoutParams(-1, -1))
        buildEmpty()
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, 0, 1f))
        StickerDrop.bind(this, repository, notice)
        applyColors()
    }

    private fun buildEmpty() {
        empty.orientation = VERTICAL
        empty.gravity = Gravity.CENTER
        emptyIcon.setImageResource(R.drawable.ic_sticker_bag)
        emptyIcon.setPadding(dp(14), dp(14), dp(14), dp(14))
        empty.addView(emptyIcon, LayoutParams(dp(56), dp(56)))
        emptyTitle.textSize = 15f; emptyTitle.setTypeface(null, Typeface.BOLD); emptyTitle.gravity = Gravity.CENTER
        empty.addView(emptyTitle, LayoutParams(-2, -2).apply { topMargin = dp(10) })
        emptyText.textSize = 12f; emptyText.gravity = Gravity.CENTER; emptyText.setLineSpacing(0f, 1.15f)
        empty.addView(emptyText, LayoutParams(-2, -2).apply { topMargin = dp(4); leftMargin = dp(24); rightMargin = dp(24) })
        emptyButton.text = "导入图片"; emptyButton.textSize = 13f; emptyButton.gravity = Gravity.CENTER
        emptyButton.setPadding(dp(18), 0, dp(18), 0); emptyButton.setOnClickListener { import() }
        empty.addView(emptyButton, LayoutParams(-2, dp(34)).apply { topMargin = dp(12) })
    }

    fun reload() {
        val permitted = allowed()
        val all = if (permitted) repository.store.list() else emptyList()
        // 分组被删光（或改名）后回到「全部」。 A group that no longer exists falls back to "all".
        if (filter.startsWith("group:") && filter.removePrefix("group:") !in repository.store.groups()) filter = "all"
        actionItem = actionItem?.let { a -> repository.store.get(a.id) }
        items = if (permitted) repository.store.list("", filter) else emptyList()
        if (!permitted) actionItem = null
        nav.visibility = if (actionItem == null) VISIBLE else INVISIBLE
        tools.visibility = if (permitted) VISIBLE else GONE
        updateActions()
        tabs.removeAllViews()
        if (permitted && all.isNotEmpty()) {
            val counts = mapOf("all" to all.size)
            for ((id, name) in listOf("all" to "全部", "recent" to "最近", "favorites" to "收藏", "ungrouped" to "未分组") +
                repository.store.groups().map { "group:$it" to it }) {
                tabs.addView(chip(if (id in counts) "$name ${counts[id]}" else name, filter == id) { filter = id; actionItem = null; reload() })
            }
        } else if (permitted) {
            tabs.addView(TextView(context).apply { text = "表情收纳袋"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(c.label); setPadding(dp(6), 0, 0, 0) })
        }
        val error = repository.store.loadError
        empty.visibility = if (items.isEmpty()) VISIBLE else GONE
        emptyButton.visibility = if (permitted && all.isEmpty()) VISIBLE else GONE
        when {
            !permitted -> { emptyTitle.text = "私密输入框"; emptyText.text = "这里不显示收藏的表情" }
            error != null -> { emptyTitle.text = "收纳袋读取失败"; emptyText.text = error }
            all.isEmpty() -> { emptyTitle.text = "还没有收藏表情"; emptyText.text = "在相册或聊天里把图片分享到「收纳到织文」，\n或点下面导入，动图也能收" }
            else -> { emptyTitle.text = "这里还没有表情"; emptyText.text = if (filter == "favorites") "长按表情可以收藏" else "换个分组看看" }
        }
        adapter.notifyDataSetChanged()
    }

    /** 长按后的操作条：缩略图、名称，再是收藏 / 编辑 / 分享 / 删除 / 关闭。 Actions for the long-pressed sticker. */
    private fun updateActions() {
        val item = actionItem
        actionBar.removeAllViews()
        actionBar.visibility = if (item != null) VISIBLE else GONE
        if (item == null) return
        actionBar.background = rounded(c.accentSoft, dp(14))
        actionBar.setPadding(dp(6), 0, dp(2), 0)
        actionBar.addView(StickerImageView(context).apply { bind(repository.store.file(item), item.id) }, LayoutParams(dp(30), dp(30)))
        actionBar.addView(TextView(context).apply {
            text = item.name; textSize = 13f; setTextColor(c.label); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(4), 0)
        }, LayoutParams(0, -2, 1f))
        actionBar.addView(iconButton(if (item.favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
            if (item.favorite) "取消收藏" else "收藏", tint = if (item.favorite) FAVORITE else c.label) {
            repository.edit(item.id, item.name, item.group, item.tags, !item.favorite) { notice(if (item.favorite) "已取消收藏" else "已收藏") }
            actionItem = null; reload()
        })
        actionBar.addView(iconButton(R.drawable.ic_edit, "编辑名称、标签与分组") { edit(item) })
        actionBar.addView(iconButton(R.drawable.ic_share, "分享原图") { StickerSending.share(context, item); actionItem = null; reload() })
        actionBar.addView(iconButton(R.drawable.ic_delete, "删除", tint = c.danger) {
            repository.delete(setOf(item.id)) { notice("已删除「${item.name}」") }; actionItem = null; reload()
        })
        actionBar.addView(iconButton(R.drawable.ic_close, "关闭") { actionItem = null; reload() })
    }

    /**
     * 编辑交给管理页：键盘和悬浮窗都是服务窗口，弹不出对话框，键盘自己的窗口里也没法给输入框打字。
     * Editing opens the manager: the keyboard and floating window are service windows that cannot host a
     * dialog, and an input field inside the keyboard's own window could not be typed into anyway.
     */
    private fun edit(item: Sticker) {
        actionItem = null; reload()
        context.startActivity(StickerActivity.edit(context, item.id))
    }

    private fun iconButton(icon: Int, label: String, tint: Int? = null, action: () -> Unit) = ImageView(context).apply {
        setImageResource(icon); contentDescription = label
        imageTintList = ColorStateList.valueOf(tint ?: c.label)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(c.stroke), null, rounded(Color.WHITE, dp(12)))
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
        layoutParams = LayoutParams(dp(36), dp(36))
    }

    private fun chip(text: String, active: Boolean, action: () -> Unit) = TextView(context).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER; maxLines = 1
        setTextColor(if (active) c.onAccent else c.muted)
        if (active) setTypeface(null, Typeface.BOLD)
        setPadding(dp(12), 0, dp(12), 0)
        background = rounded(if (active) c.accent else c.chip, dp(15))
        setOnClickListener { action() }
        layoutParams = LayoutParams(-2, dp(30)).apply { rightMargin = dp(6) }
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT, width: Int = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(width), stroke)
    }

    private fun applyColors() {
        val c = c
        background = if (colors == null) rounded(c.surface, dp(16)) else null
        for (i in 0 until tools.childCount) (tools.getChildAt(i) as? ImageView)?.imageTintList = ColorStateList.valueOf(c.label)
        emptyIcon.imageTintList = ColorStateList.valueOf(c.accent)
        emptyIcon.background = rounded(c.accentSoft, dp(28))
        emptyTitle.setTextColor(c.label); emptyText.setTextColor(c.muted)
        emptyButton.setTextColor(c.onAccent); emptyButton.background = rounded(c.accent, dp(17))
        reload()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); repository.observe(changed); reload() }
    override fun onDetachedFromWindow() {
        repository.unobserve(changed)
        for (i in 0 until grid.childCount) (grid.getChildAt(i).tag as? Tile)?.image?.stop()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class Tile(val image: StickerImageView, val caption: TextView, val star: ImageView, val gif: TextView)

    private inner class Tiles : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? FrameLayout ?: FrameLayout(context).apply {
                val image = StickerImageView(context)
                addView(image, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP).apply { topMargin = dp(7); leftMargin = dp(7); rightMargin = dp(7) })
                val caption = TextView(context).apply {
                    gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 10.5f
                }
                addView(caption, FrameLayout.LayoutParams(-1, dp(18), Gravity.BOTTOM).apply { bottomMargin = dp(3); leftMargin = dp(4); rightMargin = dp(4) })
                val star = ImageView(context).apply { setImageResource(R.drawable.ic_star_filled); imageTintList = ColorStateList.valueOf(FAVORITE) }
                addView(star, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP or Gravity.END).apply { topMargin = dp(5); rightMargin = dp(5) })
                val gif = TextView(context).apply {
                    text = "GIF"; textSize = 8f; setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = dp(4).toFloat() }
                    setPadding(dp(3), 0, dp(3), 0)
                }
                addView(gif, FrameLayout.LayoutParams(-2, dp(13), Gravity.TOP or Gravity.START).apply { topMargin = dp(5); leftMargin = dp(5) })
                tag = Tile(image, caption, star, gif)
                layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(86))
            }
            val tile = cell.tag as Tile
            val item = items[position]
            val chosen = actionItem?.id == item.id
            tile.caption.text = item.name
            tile.caption.setTextColor(c.muted)
            tile.star.visibility = if (item.favorite) VISIBLE else GONE
            tile.gif.visibility = if (item.animated) VISIBLE else GONE
            cell.background = rounded(c.tile, dp(14), if (chosen) c.accent else c.stroke, if (chosen) 2 else 1)
            cell.contentDescription = item.name + (if (item.favorite) "，已收藏" else "") + (if (item.animated) "，动图" else "")
            tile.image.bind(repository.store.file(item), item.id)
            return cell
        }
    }

    companion object {
        private val FAVORITE = Color.rgb(245, 166, 35)
    }
}

class StickerImageView(ctx:Context):ImageView(ctx) {
    @Volatile private var identity=""
    @Volatile internal var previewError:String?=null
        private set
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private var binding:Pair<File,String>?=null
    init {scaleType=ScaleType.FIT_CENTER}
    fun bind(file:File,id:String) {
        binding=file to id
        if(identity==id && drawable!=null){if(Build.VERSION.SDK_INT>=28 && isAttachedToWindow)(drawable as? AnimatedImageDrawable)?.start();return}
        identity=id;previewError=null;stop();setImageDrawable(null)
        imageWorker.execute {
            if(identity!=id)return@execute
            val image=runCatching {
                if(Build.VERSION.SDK_INT>=28)android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file)) {decoder,info,_->
                    val scale=128f/maxOf(info.size.width,info.size.height).coerceAtLeast(1);decoder.setTargetSize(maxOf(1,(info.size.width*minOf(1f,scale)).toInt()),maxOf(1,(info.size.height*minOf(1f,scale)).toInt()))
                } else bitmapPreview(file)
            }.getOrElse {previewError=it.message;bitmapPreview(file)}
            main.post {if(identity==id){setImageDrawable(image);if(Build.VERSION.SDK_INT>=28 && isAttachedToWindow)(image as? AnimatedImageDrawable)?.start()}}
        }
    }
    fun stop(){if(Build.VERSION.SDK_INT>=28)(drawable as? AnimatedImageDrawable)?.stop()}
    private fun bitmapPreview(file:File):android.graphics.drawable.Drawable? {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
        val sample=maxOf(1,maxOf(bounds.outWidth,bounds.outHeight)/128)
        return BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {inSampleSize=sample})?.let {android.graphics.drawable.BitmapDrawable(resources,it)}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();binding?.let {(file,id)->bind(file,id)};if(Build.VERSION.SDK_INT>=28)(drawable as? AnimatedImageDrawable)?.start()}
    override fun onDetachedFromWindow(){identity="";stop();super.onDetachedFromWindow()}
    companion object {private val imageWorker=java.util.concurrent.Executors.newFixedThreadPool(2){r->Thread(r,"weave-sticker-preview").apply {isDaemon=true}}}
}
