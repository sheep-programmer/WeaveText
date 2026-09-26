//! Lua 值 ↔ JSON。规则（与插件的预期一致）：
//! - 表的键恰好是 1..n（n ≥ 1）→ 数组；其余（含空表 `{}`）→ 对象。
//!   空表必须编码成 `{}`（服务端常要求空对象而非空数组）。
//! - 整数 → 整数；整数值的浮点（|x| < 2^53）也输出成整数；NaN/Inf → null。
//! - 解码时 null → nil（对象里等于没有这个键）；整数保持为 Lua 整数，便于 `code == 0`。
//! - 非法 JSON / 不可编码的值（函数、循环引用）→ 返回 nil 与错误信息，不抛 Lua 错误。
//!
//! Lua ↔ JSON rules: tables keyed exactly 1..n become arrays, everything else (including the
//! empty table) becomes an object; integral floats print as integers; JSON null decodes to nil;
//! bad input returns `nil, err` instead of raising.

use mlua::{Lua, Table, Value as LuaValue};
use serde_json::{Map, Number, Value};

const MAX_DEPTH: usize = 128;

pub fn lua_to_json(v: &LuaValue, depth: usize) -> Result<Value, String> {
    if depth > MAX_DEPTH {
        return Err("json.encode: nesting too deep (cycle?)".into());
    }
    Ok(match v {
        LuaValue::Nil => Value::Null,
        LuaValue::Boolean(b) => Value::Bool(*b),
        LuaValue::Integer(i) => Value::from(*i),
        LuaValue::Number(n) => float_to_json(*n),
        LuaValue::String(s) => Value::String(String::from_utf8_lossy(&s.as_bytes()).into_owned()),
        LuaValue::Table(t) => table_to_json(t, depth)?,
        LuaValue::LightUserData(p) if p.0.is_null() => Value::Null,
        other => return Err(format!("json.encode: cannot encode {}", other.type_name())),
    })
}

fn float_to_json(n: f64) -> Value {
    if n.is_finite() && n.fract() == 0.0 && n.abs() < 9_007_199_254_740_992.0 {
        Value::from(n as i64)
    } else {
        Number::from_f64(n)
            .map(Value::Number)
            .unwrap_or(Value::Null)
    }
}

fn table_to_json(t: &Table, depth: usize) -> Result<Value, String> {
    let n = t.raw_len();
    let mut count = 0usize;
    let mut all_int = true;
    for pair in t.clone().pairs::<LuaValue, LuaValue>() {
        let (k, _) = pair.map_err(|e| e.to_string())?;
        count += 1;
        match k {
            LuaValue::Integer(i) if i >= 1 && (i as usize) <= n => {}
            _ => all_int = false,
        }
    }
    if n > 0 && all_int && count == n {
        let mut arr = Vec::with_capacity(n);
        for i in 1..=n {
            let v: LuaValue = t.raw_get(i).map_err(|e| e.to_string())?;
            arr.push(lua_to_json(&v, depth + 1)?);
        }
        return Ok(Value::Array(arr));
    }
    let mut m = Map::new();
    for pair in t.clone().pairs::<LuaValue, LuaValue>() {
        let (k, v) = pair.map_err(|e| e.to_string())?;
        let key = match &k {
            LuaValue::String(s) => String::from_utf8_lossy(&s.as_bytes()).into_owned(),
            LuaValue::Integer(i) => i.to_string(),
            LuaValue::Number(f) => float_to_json(*f).to_string(),
            LuaValue::Boolean(b) => b.to_string(),
            other => {
                return Err(format!(
                    "json.encode: unsupported key type {}",
                    other.type_name()
                ))
            }
        };
        m.insert(key, lua_to_json(&v, depth + 1)?);
    }
    Ok(Value::Object(m))
}

pub fn json_to_lua(lua: &Lua, v: &Value) -> mlua::Result<LuaValue> {
    Ok(match v {
        Value::Null => LuaValue::Nil,
        Value::Bool(b) => LuaValue::Boolean(*b),
        Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                LuaValue::Integer(i)
            } else {
                LuaValue::Number(n.as_f64().unwrap_or(0.0))
            }
        }
        Value::String(s) => LuaValue::String(lua.create_string(s)?),
        Value::Array(a) => {
            let t = lua.create_table_with_capacity(a.len(), 0)?;
            for (i, item) in a.iter().enumerate() {
                t.raw_set(i + 1, json_to_lua(lua, item)?)?;
            }
            LuaValue::Table(t)
        }
        Value::Object(m) => {
            let t = lua.create_table_with_capacity(0, m.len())?;
            for (k, item) in m {
                t.raw_set(k.as_str(), json_to_lua(lua, item)?)?;
            }
            LuaValue::Table(t)
        }
    })
}

pub fn encode(v: &LuaValue) -> Result<String, String> {
    lua_to_json(v, 0).map(|j| j.to_string())
}

pub fn decode(lua: &Lua, bytes: &[u8]) -> Result<LuaValue, String> {
    let v: Value = serde_json::from_slice(bytes).map_err(|e| format!("json.decode: {e}"))?;
    json_to_lua(lua, &v).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn roundtrip(lua: &Lua, src: &str) -> String {
        let v: LuaValue = lua.load(src).eval().unwrap();
        encode(&v).unwrap()
    }

    #[test]
    fn arrays_objects_null() {
        let lua = Lua::new();
        assert_eq!(roundtrip(&lua, "{}"), "{}");
        assert_eq!(roundtrip(&lua, "{1,2,3}"), "[1,2,3]");
        assert_eq!(
            roundtrip(&lua, "{extra = {}, retry = {0}}"),
            r#"{"extra":{},"retry":[0]}"#
        );
        assert_eq!(roundtrip(&lua, "{[1]='a',[3]='c'}"), r#"{"1":"a","3":"c"}"#);
        assert_eq!(roundtrip(&lua, "{1, x=2}"), r#"{"1":1,"x":2}"#);
        assert_eq!(
            roundtrip(&lua, "{t = 3.0, f = 1.5, b = true}"),
            r#"{"b":true,"f":1.5,"t":3}"#
        );
        assert_eq!(roundtrip(&lua, "'中\"文'"), r#""中\"文""#);
        let bad: LuaValue = lua.load("{f = print}").eval().unwrap();
        assert!(encode(&bad).is_err());
        let cyc: LuaValue = lua.load("local t = {} t.t = t return t").eval().unwrap();
        assert!(encode(&cyc).is_err());
    }

    #[test]
    fn decode_types() {
        let lua = Lua::new();
        let v = decode(
            &lua,
            br#"{"code":0,"msg":null,"arr":[1,"x",{"k":true}],"f":1.5,"big":12345678901234}"#,
        )
        .unwrap();
        let t = v.as_table().unwrap();
        assert!(matches!(
            t.get::<LuaValue>("code").unwrap(),
            LuaValue::Integer(0)
        ));
        assert!(t.get::<LuaValue>("msg").unwrap().is_nil());
        let arr: Table = t.get("arr").unwrap();
        assert_eq!(arr.raw_len(), 3);
        assert_eq!(arr.get::<String>(2).unwrap(), "x");
        assert_eq!(t.get::<f64>("f").unwrap(), 1.5);
        assert_eq!(t.get::<i64>("big").unwrap(), 12345678901234);
        assert!(decode(&lua, b"[DONE]").is_err());
        assert!(decode(&lua, b"\x08\x01").is_err());
        // 往返
        let again = encode(&v).unwrap();
        let v2 = decode(&lua, again.as_bytes()).unwrap();
        assert_eq!(encode(&v2).unwrap(), again);
    }
}
