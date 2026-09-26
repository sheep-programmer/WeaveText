//! 织文词库：音节表、编译后的前缀树词库格式与构建工具。
//! WeaveText dictionary: syllable table, compiled trie lexicon format, builder.

pub mod gram;
pub mod lexicon;
pub mod syllable;

pub use lexicon::{Builder, Entry, Kind, Lexicon, NodeId, ROOT};
