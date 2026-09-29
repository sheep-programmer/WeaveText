# 手写识别网络的训练 / Training the handwriting network

`data/hand/hand_net.wvn` 由本目录的脚本训练、导出，内核的 `weave-dict/src/handnet.rs` 读取。网络只用可再分发的开放数据合成训练样本，
不使用任何只限研究用途的手写数据库。

`data/hand/hand_net.wvn` is trained and exported by the scripts here and read by `weave-dict/src/handnet.rs`. Training
samples are synthesised from redistributable open data only; no research-only handwriting database is used.

## 数据 / Data

| 来源 Source | 许可 License | 用途 Use |
|---|---|---|
| [Make Me a Hanzi](https://github.com/skishore/makemeahanzi) `graphics.txt` 的笔画中线（与 `data/build.sh` 同一提交） | Arphic Public License | 按笔顺的笔画，加抖动、连笔、仿射与弹性变形后画成图 / stroke medians, jittered, joined and warped |
| 霞鹜文楷、Noto Sans SC、Noto Serif SC、马善政楷书、智莽行书、刘建毛草、龙藏体、站酷小薇、站酷快乐体 | SIL OFL 1.1 | 字形骨架化后当作另一种书写样本 / glyph skeletons as extra writing styles |

网络权重由上述数据派生，与 `hand.wvz` 一样作为独立数据文件分发，适用 Arphic Public License（见 `docs/THIRD_PARTY.md`）。
The weights derive from this data and ship as a separate data file like `hand.wvz`, under the Arphic Public License.

## 步骤 / Steps

依赖 / Requirements：Python 3.12，`torch numpy opencv-python-headless scikit-image fonttools pillow`；Apple 芯片用 MPS 训练。

```sh
export HANDNET_WORK=/path/to/work        # 放字体（fonts/）、骨架缓存与检查点 / fonts/, skeleton cache, checkpoints
python skel.py                            # 预先算好各字体的字形骨架 / precompute glyph skeletons
python train.py 40000 r4                  # 约 40000 步（Apple M 系列约 3 小时）/ ~40k steps (~3 h on Apple silicon)
python train.py 40000 r4 r4.last.pt       # 中断后接着训 / resume after an interruption
python export.py r4.pt ../../data/hand/hand_net.wvn
```

可选：`HANDNET_EVAL` 指向一个 `handeval` 格式（`字<TAB>x,y x,y;…`）的真人笔迹文件，训练时每 1000 步报告准确率；
`cargo run --release --example handeval -p weave-dict -- <模型> <样本>` 可单独评测模板或网络。

Optional: point `HANDNET_EVAL` at real ink in the `handeval` format to report accuracy every 1000 steps;
`cargo run --release --example handeval -p weave-dict -- <model> <samples>` evaluates templates or the network alone.
