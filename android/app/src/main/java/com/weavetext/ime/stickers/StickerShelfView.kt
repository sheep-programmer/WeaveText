package com.weavetext.ime.stickers

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/** Shared shelf for the keyboard, full manager and optional floating window. */
class StickerShelfView(
    ctx:Context,private val compact:Boolean,private val pick:(Sticker)->Unit,
    private val manage:()->Unit,private val import:()->Unit,private val overlay:()->Unit,
    private val notice:(String)->Unit,private val allowed:()->Boolean={true},
):LinearLayout(ctx) {
    private val repository=StickerRepository.get(ctx)
    private var filter="all"
    private var query=""
    private var selecting=false
    private val selected=linkedSetOf<String>()
    private var items=emptyList<Sticker>()
    private val title=TextView(ctx)
    private val header=LinearLayout(ctx)
    private val tabs=LinearLayout(ctx)
    private val controls=LinearLayout(ctx)
    private val grid=GridView(ctx)
    private val adapter=Images()
    private val empty=TextView(ctx)
    private var actionItem:Sticker?=null
    private val actionBar=LinearLayout(ctx)
    private val changed:()->Unit={reload()}
    var dark=ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES
        set(value){field=value;applyColors()}
    private val label get()=if(dark)Color.rgb(232,233,237)else Color.rgb(30,33,40)
    private val surface get()=if(dark)Color.rgb(24,26,32)else Color.rgb(248,249,252)
    private val soft get()=if(dark)Color.rgb(45,49,59)else Color.rgb(230,235,245)
    private val muted get()=if(dark)Color.rgb(164,169,181)else Color.rgb(104,111,126)
    private val accent get()=if(dark)Color.rgb(104,137,240)else Color.rgb(75,105,205)
    init {
        orientation=VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(6))
        background=rounded(surface,dp(16),Color.TRANSPARENT)
        header.gravity=android.view.Gravity.CENTER_VERTICAL
        header.setPadding(dp(8),dp(4),dp(4),dp(4));header.background=rounded(soft,dp(15),Color.TRANSPARENT)
        val mark=ImageView(ctx).apply {setImageResource(com.weavetext.ime.R.drawable.ic_sticker_bag);setColorFilter(label,PorterDuff.Mode.SRC_IN);contentDescription="表情收纳袋"}
        header.addView(mark,LayoutParams(dp(if(compact)26 else 30),dp(if(compact)26 else 30)))
        val titleBlock=LinearLayout(ctx).apply {orientation=VERTICAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(8),0,dp(5),0)}
        title.text=if(compact)"表情"else"表情收纳袋";title.textSize=16f;title.setTypeface(null,android.graphics.Typeface.BOLD)
        val subtitle=TextView(ctx).apply {text=if(compact)"点按插入 · 长按管理"else"收藏你的图片与动图";textSize=11f;setTextColor(muted)}
        // 紧凑面板（键盘内）只有几行可用高度：标题块收窄，把空间留给图片网格。
        // The compact panel has only a few rows of height: shrink the title block to leave the grid room.
        titleBlock.addView(title,LayoutParams(-1,dp(if(compact)20 else 22)));titleBlock.addView(subtitle,LayoutParams(-1,dp(if(compact)15 else 17)))
        header.addView(titleBlock,LayoutParams(0,dp(if(compact)38 else 44),1f))
        header.addView(button("导入",import));header.addView(button(if(compact)"管理"else"整理") {if(compact)manage()else {selecting=!selecting;selected.clear();reload()}})
        // 「悬浮」直接打开悬浮窗（首次会先要权限）；不再跳到管理页。 "Floating" opens the floating window itself.
        header.addView(button("悬浮",overlay));addView(header)
        if(!compact){
            val search=EditText(ctx).apply {hint="搜索名称、标签或分组";isSingleLine=true;textSize=15f}
            search.addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s?.toString().orEmpty();reload()}
                override fun afterTextChanged(s:android.text.Editable?){}
            });addView(search,LayoutParams(-1,dp(44)))
        }
        addView(HorizontalScrollView(ctx).apply {isHorizontalScrollBarEnabled=false;setPadding(0,dp(if(compact)3 else 5),0,0);addView(tabs)},LayoutParams(-1,dp(if(compact)38 else 43)))
        // 操作条只在长按后占高度（稀缺的键盘空间不做预留）。 The in-panel action bar only takes height after a long-press.
        actionBar.visibility=GONE;actionBar.gravity=android.view.Gravity.CENTER_VERTICAL
        addView(HorizontalScrollView(ctx).apply {isHorizontalScrollBarEnabled=false;addView(actionBar)},LayoutParams(-1,dp(42)))
        addView(controls,LayoutParams(-1,dp(40)))
        val body=FrameLayout(ctx)
        grid.numColumns=if(compact)3 else 4;grid.verticalSpacing=dp(5);grid.horizontalSpacing=dp(5);grid.stretchMode=GridView.STRETCH_COLUMN_WIDTH
        grid.adapter=adapter;grid.setRecyclerListener {view -> (view.tag as? Tile)?.image?.stop()}
        grid.setOnItemClickListener {_,_,position,_->items.getOrNull(position)?.let {item ->
            if(selecting){if(!selected.add(item.id))selected.remove(item.id);adapter.notifyDataSetChanged();updateControls()}else pick(item)
        }}
        grid.setOnItemLongClickListener {viewParent,view,position,_->items.getOrNull(position)?.let {showActions(view,it)};true}
        body.addView(grid,FrameLayout.LayoutParams(-1,-1))
        empty.gravity=android.view.Gravity.CENTER;empty.textSize=14f
        body.addView(empty,FrameLayout.LayoutParams(-1,-1));addView(body,LayoutParams(-1,0,1f))
        StickerDrop.bind(this,repository,notice)
        applyColors();reload()
    }
    fun reload() {
        val permitted=allowed();header.visibility=if(permitted)VISIBLE else GONE
        tabs.visibility=if(permitted)VISIBLE else GONE
        // 当前选中的表情被删掉后收起操作条。 The action bar collapses once its sticker is gone.
        if(actionItem!=null && repository.store.get(actionItem!!.id)==null){actionItem=null;updateActions()}
        if(actionItem!=null && items.none {it.id==actionItem!!.id})actionItem=null
        updateActions()
        items=if(permitted)repository.store.list(query,filter)else emptyList();selected.retainAll(items.map {it.id}.toSet())
        if(!permitted){selecting=false;empty.visibility=VISIBLE;empty.text="私密输入框不显示表情收纳袋";tabs.removeAllViews();updateControls();adapter.notifyDataSetChanged();return}
        title.text=if(selecting)"已选 ${selected.size} 张"else "表情收纳袋 · ${repository.store.list().size}"
        empty.visibility=if(items.isEmpty())VISIBLE else GONE
        empty.text=repository.store.loadError ?: if(repository.store.list().isEmpty())"把图片分享到「收纳到织文」\n或点导入，收藏自己的表情"else"没有找到表情"
        tabs.removeAllViews()
        for((id,name) in listOf("all" to "全部","recent" to "最近","favorites" to "收藏","ungrouped" to "未分组")+repository.store.groups().map {"group:$it" to it}) {
            tabs.addView(tab(name,filter==id) {filter=id;reload()})
        }
        updateControls();adapter.notifyDataSetChanged()
    }
    private fun updateControls() {
        controls.removeAllViews();controls.visibility=if(selecting)VISIBLE else GONE
        if(!selecting)return
        controls.addView(button("全选") {selected.addAll(items.map {it.id});reload()})
        controls.addView(button("分组") {groupSelected()})
        controls.addView(button("删除") {if(selected.isNotEmpty())android.app.AlertDialog.Builder(context).setTitle("删除 ${selected.size} 张表情？")
            .setMessage("原应用中的图片不受影响。").setPositiveButton("删除"){_,_->repository.delete(selected.toSet(),notice);selected.clear()}.setNegativeButton("取消",null).show()})
        controls.addView(button("完成") {selecting=false;selected.clear();reload()})
    }
    private fun groupSelected() {
        val input=EditText(context).apply {hint="分组名称"}
        android.app.AlertDialog.Builder(context).setTitle("批量分组").setView(input).setPositiveButton("保存"){_,_->
            val ids=selected.toSet();val group=input.text.toString();StickerRepository.io.execute {val result=runCatching {repository.store.group(ids,group)};repository.changed();post {notice(result.fold({"已保存分组"},{it.message ?: "分组失败"}))}}
        }.setNegativeButton("取消",null).show()
    }
    /**
     * 长按就地给出操作：键盘面板里也能直接收藏、编辑、分享、删除，不再强制跳进全屏管理页。
     * Long-press shows the actions in place: favourite, edit, share and delete work right in the keyboard
     * panel instead of forcing a trip to the full manager.
     */
    private fun showActions(anchor:View,item:Sticker) {
        if(compact){actionItem=item;updateActions();reload();return}
        PopupMenu(context,anchor).apply {
            menu.add(if(item.favorite)"取消收藏"else"收藏").setOnMenuItemClickListener {repository.edit(item.id,item.name,item.group,item.tags,!item.favorite);true}
            menu.add("编辑名称、标签与分组").setOnMenuItemClickListener {edit(item);true}
            menu.add("分享原图").setOnMenuItemClickListener {StickerSending.share(context,item);notice("已打开分享");true}
            menu.add("删除").setOnMenuItemClickListener {repository.delete(setOf(item.id)){notice("已删除「${item.name}」")};true}
            show()
        }
    }

    /** 就地操作条：选中一张时在网格上方显示。 In-panel action bar shown while one sticker is selected. */
    private fun updateActions() {
        val item=actionItem
        actionBar.removeAllViews()
        actionBar.visibility=if(item!=null)VISIBLE else GONE
        if(item==null)return
        actionBar.setPadding(0,dp(4),0,dp(4))
        actionBar.addView(button(if(item.favorite)"取消收藏"else"收藏") {
            repository.edit(item.id,item.name,item.group,item.tags,!item.favorite);actionItem=null;reload()
        })
        actionBar.addView(button("编辑") {edit(item)})
        actionBar.addView(button("分享") {StickerSending.share(context,item);actionItem=null;reload()})
        actionBar.addView(button("删除") {
            repository.delete(setOf(item.id)){notice(it)};actionItem=null;actionBar.visibility=GONE
        })
        actionBar.addView(button("关闭") {actionItem=null;reload()})
    }
    private fun edit(item:Sticker) {
        val fields=LinearLayout(context).apply {orientation=VERTICAL;setPadding(dp(20),0,dp(20),0)}
        fun field(hintText:String,value:String)=EditText(context).apply {hint=hintText;setText(value);fields.addView(this)}
        val name=field("名称",item.name);val group=field("分组",item.group);val tags=field("标签，用逗号分隔",item.tags.joinToString("，"))
        android.app.AlertDialog.Builder(context).setTitle("编辑表情").setView(fields).setPositiveButton("保存"){_,_->
            repository.edit(item.id,name.text.toString(),group.text.toString(),tags.text.toString().split(',','，'),item.favorite,notice)
        }.setNegativeButton("取消",null).show()
    }
    private fun button(text:String,action:()->Unit)=TextView(context).apply {
        this.text=text;textSize=13f;gravity=android.view.Gravity.CENTER;setTextColor(label)
        setPadding(dp(10),0,dp(10),0);minimumHeight=dp(34);isClickable=true;isFocusable=true
        background=rounded(if(dark)Color.rgb(58,63,76) else Color.WHITE,dp(11),Color.TRANSPARENT)
        setOnClickListener {action()}
    }
    private fun tab(text:String,active:Boolean,action:()->Unit)=TextView(context).apply {
        this.text=text;textSize=13f;gravity=android.view.Gravity.CENTER;setTextColor(if(active)Color.WHITE else muted)
        setPadding(dp(13),0,dp(13),0);minimumHeight=dp(32);background=rounded(if(active)accent else soft,dp(16),Color.TRANSPARENT);setOnClickListener {action()}
    }
    private fun rounded(color:Int,radius:Int,stroke:Int)=GradientDrawable().apply {setColor(color);cornerRadius=radius.toFloat();if(stroke!=Color.TRANSPARENT)setStroke(dp(1),stroke)}
    private fun applyColors(){
        background=rounded(surface,dp(16),Color.TRANSPARENT);header.background=rounded(soft,dp(15),Color.TRANSPARENT)
        title.setTextColor(label);empty.setTextColor(muted)
        (header.getChildAt(0) as? ImageView)?.setColorFilter(label,PorterDuff.Mode.SRC_IN)
        for(i in 0 until header.childCount)(header.getChildAt(i) as? TextView)?.let {it.setTextColor(label);it.background=rounded(if(dark)Color.rgb(58,63,76) else Color.WHITE,dp(11),Color.TRANSPARENT)}
        reload()
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();repository.observe(changed);reload()}
    override fun onDetachedFromWindow(){repository.unobserve(changed);for(i in 0 until grid.childCount)(grid.getChildAt(i).tag as? Tile)?.image?.stop();super.onDetachedFromWindow()}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private data class Tile(val image:StickerImageView,val caption:TextView)
    private inner class Images:BaseAdapter() {
        override fun getCount()=items.size
        override fun getItem(position:Int)=items[position]
        override fun getItemId(position:Int)=position.toLong()
        override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
            val cell=convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation=VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(4))
                val image=StickerImageView(context);val caption=TextView(context).apply {gravity=android.view.Gravity.CENTER;maxLines=1;textSize=if(compact)10f else 12f}
                addView(image,LayoutParams(-1,dp(if(compact)58 else 84)));addView(caption,LayoutParams(-1,dp(if(compact)18 else 32)));tag=Tile(image,caption)
            }
            val tile=cell.tag as Tile;val item=items[position]
            tile.caption.text=(if(selecting && item.id in selected)"✓ "else if(item.favorite)"★ "else "")+item.name
            tile.caption.setTextColor(label);cell.background=rounded(if(dark)Color.rgb(38,42,52) else Color.WHITE,dp(12),if(dark)Color.rgb(63,68,82) else Color.rgb(225,230,240))
            cell.contentDescription=item.name+(if(item.animated)"，GIF 或动态图片"else"")
            tile.image.bind(repository.store.file(item),item.id);return cell
        }
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
