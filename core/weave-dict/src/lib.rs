//! 织文词库：音节表、编译后的前缀树词库格式与构建工具。
//! WeaveText dictionary: syllable table, compiled trie lexicon format, builder.

pub mod blob;
pub mod gram;
pub mod hand;
pub mod handnet;
pub mod follow;
pub mod lexicon;
pub mod syllable;

pub use lexicon::{Builder, Entry, Kind, Lexicon, NodeId, ROOT};
