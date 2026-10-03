//! 只读数据块：词库与模型的统一字节来源。
//! Read-only byte source shared by every compiled data file.
//!
//! 三种来源 / Three backings:
//! - 内存中的字节（测试、工具） / owned bytes (tests, tools);
//! - mmap 的原始文件（桌面、开发） / an mmapped raw file (desktop, development);
//! - **分块压缩文件**（WVPK）：按固定大小切块、每块独立 brotli 压缩，读到哪块才解压哪块，
//!   解压结果放进容量固定的缓存。可以直接从 APK 内的某段偏移读取（资源不压缩存放），
//!   因此手机上不需要再解压出一份完整文件——装机占用 ≈ APK 大小。
//!   a **block-compressed file** (WVPK): fixed-size blocks, each brotli-compressed on its own and
//!   decoded on first touch into a bounded cache. It can be read straight out of a region of the APK
//!   (stored uncompressed), so nothing is extracted on the device — footprint ≈ APK size.
//!
//! WVPK 布局（小端）/ WVPK layout (little endian):
//! ```text
//! Header 32 bytes: "WVPK" | version u32 | block_size u32 | n_blocks u32 | raw_len u64 | pad
//! Offsets [u32; n_blocks + 1]   各压缩块相对数据区起点的偏移 / compressed block offsets
//! Data    brotli 块依次排列 / brotli blocks back to back
//! ```

use std::fs::File;
use std::io::{self, Read};
use std::path::{Path, PathBuf};
use std::cell::RefCell;
use std::sync::Arc;

pub const MAGIC: &[u8; 4] = b"WVPK";
pub const VERSION: u32 = 1;
const HEADER: usize = 32;
/// 默认块大小：实测 16 KiB 的缓存命中与单块解压耗时最好，压缩率只比 64 KiB 差约 5%。
/// Default block size: 16 KiB measured best for hit rate and per-block decode time, at ~5% worse
/// ratio than 64 KiB.
pub const DEFAULT_BLOCK: usize = 16 << 10;
/// 默认缓存预算（每个文件，字节）。 Default cache budget per file, in bytes.
pub const DEFAULT_CACHE: usize = 6 << 20;

static CACHE_BUDGET: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(DEFAULT_CACHE);

/// 设置之后打开的分块文件的缓存预算（每个文件）。 Cache budget for packed files opened afterwards.
pub fn set_cache_budget(bytes: usize) {
    CACHE_BUDGET.store(bytes, std::sync::atomic::Ordering::Relaxed);
}

/// 数据来源：文件路径加上可选的区间（用于直接读取 APK 内的资源）。
/// Where data lives: a file, optionally a byte range inside it (an asset inside the APK).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Source {
    pub path: PathBuf,
    pub offset: u64,
    /// `None` 表示读到文件末尾。 `None` = to the end of the file.
    pub len: Option<u64>,
}

impl Source {
    pub fn file(path: impl Into<PathBuf>) -> Self {
        Source { path: path.into(), offset: 0, len: None }
    }

    pub fn range(path: impl Into<PathBuf>, offset: u64, len: u64) -> Self {
        Source { path: path.into(), offset, len: Some(len) }
    }
}

impl From<PathBuf> for Source {
    fn from(p: PathBuf) -> Self {
        Source::file(p)
    }
}

impl From<&Path> for Source {
    fn from(p: &Path) -> Self {
        Source::file(p)
    }
}

/// 只读字节块；克隆很便宜（共享底层数据与缓存）。 Read-only bytes; clones share data and cache.
#[derive(Clone)]
pub struct Blob(Inner);

#[derive(Clone)]
enum Inner {
    Owned(Arc<Vec<u8>>),
    Mapped(Arc<memmap2::Mmap>, usize, usize),
    Packed(Arc<Packed>),
}

fn bad(m: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, m.to_string())
}

#[inline]
fn le32(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

impl Blob {
    pub fn from_bytes(bytes: Vec<u8>) -> Self {
        Blob(Inner::Owned(Arc::new(bytes)))
    }

    /// 打开数据：WVPK 自动识别，其余按原始文件 mmap。 Open; WVPK is detected, anything else is mmapped.
    pub fn open(src: &Source) -> io::Result<Self> {
        Self::open_with_cache(src, CACHE_BUDGET.load(std::sync::atomic::Ordering::Relaxed))
    }

    pub fn open_with_cache(src: &Source, cache_bytes: usize) -> io::Result<Self> {
        let file = File::open(&src.path)?;
        let file_len = file.metadata()?.len();
        let len = src.len.unwrap_or(file_len.saturating_sub(src.offset));
        if src.offset.checked_add(len).is_none_or(|end| end > file_len) {
            return Err(bad("range outside file"));
        }
        let mut magic = [0u8; 4];
        if len >= HEADER as u64 {
            read_exact_at(&file, &mut magic, src.offset)?;
        }
        if &magic == MAGIC {
            return Ok(Blob(Inner::Packed(Arc::new(Packed::open(file, src.offset, len, cache_bytes)?))));
        }
        // mmap 的偏移必须按页对齐：从页边界映射，再跳过前面的零头。
        // mmap offsets must be page aligned: map from the page boundary and skip the slack.
        let page = 1u64 << 16;
        let start = src.offset / page * page;
        let skip = (src.offset - start) as usize;
        // SAFETY: 只读映射；数据文件由本程序生成，所有访问都经过切片下标检查。
        // Read-only mapping of our own data files; every access is bounds-checked.
        let map = unsafe {
            memmap2::MmapOptions::new()
                .offset(start)
                .len(skip + len as usize)
                .map(&file)?
        };
        Ok(Blob(Inner::Mapped(Arc::new(map), skip, len as usize)))
    }

    pub fn len(&self) -> usize {
        match &self.0 {
            Inner::Owned(v) => v.len(),
            Inner::Mapped(_, _, len) => *len,
            Inner::Packed(p) => p.raw_len,
        }
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// 是否为分块压缩来源。 Whether this blob is block-compressed.
    pub fn is_packed(&self) -> bool {
        matches!(self.0, Inner::Packed(_))
    }

    /// 以切片形式访问 `[off, off + len)`；越界时 panic（与切片下标一致）。
    /// 跨块的区间会拼接到临时缓冲区。
    /// Access `[off, off + len)` as a slice; panics when out of range, like slice indexing.
    /// Ranges that straddle blocks are assembled into a temporary buffer.
    #[inline]
    pub fn with<R>(&self, off: usize, len: usize, f: impl FnOnce(&[u8]) -> R) -> R {
        match &self.0 {
            Inner::Owned(v) => f(&v[off..off + len]),
            Inner::Mapped(m, skip, total) => {
                assert!(off + len <= *total, "blob range out of bounds");
                f(&m[skip + off..skip + off + len])
            }
            Inner::Packed(p) => p.with(off, len, f),
        }
    }

    #[inline]
    pub fn u8(&self, off: usize) -> u8 {
        self.with(off, 1, |b| b[0])
    }

    #[inline]
    pub fn u16(&self, off: usize) -> u16 {
        self.with(off, 2, |b| u16::from_le_bytes([b[0], b[1]]))
    }

    #[inline]
    pub fn u32(&self, off: usize) -> u32 {
        self.with(off, 4, |b| u32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    /// 读出全部字节（小文件用，如简繁表）。 Copy everything out (for small files).
    pub fn to_vec(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.len());
        let step = DEFAULT_BLOCK;
        let mut off = 0;
        while off < self.len() {
            let n = step.min(self.len() - off);
            self.with(off, n, |b| out.extend_from_slice(b));
            off += n;
        }
        out
    }

    /// 清空解压缓存（系统内存紧张时调用）。 Drop the decode cache, e.g. under memory pressure.
    pub fn trim(&self) {
        if let Inner::Packed(p) = &self.0 {
            p.cache.borrow_mut().clear();
        }
    }

    /// 缓存统计：(命中, 未命中)。原始来源恒为 (0, 0)。 Cache (hits, misses); zeros for raw sources.
    pub fn cache_stats(&self) -> (u64, u64) {
        match &self.0 {
            Inner::Packed(p) => {
                let c = p.cache.borrow_mut();
                (c.hits, c.misses)
            }
            _ => (0, 0),
        }
    }
}

#[cfg(unix)]
fn read_exact_at(file: &File, buf: &mut [u8], off: u64) -> io::Result<()> {
    std::os::unix::fs::FileExt::read_exact_at(file, buf, off)
}

#[cfg(windows)]
fn read_exact_at(file: &File, mut buf: &mut [u8], mut off: u64) -> io::Result<()> {
    use std::os::windows::fs::FileExt;
    while !buf.is_empty() {
        let n = file.seek_read(buf, off)?;
        if n == 0 {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        buf = &mut buf[n..];
        off += n as u64;
    }
    Ok(())
}

// ------------------------------------------------------------------ packed

struct Packed {
    file: File,
    /// 数据区在文件中的绝对偏移。 Absolute file offset of the data area.
    data_off: u64,
    block: usize,
    raw_len: usize,
    offsets: Vec<u32>,
    cache: RefCell<Cache>,
}

struct Cache {
    /// 块号 → 槽位 + 1（0 = 不在缓存）。 Block index → slot + 1 (0 = absent).
    slot_of: Vec<u32>,
    slots: Vec<Slot>,
    capacity: usize,
    tick: u64,
    hits: u64,
    misses: u64,
}

struct Slot {
    block: u32,
    last: u64,
    data: Box<[u8]>,
}

impl Cache {
    /// 取块（缺失时解压并放入缓存），返回槽位。 Get a block, decoding it on a miss; returns its slot.
    fn load(&mut self, p: &Packed, i: usize) -> usize {
        self.tick += 1;
        let tick = self.tick;
        let slot = self.slot_of[i];
        if slot != 0 {
            self.hits += 1;
            let s = slot as usize - 1;
            self.slots[s].last = tick;
            return s;
        }
        self.misses += 1;
        let data = p.decode(i);
        let s = if self.slots.len() < self.capacity {
            self.slots.push(Slot { block: i as u32, last: tick, data });
            self.slots.len() - 1
        } else {
            // 淘汰最久未用的块。 Evict the least recently used block.
            let (victim, _) = self.slots.iter().enumerate().min_by_key(|(_, s)| s.last).unwrap();
            self.slot_of[self.slots[victim].block as usize] = 0;
            self.slots[victim] = Slot { block: i as u32, last: tick, data };
            victim
        };
        self.slot_of[i] = s as u32 + 1;
        s
    }

    fn clear(&mut self) {
        for s in self.slots.drain(..) {
            self.slot_of[s.block as usize] = 0;
        }
    }
}

impl Packed {
    fn open(file: File, base: u64, len: u64, cache_bytes: usize) -> io::Result<Self> {
        let mut h = [0u8; HEADER];
        read_exact_at(&file, &mut h, base)?;
        if le32(&h, 4) != VERSION {
            return Err(bad("unsupported pack version"));
        }
        let block = le32(&h, 8) as usize;
        let n = le32(&h, 12) as usize;
        let raw_len = u64::from_le_bytes(h[16..24].try_into().unwrap()) as usize;
        if block == 0 || block > 16 << 20 || n != raw_len.div_ceil(block) {
            return Err(bad("bad pack header"));
        }
        // 先确认偏移表放得进文件再分配，坏头部不能让进程因申请几 GB 内存而直接中止。
        // Check the offset table fits before allocating: a bad header must not abort the process on a huge alloc.
        if (HEADER as u64).saturating_add((n as u64 + 1) * 4) > len {
            return Err(bad("truncated pack"));
        }
        let mut raw = vec![0u8; (n + 1) * 4];
        read_exact_at(&file, &mut raw, base + HEADER as u64)?;
        let offsets: Vec<u32> = raw.chunks_exact(4).map(|c| le32(c, 0)).collect();
        let data_off = base + HEADER as u64 + raw.len() as u64;
        if offsets.windows(2).any(|w| w[0] > w[1])
            || data_off + *offsets.last().unwrap() as u64 > base + len
        {
            return Err(bad("truncated pack"));
        }
        let capacity = (cache_bytes / block).clamp(4, n.max(4));
        Ok(Packed {
            file,
            data_off,
            block,
            raw_len,
            offsets,
            cache: RefCell::new(Cache {
                slot_of: vec![0; n],
                slots: Vec::with_capacity(capacity),
                capacity,
                tick: 0,
                hits: 0,
                misses: 0,
            }),
        })
    }

    /// 回调在持锁期间执行（都是几十字节的解析），省去每次访问的引用计数开销。
    /// The callback runs under the lock (it only parses a few bytes), avoiding per-access refcounting.
    #[inline]
    fn with<R>(&self, off: usize, len: usize, f: impl FnOnce(&[u8]) -> R) -> R {
        assert!(off + len <= self.raw_len, "blob range out of bounds");
        let first = off / self.block;
        let last = if len == 0 { first } else { (off + len - 1) / self.block };
        let mut c = self.cache.borrow_mut();
        if first == last {
            let slot = c.load(self, first);
            let s = off - first * self.block;
            return f(&c.slots[slot].data[s..s + len]);
        }
        let mut buf = Vec::with_capacity(len);
        let mut pos = off;
        while pos < off + len {
            let i = pos / self.block;
            let slot = c.load(self, i);
            let s = pos - i * self.block;
            let n = (self.block - s).min(off + len - pos);
            buf.extend_from_slice(&c.slots[slot].data[s..s + n]);
            pos += n;
        }
        drop(c);
        f(&buf)
    }

    fn decode(&self, i: usize) -> Box<[u8]> {
        let want = if i + 1 == self.offsets.len() - 1 {
            self.raw_len - i * self.block
        } else {
            self.block
        };
        let (s, e) = (self.offsets[i] as u64, self.offsets[i + 1] as u64);
        let mut comp = vec![0u8; (e - s) as usize];
        let mut out = Vec::with_capacity(want);
        let ok = read_exact_at(&self.file, &mut comp, self.data_off + s).is_ok()
            && brotli_decompressor::Decompressor::new(&comp[..], 4096)
                .take(want as u64 + 1)
                .read_to_end(&mut out)
                .is_ok()
            && out.len() == want;
        if !ok {
            // 损坏的块按全零处理：读者都做了边界检查，只会得到错误结果而不会崩溃。
            // A corrupt block reads as zeros: readers are bounds-checked, so results are wrong but safe.
            out.clear();
            out.resize(want, 0);
        }
        out.into_boxed_slice()
    }
}

/// 把原始字节压成 WVPK（构建工具用）。 Pack raw bytes into WVPK (build tools).
#[cfg(feature = "pack")]
pub fn pack(raw: &[u8], block: usize) -> Vec<u8> {
    let blocks: Vec<&[u8]> = raw.chunks(block).collect();
    let params = brotli::enc::BrotliEncoderParams {
        quality: 11,
        lgwin: 22,
        size_hint: block,
        ..Default::default()
    };
    let comp: Vec<Vec<u8>> = std::thread::scope(|s| {
        let workers = std::thread::available_parallelism().map_or(4, |n| n.get());
        let chunk = blocks.len().div_ceil(workers).max(1);
        let handles: Vec<_> = blocks
            .chunks(chunk)
            .map(|part| {
                let params = params.clone();
                s.spawn(move || {
                    part.iter()
                        .map(|b| {
                            let mut out = Vec::new();
                            brotli::BrotliCompress(&mut &b[..], &mut out, &params).expect("brotli");
                            out
                        })
                        .collect::<Vec<_>>()
                })
            })
            .collect();
        handles.into_iter().flat_map(|h| h.join().unwrap()).collect()
    });
    let mut out = Vec::new();
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&(block as u32).to_le_bytes());
    out.extend_from_slice(&(blocks.len() as u32).to_le_bytes());
    out.extend_from_slice(&(raw.len() as u64).to_le_bytes());
    out.resize(HEADER, 0);
    let mut off = 0u32;
    for c in &comp {
        out.extend_from_slice(&off.to_le_bytes());
        off += c.len() as u32;
    }
    out.extend_from_slice(&off.to_le_bytes());
    for c in &comp {
        out.extend_from_slice(c);
    }
    out
}

#[cfg(all(test, feature = "pack"))]
mod tests {
    use super::*;

    #[test]
    fn packed_roundtrip_and_straddle() {
        let raw: Vec<u8> = (0..300_000u32).map(|i| (i * 7 % 251) as u8).collect();
        let dir = std::env::temp_dir().join(format!("wvpk-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        // 前面垫 100 字节，模拟 APK 内的资源区间。 100 bytes of prefix, like an asset inside an APK.
        let mut file = vec![0xAAu8; 100];
        let packed = pack(&raw, 4096);
        file.extend_from_slice(&packed);
        file.extend_from_slice(&[0x55; 10]);
        let p = dir.join("x.bin");
        std::fs::write(&p, &file).unwrap();
        let b = Blob::open_with_cache(&Source::range(&p, 100, packed.len() as u64), 4096 * 4).unwrap();
        assert!(b.is_packed());
        assert_eq!(b.len(), raw.len());
        for &(off, len) in &[(0, 10), (4090, 20), (8191, 2), (299_990, 10), (12_000, 9000)] {
            b.with(off, len, |s| assert_eq!(s, &raw[off..off + len]));
        }
        assert_eq!(b.u32(4094), u32::from_le_bytes(raw[4094..4098].try_into().unwrap()));
        assert_eq!(b.to_vec(), raw);
        // 原始文件经偏移 mmap。 A raw file mapped at an offset.
        std::fs::write(&p, [&[1u8, 2, 3][..], &raw].concat()).unwrap();
        let m = Blob::open(&Source::range(&p, 3, raw.len() as u64)).unwrap();
        assert!(!m.is_packed());
        m.with(70_000, 5, |s| assert_eq!(s, &raw[70_000..70_005]));
        std::fs::remove_dir_all(&dir).ok();
    }
}
