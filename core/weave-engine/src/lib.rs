//! 织文输入法内核。 WeaveText IME engine.
//!
//! - [`graph`]：全拼音节图（模糊音、简拼、纠错） / full pinyin syllable graph
//! - [`shuangpin`]：双拼方案 / double pinyin schemes
//! - [`t9`]：九键 / T9
//! - [`decoder`]：词图、整句、候选 / lattice, sentence, candidates
//! - [`userdict`]：用户词库与学习 / user dictionary & learning
//! - [`table`]：五笔与英文码表查询 / wubi & English table lookup
//! - [`session`]：给界面用的状态机 / UI-facing state machine

pub mod cloud;
pub mod convert;
pub mod decoder;
pub mod graph;
pub mod session;
pub mod shuangpin;
pub mod special;
pub mod t9;
pub mod table;
pub mod userdict;

pub use session::{CandidateView, Engine, Options, Paths, Schema, Snapshot, UserWord};
