//! 读取 Rime octagram `.gram`（darts-clone 双数组，字符级 n-gram），导出为文本或统计。
//! Reader for Rime octagram `.gram` files (darts-clone double array, character n-grams).
//!
//! 用法 / Usage:
//! ```text
//! gramdump stats <file.gram>
//! gramdump dump  <file.gram> <out.tsv> [min_value]    # 每行: 键<TAB>值（值 = ln(weight)*10000）
//! gramdump build <file.gram> <out.wvg> [min4] [min3]  # 剪枝成织文搭配模型：2 字全保留，3/4 字按阈值（nat）
//! ```
//! 格式说明（自行实现，只依据公开的文件布局）：文件头 32 字节格式串 + u32 校验 + u32 单元数 +
//! i32 相对偏移，随后是 u32 单元数组。键为自定义变长编码的 UTF-32 序列。
//! Layout: 32-byte format string, u32 checksum, u32 unit count, i32 relative offset, then units.

use std::io::{BufWriter, Write};

struct Da<'a> {
    units: &'a [u8],
    n: usize,
}

impl<'a> Da<'a> {
    #[inline]
    fn unit(&self, i: usize) -> u32 {
        let o = i * 4;
        u32::from_le_bytes([
            self.units[o],
            self.units[o + 1],
            self.units[o + 2],
            self.units[o + 3],
        ])
    }
    #[inline]
    fn has_leaf(u: u32) -> bool {
        (u >> 8) & 1 == 1
    }
    #[inline]
    fn value(u: u32) -> u32 {
        u & 0x7FFF_FFFF
    }
    #[inline]
    fn label(u: u32) -> u32 {
        u & (0x8000_0000 | 0xFF)
    }
    #[inline]
    fn offset(u: u32) -> usize {
        ((u >> 10) << ((u & (1 << 9)) >> 6)) as usize
    }
}

/// 解码自定义编码为字符串；失败返回 None。 Decode the custom encoding.
fn decode(b: &[u8]) -> Option<String> {
    let mut out = String::new();
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        if c < 0x80 {
            out.push(c as char);
            i += 1;
        } else if (0x80..0xE0).contains(&c) {
            let lo = *b.get(i + 1)?;
            out.push(char::from_u32((((c as u32) - 0x40) << 8) | lo as u32)?);
            i += 2;
        } else if c == 0xE0 {
            out.push('\0');
            i += 1;
        } else if c == 0xE1 {
            let hi = *b.get(i + 1)?;
            out.push(char::from_u32(((hi as u32) - 0x40) << 8)?);
            i += 2;
        } else {
            let n = (c & 0x0F) as usize;
            if n == 0 || n > 5 || i + n > b.len() {
                return None;
            }
            let mut concat: u64 = 0;
            for k in 0..n {
                concat = (concat << 7) | (*b.get(i + 1 + k)? as u64 & 0x7F);
            }
            let total = 7 * n as u32;
            let shifted: u64 = if total > 32 {
                concat >> (total - 32)
            } else {
                concat << (32 - total)
            };
            let k = 5 - n as u32;
            out.push(char::from_u32((shifted >> (7 * k)) as u32)?);
            i += 1 + n;
        }
    }
    Some(out)
}

fn walk(da: &Da, id: usize, key: &mut Vec<u8>, f: &mut dyn FnMut(&[u8], u32)) {
    let u = da.unit(id);
    let base = id ^ Da::offset(u);
    if Da::has_leaf(u) {
        let leaf = da.unit(base);
        f(key, Da::value(leaf));
    }
    for c in 1u32..=255 {
        let child = base ^ c as usize;
        if child >= da.n {
            continue;
        }
        if Da::label(da.unit(child)) == c {
            key.push(c as u8);
            walk(da, child, key, f);
            key.pop();
        }
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let file = std::fs::File::open(&args[1]).expect("open");
    // SAFETY: 只读映射。 Read-only mapping.
    let map = unsafe { memmap2::Mmap::map(&file).expect("mmap") };
    assert!(map.starts_with(b"Rime::Grammar/"), "not a gram file");
    let size = u32::from_le_bytes(map[36..40].try_into().unwrap()) as usize;
    let rel = i32::from_le_bytes(map[40..44].try_into().unwrap()) as isize;
    let start = (40 + rel) as usize;
    let da = Da {
        units: &map[start..start + size * 4],
        n: size,
    };
    eprintln!("units: {size}");
    let mode = args[0].as_str();
    let min_value: u32 = args.get(3).and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut by_len = [0usize; 12];
    // 各长度的值分布（0.5 nat 一档）。 Value histogram per key length, 0.5 nat bins.
    let mut fine = vec![vec![0usize; 80]; 7];
    let mut hist = [0usize; 16];
    let mut bad = 0usize;
    let mut writer = if mode == "dump" {
        Some(BufWriter::new(
            std::fs::File::create(&args[2]).expect("create"),
        ))
    } else {
        None
    };
    let mut builder = (mode == "build").then(weave_dict::gram::GramBuilder::default);
    let min4: f32 = args.get(3).and_then(|v| v.parse().ok()).unwrap_or(18.0);
    let min3: f32 = args.get(4).and_then(|v| v.parse().ok()).unwrap_or(0.0);
    let mut key = Vec::new();
    walk(&da, 0, &mut key, &mut |k, v| match decode(k) {
        Some(s) => {
            let n = s.chars().count().min(11);
            by_len[n] += 1;
            hist[((v / 10000) as usize).min(15)] += 1;
            fine[n.min(6)][((v / 5000) as usize).min(79)] += 1;
            if let Some(w) = writer.as_mut() {
                if v >= min_value {
                    let _ = writeln!(w, "{s}\t{v}");
                }
            }
            if let Some(b) = builder.as_mut() {
                let score = v as f32 / 10000.0;
                let keep = !s.contains('$')
                    && !s.chars().any(|c| c.is_ascii() || c.is_control())
                    && match n {
                        2 => true,
                        3 => score >= min3,
                        4 => score >= min4,
                        _ => false,
                    };
                if keep {
                    b.push(&s, score);
                }
            }
        }
        None => bad += 1,
    });
    if let Some(b) = builder {
        eprintln!("kept {} keys", b.len());
        b.write_to(std::path::Path::new(&args[2])).expect("write");
        let size = std::fs::metadata(&args[2]).map(|m| m.len()).unwrap_or(0);
        eprintln!("wrote {} ({:.1} MiB)", args[2], size as f64 / 1048576.0);
    }
    eprintln!("keys by char length: {:?}", &by_len[1..]);
    eprintln!("value histogram (ln weight buckets 0..15): {hist:?}");
    eprintln!("undecodable: {bad}");
    for (len, row) in fine.iter().enumerate().skip(1) {
        let nz: Vec<String> = row
            .iter()
            .enumerate()
            .filter(|(_, c)| **c > 0)
            .map(|(i, c)| format!("{:.1}:{}", i as f64 / 2.0, c))
            .collect();
        if !nz.is_empty() {
            eprintln!("len {len}: {}", nz.join(" "));
        }
    }
}
