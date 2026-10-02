package com.weavetext.ime.stickers

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Independent asset collection; clearing clipboard history never touches this directory. */
data class Sticker(
    val id:String, val file:String, val mime:String, val bytes:Long,
    val width:Int, val height:Int, val animated:Boolean,
    val name:String, val group:String="", val tags:List<String> = emptyList(),
    val favorite:Boolean=false, val created:Long, val lastUsed:Long=0,
)

class StickerStore(private val directory:File,private val inspect:(File)->Pair<Int,Int>) {
    private val originals=File(directory,"originals")
    private val catalog=File(directory,"catalog.json")
    private var readError:String?=null
    private var entries=runCatching {read()}.getOrElse {readError="表情索引损坏，请导入表情备份恢复";emptyList()}
    val loadError:String? get()=readError

    @Synchronized fun list(query:String="",filter:String="all"):List<Sticker> {
        val search=query.trim().lowercase()
        return entries.filter {s ->
            (search.isEmpty() || (listOf(s.name,s.group)+s.tags).any {it.lowercase().contains(search)}) &&
                when(filter){"favorites"->s.favorite;"recent"->s.lastUsed>0;"ungrouped"->s.group.isEmpty();"all"->true;else->s.group==filter.removePrefix("group:")}
        }.sortedWith(compareByDescending<Sticker> {if(filter=="recent")it.lastUsed else it.created}.thenBy {it.id})
    }
    @Synchronized fun groups()=entries.map {it.group}.filter {it.isNotEmpty()}.distinct().sorted()
    @Synchronized fun get(id:String)=entries.firstOrNull {it.id==id}
    fun file(sticker:Sticker):File {
        require(sticker.id.matches(Regex("[a-f0-9]{64}")) && sticker.file.matches(Regex("${sticker.id}\\.(png|jpg|gif|webp|bmp)"))) {"表情文件记录无效"}
        return File(originals,sticker.file)
    }

    /** Copies original bytes while the caller still holds its read grant. MIME is inferred from bytes. */
    fun import(input:InputStream,name:String,group:String="",expectedId:String?=null,limit:Long=MAX_BYTES):Pair<Sticker,Boolean> {
        directory.mkdirs();originals.mkdirs()
        val temporary=File.createTempFile("collect-",".tmp",directory)
        try {
            val sha=MessageDigest.getInstance("SHA-256")
            var size=0L
            input.use {source -> temporary.outputStream().use {target ->
                val buffer=ByteArray(64*1024)
                while(true){val n=source.read(buffer);if(n<0)break;size+=n
                    require(size<=MAX_BYTES) {"表情超过 20 MB，请先压缩后再收纳"}
                    require(size<=limit){"备份解包超过大小限制"}
                    sha.update(buffer,0,n);target.write(buffer,0,n)
                }
            }}
            val type=ImageType.detect(temporary) ?: error("没有收到可读取的图片，试试从原应用分享或保存后导入")
            val (width,height)=inspect(temporary)
            require(width in 1..8192 && height in 1..8192 && width.toLong()*height<=32_000_000) {"图片尺寸过大或图片已损坏"}
            val id=sha.digest().joinToString(""){"%02x".format(it)}
            require(expectedId==null || id==expectedId){"备份中的图片校验失败"}
            synchronized(this) {
                entries.firstOrNull {it.id==id}?.let {old ->
                    if(!file(old).isFile){check(temporary.renameTo(file(old))) {"无法恢复表情文件"}}
                    return old to false
                }
                require(entries.size<5000) {"已收纳 5000 张，请先整理或导出"}
                val item=Sticker(id,"$id.${type.extension}",type.mime,size,width,height,type.animated,
                    clean(name).ifEmpty {"表情"},clean(group),created=System.currentTimeMillis())
                check(temporary.renameTo(file(item))) {"无法保存表情文件"}
                val next=entries+item
                try {save(next);entries=next} catch(e:Exception){file(item).delete();throw e}
                return item to true
            }
        } finally {temporary.delete()}
    }
    @Synchronized fun edit(id:String,name:String,group:String,tags:List<String>,favorite:Boolean):Boolean {
        val old=get(id) ?: return false
        val changed=old.copy(name=clean(name).ifEmpty {old.name},group=clean(group),tags=tags.map(::clean).filter {it.isNotEmpty()}.distinct().take(32),favorite=favorite)
        val next=entries.map {if(it.id==id)changed else it};save(next);entries=next;return true
    }
    @Synchronized fun group(ids:Set<String>,group:String) {
        val next=entries.map {if(it.id in ids)it.copy(group=clean(group))else it};save(next);entries=next
    }
    @Synchronized fun used(id:String) {
        if(get(id)==null)return
        val next=entries.map {if(it.id==id)it.copy(lastUsed=System.currentTimeMillis())else it};save(next);entries=next
    }
    @Synchronized fun delete(ids:Set<String>) {
        val removed=entries.filter {it.id in ids};val next=entries.filter {it.id !in ids}
        save(next);entries=next;removed.forEach {file(it).delete()}
    }
    @Synchronized fun export(output:java.io.OutputStream) {
        java.util.zip.ZipOutputStream(output).use {zip ->
            require(entries.sumOf {file(it).length()}<=512L*1024*1024){"表情备份超过 512 MB，请分批整理"}
            require(entries.all {file(it).isFile}){"表情原文件丢失，请重新导入后备份"}
            zip.putNextEntry(java.util.zip.ZipEntry("catalog.json"));zip.write(encode(entries).toByteArray());zip.closeEntry()
            for(item in entries){val f=file(item);if(!f.isFile)continue
                zip.putNextEntry(java.util.zip.ZipEntry("originals/${item.file}"));f.inputStream().use {it.copyTo(zip)};zip.closeEntry()
            }
        }
    }
    fun importArchive(input:InputStream):Pair<Int,Int> {
        // Read metadata first but never trust its file paths, MIME, dimensions or hashes.
        val archive=File.createTempFile("sticker-backup-",".zip",directory.apply {mkdirs()})
        try {
            var bytes=0L
            input.use {source -> archive.outputStream().use {target ->val b=ByteArray(65536)
                while(true){val n=source.read(b);if(n<0)break;bytes+=n;require(bytes<=512L*1024*1024){"备份超过 512 MB"};target.write(b,0,n)}
            }}
            var added=0;var skipped=0;var expanded=0L
            java.util.zip.ZipFile(archive).use {zip ->
                val record=zip.getEntry("catalog.json") ?: error("不是织文表情备份")
                val json=zip.getInputStream(record).use {readLimited(it,4*1024*1024).toString(Charsets.UTF_8)}
                val records=decode(json)
                if(readError!=null){synchronized(this){catalog.copyTo(File(directory,"catalog.corrupt-${System.currentTimeMillis()}.json"));readError=null}}
                for(meta in records) {
                    val entry=zip.getEntry("originals/${meta.file}") ?: error("备份文件不完整")
                    require(entry.size in 0..MAX_BYTES && expanded+entry.size<=512L*1024*1024){"备份解包超过大小限制"}
                    val (item,new)=import(zip.getInputStream(entry),meta.name,meta.group,meta.id,512L*1024*1024-expanded)
                    expanded+=item.bytes
                    if(new){synchronized(this){
                        val restored=item.copy(tags=meta.tags,favorite=meta.favorite,created=meta.created,lastUsed=meta.lastUsed)
                        val next=entries.map {if(it.id==item.id)restored else it};save(next);entries=next
                    };added++}else skipped++
                }
            };return added to skipped
        } finally {archive.delete()}
    }
    private fun save(items:List<Sticker>) {
        check(readError==null){readError ?: "无法读取表情索引"}
        directory.mkdirs();val temp=File(directory,"catalog.json.tmp")
        val data=encode(items).toByteArray();require(data.size<=4*1024*1024){"表情信息过多，请减少标签或整理后再保存"}
        temp.outputStream().use {it.write(data);it.fd.sync()}
        if(catalog.isFile){val backup=File(directory,"catalog.json.bak.tmp");backup.writeText(encode(entries));check(backup.renameTo(File(directory,"catalog.json.bak"))){"无法保存表情备份索引"}}
        check(temp.renameTo(catalog)) {"无法保存表情信息"}
    }
    private fun read():List<Sticker> {
        if(!catalog.isFile)return emptyList()
        return runCatching {decode(catalog.inputStream().use {readLimited(it,4*1024*1024).toString(Charsets.UTF_8)})}.getOrElse {
            val backup=File(directory,"catalog.json.bak")
            if(backup.isFile)decode(backup.inputStream().use {readLimited(it,4*1024*1024).toString(Charsets.UTF_8)})
            else throw IllegalStateException("表情索引损坏，请从表情备份恢复",it)
        }
    }
    private fun encode(items:List<Sticker>)=JSONObject().put("format",FORMAT).put("items",JSONArray(items.map {s ->
        JSONObject().put("id",s.id).put("file",s.file).put("mime",s.mime).put("bytes",s.bytes).put("width",s.width).put("height",s.height)
            .put("animated",s.animated).put("name",s.name).put("group",s.group).put("tags",JSONArray(s.tags))
            .put("favorite",s.favorite).put("created",s.created).put("lastUsed",s.lastUsed)
    })).toString()
    private fun decode(json:String):List<Sticker> {
        val root=JSONObject(json);require(root.getString("format")==FORMAT){"不支持的表情备份版本"}
        val array=root.getJSONArray("items");require(array.length()<=5000){"表情记录过多"}
        return (0 until array.length()).map {i ->val s=array.getJSONObject(i);val id=s.getString("id");val file=s.getString("file")
            require(id.matches(Regex("[a-f0-9]{64}")) && file.matches(Regex("${id}\\.(png|jpg|gif|webp|bmp)"))){"表情记录无效"}
            val tags=s.optJSONArray("tags") ?: JSONArray()
            Sticker(id,file,s.getString("mime"),s.optLong("bytes"),s.optInt("width"),s.optInt("height"),s.optBoolean("animated"),
                clean(s.optString("name","表情")),clean(s.optString("group")),(0 until minOf(tags.length(),32)).map {clean(tags.optString(it))},
                s.optBoolean("favorite"),s.optLong("created"),s.optLong("lastUsed"))
        }.distinctBy {it.id}
    }
    companion object {
        const val FORMAT="weavetext-stickers-1"
        const val MAX_BYTES=20L*1024*1024
        private fun clean(s:String)=s.filter {!it.isISOControl()}.trim().take(120)
        private fun readLimited(input:InputStream,max:Int):ByteArray {
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(16384)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=max){"表情索引过大"};out.write(buffer,0,n)}
            return out.toByteArray()
        }
    }
}

internal data class ImageType(val mime:String,val extension:String,val animated:Boolean) {
    companion object {
        fun detect(file:File):ImageType? {
            val header=file.inputStream().use {val buffer=ByteArray(32);var offset=0;while(offset<buffer.size){val n=it.read(buffer,offset,buffer.size-offset);if(n<0)break;offset+=n};buffer.copyOf(offset)}
            if(header.size<12)return null
            fun starts(vararg values:Int)=values.indices.all {header[it].toInt() and 255==values[it]}
            return when {
                starts(137,80,78,71,13,10,26,10)->ImageType("image/png","png",false)
                starts(255,216,255)->ImageType("image/jpeg","jpg",false)
                String(header,0,6,Charsets.US_ASCII) in listOf("GIF87a","GIF89a")->ImageType("image/gif","gif",true)
                String(header,0,4,Charsets.US_ASCII)=="RIFF" && String(header,8,4,Charsets.US_ASCII)=="WEBP"->
                    ImageType("image/webp","webp",header.size>20 && String(header,12,4,Charsets.US_ASCII)=="VP8X" && header[20].toInt() and 2!=0)
                starts(66,77)->ImageType("image/bmp","bmp",false)
                else->null
            }
        }
    }
}
