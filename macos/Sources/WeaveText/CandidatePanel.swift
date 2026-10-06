import AppKit
import SwiftUI
import WeaveCore

/// 候选窗要画的内容。 What the candidate window draws.
struct CandidateState: Equatable {
    var preedit: String
    var candidates: [Candidate]
    var highlight: Int
    var hasPrevious: Bool
    var hasNext: Bool
    var orientation: CandidateOrientation
    var fontSize: CGFloat
    /// 没有组合串时顶行的小字提示（联想词）。 A small top-line hint when there is no preedit (predictions).
    var hint: String = ""
    /// 候选比一页多：显示下拉按钮。 More candidates than a page: show the dropdown button.
    var expandable = false
    /// 展开后的候选网格（nil = 没展开）。 The expanded grid's candidates (nil = collapsed).
    var expanded: [Candidate]? = nil
    var expandedMore = false
}

/// 输入法的交互窗只能接收鼠标，不能夺走文档的键盘焦点。
/// Interactive IME panels receive mouse events without taking the document's keyboard focus.
final class InputPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

/// 跟随光标的候选窗：无边框、不抢焦点，整个进程复用一个。
/// The caret-following candidate window: borderless, never takes focus, one per process.
final class CandidatePanel {
    static let shared = CandidatePanel()

    private let panel: NSPanel
    private let hosting: ClickThroughHostingView<CandidateBar>
    private let effect = NSVisualEffectView()
    private weak var owner: WeaveInputController?
    private var lastCaret = NSRect.zero
    static let cornerRadius: CGFloat = 12
    private var themeObserver:NSObjectProtocol?

    private init() {
        panel = InputPanel(contentRect: NSRect(x: 0, y: 0, width: 200, height: 40),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.level = .popUpMenu
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.isReleasedWhenClosed = false
        // 候选窗要能被文档前的点击够到，所以不能带 .transient：transient 会被 WindowServer 从跨进程窗口列表与
        // 命中测试里剔除，于是点不上候选也点不到展开键。语音面板不带它没事，因为它原本就要拿到自己的焦点。
        // The candidate window must stay in the system-wide window list so the front app can forward clicks; a
        // .transient window is dropped from that list and from cross-process hit-testing, making it unclickable.
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]

        effect.material = .popover
        effect.state = .active
        effect.blendingMode = .behindWindow
        effect.wantsLayer = true
        effect.layer?.cornerRadius = Self.cornerRadius
        effect.layer?.masksToBounds = true
        effect.maskImage = Self.roundedMask(radius: Self.cornerRadius)

        hosting = ClickThroughHostingView(rootView: CandidateBar(state: .empty, pick: { _ in }))
        hosting.translatesAutoresizingMaskIntoConstraints = true
        hosting.autoresizingMask = [.width, .height]
        effect.addSubview(hosting)
        panel.contentView = effect
        WindowAppearance.shared.track(panel)
        themeObserver=NotificationCenter.default.addObserver(forName:Preferences.didChange,object:Preferences.shared,queue:.main) {[weak self] _ in
            self?.hosting.rootView.theme=Preferences.shared.colorTheme
        }
    }

    /// 原地更新内容与位置，不重建窗口（换键不闪）。 Update in place, never rebuild (no flicker between keys).
    func show(_ state: CandidateState, caret: NSRect, owner: WeaveInputController) {
        self.owner = owner
        panel.appearance = Self.appearance(Preferences.shared.appearance)
        hosting.rootView = CandidateBar(state: state, pick: { [weak self] i in self?.owner?.pick(pageIndex: i) }, policy: { [weak self] i,text,mode in self?.owner?.setCandidatePolicy(pageIndex:i,expectedText:text,mode:mode) },
                                        toggle: { [weak self] in self?.owner?.toggleExpand() },
                                        pickExpanded: { [weak self] i in self?.owner?.pickExpanded(index: i) },
                                        loadMore: { [weak self] in self?.owner?.loadMoreExpanded() },
                                        pageTurn: { [weak self] delta in self?.owner?.turnPage(delta) },theme:Preferences.shared.colorTheme)
        hosting.layoutSubtreeIfNeeded()
        let size = hosting.fittingSize
        let caret = usable(caret)
        let screens = NSScreen.screens.map(\.visibleFrame)
        let screen=screens.first(where:{$0.contains(caret.origin)}) ?? screens.first
        let limit=min(900,max(240,(screen?.width ?? 924)-24))
        var fitted=size
        if size.width>limit {
            var compact=state;compact.orientation = .vertical
            var bar=hosting.rootView;bar.state=compact;bar.widthLimit=limit
            hosting.rootView=bar
            hosting.layoutSubtreeIfNeeded();fitted=hosting.fittingSize
        }
        let origin = PanelPlacement.origin(size: fitted, caret: caret, screens: screens)
        let frame = NSRect(origin: origin, size: fitted).integral
        if panel.frame != frame {
            panel.setFrame(frame, display: true)
            hosting.frame = effect.bounds
            panel.invalidateShadow()
        }
        if !panel.isVisible { panel.orderFrontRegardless() }
    }

    func hide() {
        if panel.isVisible { panel.orderOut(nil) }
    }

    /// 有的应用报不出光标位置（零矩形），沿用上次的或鼠标位置。 Some apps report no caret; reuse the last one or the mouse.
    private func usable(_ caret: NSRect) -> NSRect {
        if caret.origin != .zero || caret.height > 0 {
            lastCaret = caret
            return caret
        }
        if lastCaret != .zero { return lastCaret }
        let m = NSEvent.mouseLocation
        return NSRect(x: m.x, y: m.y - 18, width: 1, height: 18)
    }

    static func appearance(_ mode: AppearanceMode) -> NSAppearance? {
        switch mode {
        case .system: return nil
        case .light: return NSAppearance(named: .aqua)
        case .dark: return NSAppearance(named: .darkAqua)
        }
    }

    private static func roundedMask(radius: CGFloat) -> NSImage {
        let edge = radius * 2 + 1
        let image = NSImage(size: NSSize(width: edge, height: edge), flipped: false) { rect in
            NSColor.black.setFill()
            NSBezierPath(roundedRect: rect, xRadius: radius, yRadius: radius).fill()
            return true
        }
        image.capInsets = NSEdgeInsets(top: radius, left: radius, bottom: radius, right: radius)
        image.resizingMode = .stretch
        return image
    }
}

/// 不激活窗口也能响应第一下点击。 Accept the first click without activating the window.
final class ClickThroughHostingView<Content: View>: NSHostingView<Content> {
    override var needsPanelToBecomeKey: Bool { false }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

extension CandidateState {
    static let empty = CandidateState(preedit: "", candidates: [], highlight: 0, hasPrevious: false, hasNext: false,
                                      orientation: .horizontal, fontSize: 16)
}

/// 候选条：组合串一行 + 带序号的候选，首选高亮，右侧翻页箭头。
/// The candidate bar: a preedit line and numbered candidates, the highlight tinted, page arrows at the end.
struct CandidateBar: View {
    @ObservedObject var cloud=EngineHost.shared.cloud
    var state: CandidateState
    let pick: (Int) -> Void
    var policy: (Int,String,String) -> Void = {_,_,_ in}
    var toggle: () -> Void = {}
    var pickExpanded: (Int) -> Void = {_ in}
    var loadMore: () -> Void = {}
    /// 点翻页箭头。 Click the page arrows. (delta: -1 上一页, +1 下一页)
    var pageTurn: (Int) -> Void = { _ in }
    var widthLimit:CGFloat? = nil
    var theme:ColorTheme = .fresh
    private var palette:ThemePalette {Theme.palette(theme)}

    private var font: Font { .system(size: state.fontSize) }
    private var small: Font { .system(size: max(10, state.fontSize * 0.72)) }
    /// 候选注释（大写金额、日期……）：比序号再小一点。 Candidate notes (大写金额, 日期…): a bit smaller than the numbers.
    private var note: Font { .system(size: max(9, state.fontSize * 0.66)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !state.preedit.isEmpty || !state.hint.isEmpty || (cloud.status.enabled && cloud.status.updating) {
                HStack(spacing:6) {
                Text(state.preedit.isEmpty ? state.hint : String(state.preedit.suffix(72)))
                    .help(state.preedit)
                    .font(state.preedit.isEmpty ? note : small)
                    .foregroundStyle(state.preedit.isEmpty ? palette.hint : palette.secondary)
                    .padding(.horizontal, 6)
                    .lineLimit(1)
                if !state.preedit.isEmpty && !state.hint.isEmpty {
                    Text(state.hint).font(note).foregroundStyle(.red).lineLimit(1)
                }
                if cloud.status.enabled && cloud.status.updating {
                    ProgressView().controlSize(.mini).tint(.blue).help("正在加载云端热词")
                }
                }
            }
            if let all = state.expanded {
                expandedGrid(all)
            } else if state.orientation == .horizontal {
                HStack(spacing: 2) {
                    items
                    arrows(vertical: false)
                    expandButton
                }
            } else {
                VStack(alignment: .leading, spacing: 1) {
                    items
                    HStack(spacing: 2) {
                        Spacer(minLength: 0)
                        arrows(vertical: true)
                        expandButton
                    }
                }
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 6)
        .frame(maxWidth:widthLimit)
        .fixedSize(horizontal:widthLimit==nil,vertical:true)
    }

    @ViewBuilder private var items: some View {
        ForEach(Array(state.candidates.enumerated()), id: \.offset) { i, c in
            let on = i == state.highlight
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text("\(i + 1)")
                    .font(small.monospacedDigit())
                    .foregroundStyle(on ? palette.candidate : palette.hint)
                candidateLabel(c,primary:on)
                if c.cloud {Image(systemName:"cloud.fill").font(note).foregroundStyle(.blue).help("来自已下载的云端热词库").accessibilityLabel("云端词")}
                if !c.comment.isEmpty {
                    Text(c.comment)
                        .font(note)
                        .foregroundStyle(palette.hint)
                        .padding(.leading, 1)
                }
            }
            .lineLimit(1)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .frame(maxWidth: state.orientation == .vertical ? .infinity : nil, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(on ? palette.accentSoft : .clear))
            .contentShape(Rectangle())
            .onTapGesture { pick(i) }
            .contextMenu {
                Button("恢复正常排序") { policy(i,c.text,"") }
                Button("降低优先级") { policy(i,c.text,"down") }
                if c.user {Button("删除学习记录") {policy(i,c.text,"forget")}}
            }
        }
    }

    private func candidateLabel(_ c:Candidate,primary:Bool) -> some View {
        VStack(alignment:.leading,spacing:2) {
            if !c.pinyin.isEmpty {
                Text(c.pinyin).font(note).foregroundStyle(palette.secondary)
                    .lineLimit(nil).fixedSize(horizontal:false,vertical:true)
            }
            Text(c.text.count>48 ? String(c.text.prefix(47))+"…" : c.text)
                .help(c.text).font(font).foregroundStyle(primary ? palette.candidate : palette.label)
        }
    }

    /// 下拉按钮：展开成全部候选。 The dropdown button: expand into the full list.
    @ViewBuilder private var expandButton: some View {
        if state.expandable {
            Button(action: toggle) {
                Image(systemName: "chevron.down")
                    .font(.system(size: max(9, state.fontSize * 0.6), weight: .semibold))
                    .foregroundStyle(palette.accent)
                    .frame(width: 22, height: 22)
                    .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(palette.accentSoft))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("展开全部候选")
            .accessibilityLabel("展开全部候选")
        }
    }

    /// 可以点的翻页箭头：上一页、下一页。 Clickable page arrows: previous / next.
    @ViewBuilder private func arrow(_ systemName: String, enabled: Bool, action: @escaping () -> Void, hint: String) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .foregroundStyle(enabled ? palette.accent : palette.hint.opacity(0.4))
                .font(.system(size: max(9, state.fontSize * 0.6), weight: .semibold))
                .frame(width: 18, height: 20)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .help(hint)
        .accessibilityLabel(hint)
    }

    /// 展开后的候选网格：滚动浏览，点选上屏，滚到底附近再取一批。 The expanded grid: scroll, click to pick, more load near the end.
    @ViewBuilder private func expandedGrid(_ all: [Candidate]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ScrollView(.vertical, showsIndicators: true) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96, maximum: 260), spacing: 2, alignment: .leading)],
                          alignment: .leading, spacing: 2) {
                    ForEach(Array(all.enumerated()), id: \.offset) { i, c in
                        HStack(alignment: .lastTextBaseline, spacing: 4) {
                            Text(c.text).font(font).foregroundStyle(i == 0 ? palette.candidate : palette.label)
                            if c.cloud { Image(systemName: "cloud.fill").font(note).foregroundStyle(.blue) }
                        }
                        .lineLimit(1)
                        .padding(.horizontal, 7).padding(.vertical, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(i == 0 ? palette.accentSoft : .clear))
                        .contentShape(Rectangle())
                        .onTapGesture { pickExpanded(i) }
                        .onAppear { if state.expandedMore, i >= all.count - 12 { loadMore() } }
                    }
                }
            }
            .frame(width: 560, height: min(300, max(80, CGFloat((all.count + 4) / 5) * (state.fontSize + 14))))
            HStack {
                Text("共 \(all.count)\(state.expandedMore ? "+" : "") 个 · 点选上屏，按任意键收起").font(note).foregroundStyle(palette.hint)
                Spacer()
                Button(action: toggle) {
                    Image(systemName: "chevron.up")
                        .font(.system(size: max(9, state.fontSize * 0.6), weight: .semibold))
                        .foregroundStyle(palette.accent).frame(width: 22, height: 22)
                        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(palette.accentSoft))
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).help("收起").accessibilityLabel("收起")
            }.padding(.horizontal, 6)
        }
    }

    @ViewBuilder private func arrows(vertical: Bool) -> some View {
        if state.hasPrevious || state.hasNext {
            HStack(spacing: 2) {
                arrow(vertical ? "chevron.up" : "chevron.left", enabled: state.hasPrevious,
                      action: { pageTurn(-1) }, hint: "上一页")
                arrow(vertical ? "chevron.down" : "chevron.right", enabled: state.hasNext,
                      action: { pageTurn(1) }, hint: "下一页")
            }
            .padding(.leading, 4)
            .padding(.trailing, 2)
        }
    }
}
