# 快捷输入：日期、时间、计算与查字 / Quick input: dates, time, calculation and character lookup

中文候选中的日期、时间等工具在本机生成，沿用系统时区。普通词语仍为首选，当前值排在后面，点选后才上屏；不用先把“时间”两个字提交到正文，也不替换正文中已输入的词。

Date/time tools generate candidates locally using the system time zone. The ordinary word stays first, followed by current values that commit only when selected. Type the word's input code while composing; already committed text is not rewritten.

| 输入 / Input | 候选 / Candidates |
| --- | --- |
| `shijian`、`sj`（时间） | 当前 `HH:mm`、日期加时间、中文时间、含秒时间 / Current time, date and time, Chinese time, time with seconds |
| `riqi`、`rq`（日期） | 当前中文日期、`YYYY-MM-DD`、日期加星期、斜线日期 / Current date in Chinese, ISO, with weekday, or slash format |
| `xingqi`、`xq`、`zhouji`（星期、周几） | 当前星期 / Current weekday |
| `jintian`（今天） | 今天日期 / Today's date |
| `mingtian`（明天） | 明天日期 / Tomorrow's date |
| `zuotian`（昨天） | 昨天日期 / Yesterday's date |
| `xianzai`（现在） | 当前时间 / Current time |

全拼直接用这些输入码；双拼与九键在整词首选为上述词时也提供当前值。只在整串匹配时触发，句子里的片段与分段选词后的剩余拼音不插入日期候选。时间按候选生成时取值，不是持续走动的时钟。快捷短语模板可用 `{date}`、`{time}`、`{weekday}`，在“词库 → 快捷短语与模板”编辑。

Full pinyin accepts these codes directly. Double pinyin and keypad input also offer values when the first complete decoded word matches. Sentence fragments and remaining composition after a partial selection do not trigger date insertion. Time is sampled when candidates are generated, rather than updated continuously. Custom shortcut templates support `{date}`, `{time}` and `{weekday}` under Dictionary → Shortcuts and templates.

## 算式与数字 / Expressions and numbers

全拼中先输入 `v`，再输入数字或算式。数字、运算符和括号都留在组合串；空格确认首选，点选可以选其他格式，回车保留原始 `v…` 输入。计算器沿用插件市场中已有的启停开关。

In full pinyin, type `v` followed by a number or expression. Digits, operators and parentheses stay in composition. Space confirms the first candidate, tapping chooses another format, and Return commits the original `v…` text. The existing calculator switch in the extension market controls this feature.

| 输入 / Input | 可选结果 / Offered results |
| --- | --- |
| `v(128+32)*4`、`v（128＋32）×4=` | `640`、完整算式、大写金额 / Result, complete equation, financial amount |
| `v1234` | `1234`、壹仟贰佰叁拾肆元整、一千二百三十四、`1,234` |
| `v1234.50` | 原数字、壹仟贰佰叁拾肆元伍角、一千二百三十四点五零、`1,234.50` |
| `v-1234.50` | 原数字、负壹仟贰佰叁拾肆元伍角、负一千二百三十四点五零、`-1,234.50` |

支持 `+ - * / ^ %` 及括号，也接受 `× ÷ ＋ － − （ ） ．` 和末尾单个 `=`／`＝`。`%` 表示取余，例如 `v7%3` 为 `1`；不将其解释为百分比。金额按原数字精确转换，最多 16 位整数、两位小数；算术使用浮点数，结果最多显示十位小数，不能替代财务核算。除零、未完成和无效算式不给结果。

Operators include `+ - * / ^ %` and parentheses, with common Unicode variants and one optional terminal equals sign. `%` is remainder: `v7%3` gives `1`, not a percentage. Financial conversion uses the original digits exactly, with at most 16 integer digits and two decimal places. Arithmetic uses floating point and displays up to ten decimals; it is not financial accounting. Division by zero, incomplete and invalid expressions produce no result.

也可以直接在正文输入 `128*4=`，然后点选结果 `512`，得到 `128*4=512`。手机软键盘与外接键盘等号走同一逻辑；输入框须允许读取光标前文本，密码等敏感输入框不提供计算候选。Mac 默认原样直通的英文模式沿用应用直接处理输入；中文模式下正文算式使用半角 `=` 出结果，`v` 模式支持两种等号。`v` 算式中的括号保持组合，符号面板的括号入口只加入左括号，再自行输入右括号。

You can also type `128*4=` into the editor and select `512`, producing `128*4=512`. Android soft and physical equals keys share this behavior. It requires readable text before the cursor and is suppressed in sensitive fields such as passwords. Mac's default literal English mode continues to let the application handle input; editor-side formulas use ASCII `=` in Chinese mode, while v composition accepts either equals sign. Parentheses remain in v composition; the paired-symbol entry adds only its opening parenthesis, followed by a manually entered closer.

## 不会读的字 / Characters with unknown readings

全拼中 `u` 后输入部件拼音或笔画：`ukoukoukou` 查“品”，`uririri` 查“晶”，`uhs` 查“十”。笔画码为 `h` 横、`s` 竖、`p` 撇、`n` 点、`z` 折。候选显示读音，确认后输入汉字。此功能沿用已随包提供的字形表；单独 `u` 和普通英文词仍按原输入逻辑处理。

In full pinyin, type `u` followed by component pinyin or strokes: `ukoukoukou` finds 品, `uririri` finds 晶, and `uhs` finds 十. Stroke codes are `h` horizontal, `s` vertical, `p` left-falling, `n` dot and `z` turning. Candidates show pronunciations and commit the selected character. Lookup uses the existing bundled shape table; a lone `u` and ordinary English words keep their existing behavior.

已知读音但候选太多时，可在拼音后按反引号再输入部件或笔画，例如 ``shi`rc`` 筛选“时”；手机展开候选也有横竖撇点折笔画键。

For a known pronunciation with too many candidates, append a backtick and a component/stroke code, such as ``shi`rc`` for 时. The expanded Android candidate view also provides stroke keys.
