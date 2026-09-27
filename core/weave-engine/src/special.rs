//! 特殊候选：算式计算、大写金额与中文数字、日期时间。
//! Special candidates: arithmetic, financial (uppercase) numerals and Chinese numbers, date and time.
//!
//! - `v` 开头的数字或算式（v1234、v12.5*4）：结果、大写金额、中文小写、千分位。
//!   `v` followed by a number or an expression: result, uppercase amount, Chinese numerals, thousands separators.
//! - 拼音 `rq` / `sj` / `xq`：今天的日期、现在的时间、星期几（插在首选之后）。
//!   Pinyin `rq` / `sj` / `xq`: today's date, the time, the weekday (inserted after the first candidate).

/// 计算四则运算（+ − × ÷ % ^ 与括号，支持 × ÷ 符号），出错或结果非有限数时返回 None。
/// Evaluate + − × ÷ % ^ with parentheses (× ÷ accepted); None on errors or non-finite results.
pub fn eval(expr: &str) -> Option<f64> {
    let s: Vec<char> = expr
        .chars()
        .filter(|c| !c.is_whitespace())
        .map(|c| match c {
            '×' | 'x' | 'X' => '*',
            '÷' => '/',
            '（' => '(',
            '）' => ')',
            '－' | '−' => '-',
            '＋' => '+',
            c => c,
        })
        .collect();
    if s.is_empty() || s.len() > 200 {
        return None;
    }
    let mut p = Parser { s: &s, i: 0, depth: 0 };
    let v = p.sum()?;
    (p.i == s.len() && v.is_finite()).then_some(v)
}

struct Parser<'a> {
    s: &'a [char],
    i: usize,
    depth: usize,
}

impl Parser<'_> {
    fn peek(&self) -> Option<char> {
        self.s.get(self.i).copied()
    }

    fn sum(&mut self) -> Option<f64> {
        let mut v = self.product()?;
        while let Some(op @ ('+' | '-')) = self.peek() {
            self.i += 1;
            let r = self.product()?;
            v = if op == '+' { v + r } else { v - r };
        }
        Some(v)
    }

    fn product(&mut self) -> Option<f64> {
        let mut v = self.power()?;
        while let Some(op @ ('*' | '/' | '%')) = self.peek() {
            self.i += 1;
            let r = self.power()?;
            v = match op {
                '*' => v * r,
                '/' if r != 0.0 => v / r,
                '%' if r != 0.0 => v % r,
                _ => return None,
            };
        }
        Some(v)
    }

    fn power(&mut self) -> Option<f64> {
        let b = self.unary()?;
        if self.peek() == Some('^') {
            self.i += 1;
            let e = self.power()?;
            return Some(b.powf(e));
        }
        Some(b)
    }

    fn unary(&mut self) -> Option<f64> {
        match self.peek()? {
            '-' => {
                self.i += 1;
                Some(-self.unary()?)
            }
            '+' => {
                self.i += 1;
                self.unary()
            }
            _ => self.atom(),
        }
    }

    fn atom(&mut self) -> Option<f64> {
        if self.peek()? == '(' {
            self.depth += 1;
            if self.depth > 32 {
                return None;
            }
            self.i += 1;
            let v = self.sum()?;
            if self.peek() != Some(')') {
                return None;
            }
            self.i += 1;
            self.depth -= 1;
            return Some(v);
        }
        let start = self.i;
        while matches!(self.peek(), Some('0'..='9' | '.')) {
            self.i += 1;
        }
        let t: String = self.s[start..self.i].iter().collect();
        if t.is_empty() || t.matches('.').count() > 1 {
            return None;
        }
        t.parse().ok()
    }
}

/// 结果的显示：整数不带小数点，否则最多 10 位小数并去掉末尾的 0。
/// Result display: integers without a decimal point, otherwise up to 10 decimals, trailing zeros trimmed.
pub fn format_number(v: f64) -> String {
    if v.fract() == 0.0 && v.abs() < 1e15 {
        return format!("{}", v as i64);
    }
    let s = format!("{v:.10}");
    let s = s.trim_end_matches('0').trim_end_matches('.');
    if s == "-0" { "0".into() } else { s.to_string() }
}

/// 是否是一个算式（至少一个运算符，只含数字、运算符与括号）。 Whether `s` is an expression with at least one operator.
pub fn is_expression(s: &str) -> bool {
    let body = s.trim_start_matches(['-', '+']);
    body.chars().any(|c| "+-*/×÷%^".contains(c))
        && s.chars().all(|c| c.is_ascii_digit() || " .+-*/×÷%^()（）".contains(c))
        && s.chars().any(|c| c.is_ascii_digit())
}

const DIGITS_UPPER: [char; 10] = ['零', '壹', '贰', '叁', '肆', '伍', '陆', '柒', '捌', '玖'];
const DIGITS_LOWER: [char; 10] = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九'];

/// 四位一节的读法（千百十个），`zero_lead` 为真时前导零要读出。 One group of four digits.
fn group(n: u32, digits: &[char; 10], units: [&str; 4], zero_lead: bool) -> String {
    let d = [n / 1000, n / 100 % 10, n / 10 % 10, n % 10];
    let mut out = String::new();
    let mut zero = zero_lead;
    let mut started = false;
    for (i, &x) in d.iter().enumerate() {
        if x == 0 {
            if started || zero_lead {
                zero = true;
            }
            continue;
        }
        if zero && (started || zero_lead) {
            out.push(digits[0]);
        }
        zero = false;
        started = true;
        out.push(digits[x as usize]);
        out.push_str(units[i]);
    }
    out
}

/// 整数的中文读法。 Chinese reading of a non-negative integer.
fn integer(n: u64, digits: &[char; 10], units: [&str; 4], big: [&str; 4]) -> String {
    if n == 0 {
        return digits[0].to_string();
    }
    let mut groups = Vec::new();
    let mut m = n;
    while m > 0 {
        groups.push((m % 10_000) as u32);
        m /= 10_000;
    }
    let mut out = String::new();
    let mut need_zero = false;
    for (i, &g) in groups.iter().enumerate().rev() {
        if g == 0 {
            need_zero = !out.is_empty();
            continue;
        }
        let lead = !out.is_empty() && (need_zero || g < 1000);
        out.push_str(&group(g, digits, units, lead));
        out.push_str(big[i]);
        need_zero = false;
    }
    out
}

/// 中文小写数字（一千二百三十四；十几读作「十几」）。 Chinese numerals, lowercase.
pub fn chinese_number(n: u64) -> String {
    let s = integer(n, &DIGITS_LOWER, ["千", "百", "十", ""], ["", "万", "亿", "万亿"]);
    // 「一十」开头读作「十」。 A leading 一十 reads as 十.
    s.strip_prefix("一十").map(|r| format!("十{r}")).unwrap_or(s)
}

/// 大写金额（壹仟贰佰叁拾肆元伍角陆分 / 元整）；最多两位小数、不超过万亿。
/// Uppercase financial amount; at most two decimals, below 10^16.
pub fn money_upper(s: &str) -> Option<String> {
    let (int, frac) = s.split_once('.').unwrap_or((s, ""));
    if int.is_empty() || !int.bytes().all(|b| b.is_ascii_digit()) || !frac.bytes().all(|b| b.is_ascii_digit()) || frac.len() > 2 || int.len() > 16 {
        return None;
    }
    let n: u64 = int.parse().ok()?;
    let jiao = frac.as_bytes().first().map(|b| b - b'0').unwrap_or(0);
    let fen = frac.as_bytes().get(1).map(|b| b - b'0').unwrap_or(0);
    let mut out = String::new();
    if n > 0 {
        out.push_str(&integer(n, &DIGITS_UPPER, ["仟", "佰", "拾", ""], ["", "万", "亿", "万亿"]));
        out.push('元');
    }
    if jiao == 0 && fen == 0 {
        if n == 0 {
            return Some("零元整".into());
        }
        out.push('整');
        return Some(out);
    }
    if jiao > 0 {
        out.push(DIGITS_UPPER[jiao as usize]);
        out.push('角');
    } else if n > 0 {
        out.push('零');
    }
    if fen > 0 {
        out.push(DIGITS_UPPER[fen as usize]);
        out.push('分');
    }
    Some(out)
}

/// 千分位。 Thousands separators.
pub fn thousands(s: &str) -> String {
    let (sign, rest) = s.strip_prefix('-').map(|r| ("-", r)).unwrap_or(("", s));
    let (int, frac) = rest.split_once('.').map(|(a, b)| (a, Some(b))).unwrap_or((rest, None));
    let mut out = String::new();
    for (i, c) in int.chars().enumerate() {
        if i > 0 && (int.len() - i) % 3 == 0 {
            out.push(',');
        }
        out.push(c);
    }
    match frac {
        Some(f) => format!("{sign}{out}.{f}"),
        None => format!("{sign}{out}"),
    }
}

/// `v` 之后的内容能否继续输入这个字符。 Whether `c` may extend the text after `v`.
pub fn v_accepts(c: char) -> bool {
    c.is_ascii_digit() || ".+-*/()%^".contains(c)
}

/// `v` 模式的候选：(文字, 注释)。 Candidates of the `v` mode: (text, comment).
pub fn v_candidates(body: &str) -> Vec<(String, String)> {
    let mut out: Vec<(String, String)> = Vec::new();
    if is_expression(body) {
        if let Some(v) = eval(body.trim_end_matches('=')) {
            let r = format_number(v);
            out.push((r.clone(), "计算结果".into()));
            out.push((format!("{body}={r}"), "算式".into()));
            if let Some(m) = money_upper(&r) {
                out.push((m, "大写金额".into()));
            }
        }
        return out;
    }
    if body.is_empty() || !body.bytes().all(|b| b.is_ascii_digit() || b == b'.') || body.matches('.').count() > 1 {
        return out;
    }
    out.push((body.to_string(), String::new()));
    if let Some(m) = money_upper(body) {
        out.push((m, "大写金额".into()));
    }
    let int = body.split('.').next().unwrap_or("");
    if !body.contains('.') && int.len() <= 16 {
        if let Ok(n) = int.parse::<u64>() {
            out.push((chinese_number(n), "中文数字".into()));
            if int.len() > 3 {
                out.push((thousands(int), "千分位".into()));
            }
        }
    }
    out
}

/// 公历日期（自 1970-01-01 起的天数）。 Civil date from days since 1970-01-01.
pub fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    (y, m, d)
}

const WEEKDAYS: [&str; 7] = ["星期四", "星期五", "星期六", "星期日", "星期一", "星期二", "星期三"];

/// 拼音缩写对应的日期时间候选；`now` 为 Unix 秒，`offset_min` 为本地时区相对 UTC 的分钟数。
/// Date/time candidates for a pinyin abbreviation; `now` in Unix seconds, `offset_min` the local UTC offset.
pub fn date_candidates(key: &str, now: i64, offset_min: i32) -> Vec<(String, String)> {
    let local = now + offset_min as i64 * 60;
    let days = local.div_euclid(86_400);
    let secs = local.rem_euclid(86_400);
    let (y, m, d) = civil(days);
    let (h, mi) = (secs / 3600, secs / 60 % 60);
    let wd = WEEKDAYS[days.rem_euclid(7) as usize];
    let c = |t: String, c: &str| (t, c.to_string());
    match key {
        "rq" => vec![
            c(format!("{y}年{m}月{d}日"), "日期"),
            c(format!("{y}-{m:02}-{d:02}"), "日期"),
            c(format!("{y}年{m}月{d}日 {wd}"), "日期"),
        ],
        "sj" => vec![
            c(format!("{h:02}:{mi:02}"), "时间"),
            c(format!("{y}-{m:02}-{d:02} {h:02}:{mi:02}"), "时间"),
            c(format!("{h}点{mi:02}分"), "时间"),
        ],
        "xq" => vec![c(wd.to_string(), "星期")],
        _ => Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arithmetic() {
        assert_eq!(eval("(128+32)*4"), Some(640.0));
        assert_eq!(eval("2^3^2"), Some(512.0));
        assert_eq!(eval("-3+10/4"), Some(-0.5));
        assert_eq!(eval("7%3"), Some(1.0));
        assert_eq!(eval("12×3÷4"), Some(9.0));
        assert_eq!(eval("1/0"), None);
        assert_eq!(eval("1+"), None);
        assert_eq!(eval("(1"), None);
        assert_eq!(eval("1.2.3"), None);
        assert_eq!(format_number(0.1 + 0.2), "0.3");
        assert_eq!(format_number(2.5), "2.5");
        assert_eq!(format_number(-4.0), "-4");
        assert!(is_expression("128*4"));
        assert!(!is_expression("-128"));
        assert!(!is_expression("abc+1"));
    }

    #[test]
    fn numerals() {
        assert_eq!(money_upper("1234").unwrap(), "壹仟贰佰叁拾肆元整");
        assert_eq!(money_upper("1004.5").unwrap(), "壹仟零肆元伍角");
        assert_eq!(money_upper("100000.05").unwrap(), "壹拾万元零伍分");
        assert_eq!(money_upper("0.3").unwrap(), "叁角");
        assert_eq!(money_upper("0").unwrap(), "零元整");
        assert_eq!(money_upper("120000301").unwrap(), "壹亿贰仟万零叁佰零壹元整");
        assert_eq!(money_upper("1.234"), None);
        assert_eq!(chinese_number(1234), "一千二百三十四");
        assert_eq!(chinese_number(15), "十五");
        assert_eq!(chinese_number(105), "一百零五");
        assert_eq!(chinese_number(10_010), "一万零一十");
        assert_eq!(chinese_number(20_000_000), "二千万");
        assert_eq!(thousands("1234567"), "1,234,567");
        assert_eq!(thousands("-1234.5"), "-1,234.5");
        let v = v_candidates("1234");
        assert_eq!(v[0].0, "1234");
        assert_eq!(v[1].0, "壹仟贰佰叁拾肆元整");
        assert_eq!(v_candidates("(1+2)*3")[0].0, "9");
        assert!(v_candidates("1+").is_empty());
    }

    #[test]
    fn dates() {
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(civil(20_723), (2026, 9, 27));
        // 2026-09-27 03:06 UTC = 11:06 北京时间，星期日。 11:06 in UTC+8, a Sunday.
        let now = 20_723 * 86_400 + 3 * 3600 + 6 * 60;
        assert_eq!(date_candidates("rq", now, 480)[0].0, "2026年9月27日");
        assert_eq!(date_candidates("sj", now, 480)[0].0, "11:06");
        assert_eq!(date_candidates("xq", now, 480)[0].0, "星期日");
        assert!(date_candidates("ni", now, 480).is_empty());
        // 跨日：UTC 前一天 23:30 在东八区已是次日。 Crossing midnight in UTC+8.
        assert_eq!(date_candidates("rq", 20_722 * 86_400 + 23 * 3600 + 1800, 480)[1].0, "2026-09-27");
    }
}
