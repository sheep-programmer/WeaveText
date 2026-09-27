#!/usr/bin/env python3
"""
织文图标唯一来源 / Single source of truth for WeaveText line icons.

生成 / Generates:
  1. docs/design/icons/drawable/ic_*.xml   Android VectorDrawable（24dp，1.75dp 描边，圆角端点）
  2. docs/design/04-components-spec.md     <!-- ICON-TABLE --> 标记之间的图标表
  3. docs/design/preview/index.html        <!-- ICON-SPRITE --> 标记之间的 SVG sprite

用法 / Usage:  python3 docs/design/icons/build_icons.py
"""
from pathlib import Path

STROKE = 1.75
ROOT = Path(__file__).resolve().parent.parent  # docs/design


def rrect(x, y, w, h, r):
    """圆角矩形路径 / rounded-rect path (absolute start, relative segments)."""
    return (f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0 0,1 {r:g},{r:g}"
            f"v{h - 2 * r:g}a{r:g},{r:g} 0 0,1 {-r:g},{r:g}"
            f"h{-(w - 2 * r):g}a{r:g},{r:g} 0 0,1 {-r:g},{-r:g}"
            f"v{-(h - 2 * r):g}a{r:g},{r:g} 0 0,1 {r:g},{-r:g}z")


def circle(cx, cy, r):
    return f"M{cx:g},{cy - r:g}a{r:g},{r:g} 0 1,1 0,{2 * r:g}a{r:g},{r:g} 0 1,1 0,{-2 * r:g}z"


def band(points, w):
    """把折线加粗成闭合多边形（斜接），用于面性图标的镂空 / thicken a polyline into a mitred polygon (cut-outs)."""
    import math
    h = w / 2
    n = len(points)
    left, right = [], []
    for i, (x, y) in enumerate(points):
        dirs = []
        if i > 0:
            dirs.append((x - points[i - 1][0], y - points[i - 1][1]))
        if i < n - 1:
            dirs.append((points[i + 1][0] - x, points[i + 1][1] - y))
        normals = []
        for dx, dy in dirs:
            ln = math.hypot(dx, dy)
            normals.append((-dy / ln, dx / ln))
        nx = sum(a for a, _ in normals)
        ny = sum(b for _, b in normals)
        ln = math.hypot(nx, ny)
        nx, ny = nx / ln, ny / ln
        scale = h / max(0.3, nx * normals[0][0] + ny * normals[0][1])
        left.append((x + nx * scale, y + ny * scale))
        right.append((x - nx * scale, y - ny * scale))
    pts = left + right[::-1]
    return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "z"


# (name, 中文用途, Material Symbols Rounded 替代名, [(pathData, filled)])
# filled：False 描边；True 填充 + 描边；"solid" 仅填充（evenOdd，内部子路径为镂空）。
# filled: False stroke; True fill + stroke; "solid" fill only (evenOdd, inner sub-paths are cut-outs).
ICONS = [
    ("ic_logo", "工具箱入口 / 织文标", "—（品牌 brand）",
     [(circle(12, 12, 9) + " M7.5,9.5l2.25,5.5l2.25,-4.5l2.25,4.5l2.25,-5.5", False)]),
    ("ic_keyboard", "键盘切换 / 输入方案", "keyboard",
     [(rrect(3, 5.5, 18, 13, 3) + " M7.5,9.5h0.01 M10.5,9.5h0.01 M13.5,9.5h0.01 M16.5,9.5h0.01 M9,14.5h6", False)]),
    ("ic_mic", "语音", "mic",
     [("M12,3a3,3 0 0,1 3,3v5a3,3 0 0,1 -6,0v-5a3,3 0 0,1 3,-3z M5.5,11a6.5,6.5 0 0,0 13,0 M12,17.5v3.5", False)]),
    ("ic_cursor", "光标编辑", "text_select_move_forward_character",
     [("M12,5v14 M9.5,5h5 M9.5,19h5 M6.5,9l-3,3l3,3 M17.5,9l3,3l-3,3", False)]),
    ("ic_clipboard", "剪贴板 / 粘贴", "content_paste",
     [("M9,4.5h-1a2.5,2.5 0 0,0 -2.5,2.5v11.5a2.5,2.5 0 0,0 2.5,2.5h9a2.5,2.5 0 0,0 2.5,-2.5v-11.5"
       "a2.5,2.5 0 0,0 -2.5,-2.5h-1 " + rrect(9, 3, 6, 3, 1) + " M9,11h6 M9,15h4", False)]),
    ("ic_chevron_down", "收起键盘 / 展开候选 / 下拉", "keyboard_arrow_down", [("M6,9l6,6l6,-6", False)]),
    ("ic_chevron_up", "收起候选 / 光标上", "keyboard_arrow_up", [("M6,15l6,-6l6,6", False)]),
    ("ic_chevron_left", "面板返回 / 光标左", "chevron_left", [("M15,6l-6,6l6,6", False)]),
    ("ic_chevron_right", "列表进入 / 光标右", "chevron_right", [("M9,6l6,6l-6,6", False)]),
    ("ic_backspace", "删除", "backspace",
     [("M8.5,5h10a2.5,2.5 0 0,1 2.5,2.5v9a2.5,2.5 0 0,1 -2.5,2.5h-10l-6,-7z M12,9.5l5,5 M17,9.5l-5,5", False)]),
    ("ic_enter", "回车（无动作）", "keyboard_return",
     [("M19,5v6a3,3 0 0,1 -3,3h-11 M8.5,10.5l-3.5,3.5l3.5,3.5", False)]),
    ("ic_shift", "Shift 关", "shift", [("M12,3.5l8,8.5h-4.5v7.5h-7v-7.5h-4.5z", False)]),
    ("ic_shift_filled", "Shift 单次", "shift（FILL=1）", [("M12,3.5l8,8.5h-4.5v7.5h-7v-7.5h-4.5z", True)]),
    ("ic_shift_lock", "Shift 锁定", "shift_lock（FILL=1）",
     [("M12,3l7.5,7.5h-4v5h-7v-5h-4z", True), ("M8.5,20h7", False)]),
    ("ic_space", "空格键符号", "space_bar",
     [("M5,10.5v3a1.5,1.5 0 0,0 1.5,1.5h11a1.5,1.5 0 0,0 1.5,-1.5v-3", False)]),
    ("ic_search", "搜索（回车动作）/ 用户词搜索", "search",
     [(circle(10.5, 10.5, 6.5) + " M15.3,15.3l4.7,4.7", False)]),
    ("ic_emoji", "表情", "sentiment_satisfied",
     [(circle(12, 12, 9) + " M9,10h0.01 M15,10h0.01 M8.5,14.5a4,4 0 0,0 7,0", False)]),
    ("ic_settings", "设置（六边形，呼应参考图）", "settings",
     [("M12,2.8l8,4.6v9.2l-8,4.6l-8,-4.6v-9.2z " + circle(12, 12, 3), False)]),
    ("ic_moon", "深色模式", "dark_mode",
     [("M20,14.5a8,8 0 1,1 -10.5,-10.5a7.5,7.5 0 0,0 10.5,10.5z", False)]),
    ("ic_theme", "外观（半填充圆）", "contrast",
     [(circle(12, 12, 9), False), ("M12,3a9,9 0 0,1 0,18z", True)]),
    ("ic_resize", "键盘调节", "open_in_full",
     [("M8,4h-2a2,2 0 0,0 -2,2v2 M16,4h2a2,2 0 0,1 2,2v2 M8,20h-2a2,2 0 0,1 -2,-2v-2 "
       "M16,20h2a2,2 0 0,0 2,-2v-2 M9,15l6,-6 M11,9h4v4 M13,15h-4v-4", False)]),
    ("ic_quick_phrase", "常用语", "chat",
     [("M6,4.5h12a2.5,2.5 0 0,1 2.5,2.5v8a2.5,2.5 0 0,1 -2.5,2.5h-6l-4,3v-3h-2a2.5,2.5 0 0,1 -2.5,-2.5v-8"
       "a2.5,2.5 0 0,1 2.5,-2.5z M8,9.5h8 M8,13h5", False)]),
    ("ic_one_hand", "单手模式", "splitscreen_left",
     [(rrect(6, 3, 12, 18, 2) + " M6,14.5h8.5v6.5", False)]),
    ("ic_float", "悬浮键盘", "picture_in_picture",
     [(rrect(3, 4, 18, 16, 2.5) + " " + rrect(7, 10.5, 10, 6.5, 1.5) + " M10.5,8h3", False)]),
    ("ic_waveform", "语音引擎 / 波形", "graphic_eq",
     [("M4,10v4 M8,7v10 M12,4v16 M16,7v10 M20,10v4", False)]),
    ("ic_toolbox", "工具箱（备用）", "apps",
     [(" ".join(rrect(x, y, 6, 6, 1.5) for y in (4, 14) for x in (4, 14)), False)]),
    ("ic_tab", "Tab", "keyboard_tab",
     [("M3.5,12h12 M11.5,8l4,4l-4,4 M20.5,6v12", False)]),
    ("ic_copy", "复制", "content_copy",
     [(rrect(7.5, 8.5, 12, 12, 2) + " M15.5,8.5v-2a2,2 0 0,0 -2,-2h-7a2,2 0 0,0 -2,2v7a2,2 0 0,0 2,2h1", False)]),
    ("ic_cut", "剪切", "content_cut",
     [(circle(6.5, 17.5, 2.5) + " " + circle(17.5, 17.5, 2.5) + " M8,15.5l8.5,-12 M16,15.5l-8.5,-12", False)]),
    ("ic_select_all", "全选", "select_all",
     [("M4,8v-2a2,2 0 0,1 2,-2h2 M11,4h2 M16,4h2a2,2 0 0,1 2,2v2 M20,11v2 M20,16v2a2,2 0 0,1 -2,2h-2 "
       "M13,20h-2 M8,20h-2a2,2 0 0,1 -2,-2v-2 M4,13v-2 M9.5,9.5h5v5h-5z", False)]),
    ("ic_pin", "固定", "keep",
     [("M9,3.5h6 M10,3.5v5.5l-3,3.5v1.5h10v-1.5l-3,-3.5v-5.5 M12,14v6.5", False)]),
    ("ic_delete", "删除（垃圾桶）/ 清空", "delete",
     [("M4,6.5h16 M9.5,3.5h5 M6,6.5l0.9,12a2,2 0 0,0 2,1.85h6.2a2,2 0 0,0 2,-1.85l0.9,-12 M10,10.5v6 M14,10.5v6", False)]),
    ("ic_edit", "编辑", "edit",
     [("M4,20v-3l11.25,-11.25a2.12,2.12 0 0,1 3,3l-11.25,11.25z M13.5,7.5l3,3", False)]),
    ("ic_undo", "撤销", "undo",
     [("M9,14l-5,-5l5,-5 M4,9h10.5a5.5,5.5 0 0,1 0,11h-3.5", False)]),
    ("ic_close", "关闭", "close", [("M6,6l12,12 M18,6l-12,12", False)]),
    ("ic_check", "完成 / 已选", "check", [("M5,12.5l4.5,4.5l9.5,-10", False)]),
    ("ic_plus", "添加", "add", [("M12,5v14 M5,12h14", False)]),
    ("ic_import", "导入文件 / .xipk", "file_open",
     [("M13.5,3.5h-6.5a2,2 0 0,0 -2,2v13a2,2 0 0,0 2,2h10a2,2 0 0,0 2,-2v-9.5z M13.5,3.5v4a2,2 0 0,0 2,2h3.5 "
       "M12,11.5v6 M9.5,15l2.5,2.5l2.5,-2.5", False)]),
    ("ic_export", "导出", "file_export",
     [("M13.5,3.5h-6.5a2,2 0 0,0 -2,2v13a2,2 0 0,0 2,2h10a2,2 0 0,0 2,-2v-9.5z M13.5,3.5v4a2,2 0 0,0 2,2h3.5 "
       "M12,17.5v-6 M9.5,14l2.5,-2.5l2.5,2.5", False)]),
    ("ic_book", "词库", "menu_book",
     [("M6.5,3.5h12v14h-12a1.5,1.5 0 0,0 -1.5,1.5v-14a1.5,1.5 0 0,1 1.5,-1.5z M5,19a1.5,1.5 0 0,0 1.5,1.5h12v-3 M9,7.5h6", False)]),
    ("ic_info", "关于 / 帮助", "info",
     [(circle(12, 12, 9) + " M12,11v5.5 M12,7.75h0.01", False)]),
    ("ic_warning", "警告横幅", "warning",
     [("M12,4l9,15.5h-18z M12,10v4 M12,16.75h0.01", False)]),
    ("ic_volume", "按键音", "volume_up",
     [("M4,9.5h3l4.5,-4v13l-4.5,-4h-3z M15.5,9a4,4 0 0,1 0,6 M18,6.5a7.5,7.5 0 0,1 0,11", False)]),
    ("ic_vibration", "振动", "vibration",
     [(rrect(7.5, 4, 9, 16, 1.5) + " M4.5,9v6 M19.5,9v6 M2,11v2 M22,11v2", False)]),
    ("ic_lock", "符号面板锁定", "lock",
     [(rrect(5.5, 10.5, 13, 10, 1.5) + " M8.5,10.5v-3a3.5,3.5 0 0,1 7,0v3 M12,14.5v2", False)]),
    ("ic_lock_open", "符号面板解锁", "lock_open_right",
     [(rrect(5.5, 10.5, 13, 10, 1.5) + " M8.5,10.5v-3a3.5,3.5 0 0,1 6.8,-1.2 M12,14.5v2", False)]),
    ("ic_arrow_back", "设置页返回", "arrow_back", [("M19,12h-14 M11,6l-6,6l6,6", False)]),
    ("ic_open_external", "外部链接", "open_in_new",
     [("M14,4h6v6 M20,4l-9,9 M18,14v4a2,2 0 0,1 -2,2h-10a2,2 0 0,1 -2,-2v-10a2,2 0 0,1 2,-2h4", False)]),
    ("ic_drag_handle", "拖拽排序", "drag_handle", [("M5,9h14 M5,15h14", False)]),
    ("ic_stop", "语音收音中（停止）", "stop（FILL=1）", [(rrect(7, 7, 10, 10, 2), True)]),
    ("ic_globe", "中英 / 语言键（地球）", "language",
     [(circle(12, 12, 9) + " M12,3a5,9 0 0,1 0,18a5,9 0 0,1 0,-18z M3,12h18", False)]),
    # 面性变体（键盘风格「面性工具栏」）/ Filled variants for styles with a filled toolbar
    ("ic_logo_filled", "工具箱入口（面性）", "—（品牌 brand）",
     [(circle(12, 12, 9.9) + " " + band([(7.3, 9.3), (9.75, 15.2), (12, 10.7), (14.25, 15.2), (16.7, 9.3)], 1.9), "solid")]),
    ("ic_keyboard_filled", "键盘切换（面性）", "keyboard（FILL=1）",
     [(rrect(2.2, 4.7, 19.6, 14.6, 3.6) + " " + " ".join(rrect(x - 0.95, 8.55, 1.9, 1.9, 0.5) for x in (7.5, 10.5, 13.5, 16.5))
       + " " + rrect(8.05, 13.55, 7.9, 1.9, 0.95), "solid")]),
    ("ic_mic_filled", "语音（面性）", "mic（FILL=1）",
     [(rrect(8.6, 2.6, 6.8, 11.8, 3.4), "solid"), ("M5.5,11a6.5,6.5 0 0,0 13,0 M12,17.5v3.5", False)]),
    ("ic_cursor_filled", "光标编辑（面性）", "text_select_move_forward_character（FILL=1）",
     [(rrect(2.4, 3.4, 19.2, 17.2, 4.5) + " " + rrect(9.2, 6.2, 5.6, 1.8, 0.9) + " M11.1,8h1.8v8h-1.8z "
       + rrect(9.2, 16, 5.6, 1.8, 0.9) + " M7.4,9.3l-2.7,2.7l2.7,2.7z M16.6,9.3l2.7,2.7l-2.7,2.7z", "solid")]),
    ("ic_clipboard_filled", "剪贴板（面性）", "content_paste（FILL=1）",
     [(rrect(4.6, 3.6, 14.8, 17.8, 2.8) + " " + rrect(8.05, 10.1, 7.9, 1.8, 0.9) + " " + rrect(8.05, 14.1, 5.9, 1.8, 0.9), "solid"),
      (rrect(8.4, 1.8, 7.2, 3.8, 1.4), "solid")]),
    ("ic_chevron_down_filled", "收起键盘（面性）", "arrow_drop_down", [("M5.2,8.6h13.6l-6.8,7.8z", "solid")]),
    ("ic_emoji_filled", "表情（面性）", "sentiment_satisfied（FILL=1）",
     [(circle(12, 12, 9.9) + " " + circle(9, 10, 1.3) + " " + circle(15, 10, 1.3) + " M8,13.6h8a4,4 0 0,1 -8,0z", "solid")]),
    ("ic_settings_filled", "设置（面性）", "settings（FILL=1）",
     [("M12,1.8l8.9,5.1v10.2l-8.9,5.1l-8.9,-5.1v-10.2z " + circle(12, 12, 3.2), "solid")]),
    ("ic_devices", "织文互联 / 电脑与手机", "devices",
     [(rrect(2.5, 5, 13.5, 9.5, 1.5) + " M1.5,18h11 " + rrect(16.5, 8.5, 5.5, 11, 1.25) + " M19.25,17h0.01", False)]),
    ("ic_send", "发送到电脑", "send",
     [("M4.5,11.5l15,-7l-5.5,15l-2.75,-5.75z M11.25,13.75l8.25,-9.25", False)]),
]


def vector_xml(parts):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- 由 build_icons.py 生成，勿手改 / generated, do not edit -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="24dp" android:height="24dp"',
             '    android:viewportWidth="24" android:viewportHeight="24">']
    for d, filled in parts:
        lines.append("  <path")
        if filled == "solid":
            lines += ['      android:fillColor="#FF000000"',
                      '      android:fillType="evenOdd"',
                      f'      android:pathData="{d}" />']
            continue
        if filled:
            lines.append('      android:fillColor="#FF000000"')
        lines += ['      android:strokeColor="#FF000000"',
                  f'      android:strokeWidth="{STROKE}"',
                  '      android:strokeLineCap="round"',
                  '      android:strokeLineJoin="round"',
                  f'      android:pathData="{d}" />']
    lines.append("</vector>\n")
    return "\n".join(lines)


def svg_symbol(name, parts):
    body = "".join(
        f'<path d="{d}" fill="currentColor" fill-rule="evenodd"/>' if f == "solid" else
        f'<path d="{d}" fill="{"currentColor" if f else "none"}" stroke="currentColor" '
        f'stroke-width="{STROKE}" stroke-linecap="round" stroke-linejoin="round"/>' for d, f in parts)
    return f'<symbol id="{name}" viewBox="0 0 24 24">{body}</symbol>'


def replace_between(path: Path, tag: str, content: str):
    text = path.read_text(encoding="utf-8")
    begin, end = f"<!-- {tag}:BEGIN -->", f"<!-- {tag}:END -->"
    a, b = text.index(begin) + len(begin), text.index(end)
    path.write_text(text[:a] + "\n" + content + "\n" + text[b:], encoding="utf-8")


def main():
    out = ROOT / "icons" / "drawable"
    out.mkdir(parents=True, exist_ok=True)
    for old in out.glob("ic_*.xml"):
        old.unlink()
    rows = ["| 名称 Name | 用途 Usage | Material Symbols Rounded | pathData（viewport 24，`fill` 标注实心子路径） |",
            "|---|---|---|---|"]
    for name, usage, ms, parts in ICONS:
        (out / f"{name}.xml").write_text(vector_xml(parts), encoding="utf-8")
        pd = "<br>".join(("**solid** " if f == "solid" else "**fill** " if f else "") + f"`{d}`" for d, f in parts)
        rows.append(f"| `{name}` | {usage} | `{ms}` | {pd} |")
    replace_between(ROOT / "04-components-spec.md", "ICON-TABLE", "\n".join(rows))
    sprite = ('<svg xmlns="http://www.w3.org/2000/svg" style="display:none">'
              + "".join(svg_symbol(n, p) for n, _, _, p in ICONS) + "</svg>")
    replace_between(ROOT / "preview" / "index.html", "ICON-SPRITE", sprite)
    sheet = "".join(f'<div class="ic-cell"><svg><use href="#{n}"/></svg><code>{n}</code></div>'
                    for n, *_ in ICONS)
    replace_between(ROOT / "preview" / "index.html", "ICON-SHEET", sheet)
    print(f"{len(ICONS)} icons written")


if __name__ == "__main__":
    main()
