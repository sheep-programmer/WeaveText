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

/// 跟随光标的候选窗：无边框、不抢焦点，整个进程复用一个。
/// The caret-following candidate window: borderless, never takes focus, one per process.
final class CandidatePanel {
    static let shared = CandidatePanel()

    private let panel: NSPanel
    private let hosting: ClickThroughHostingView<CandidateBar>
    private let effect = NSVisualEffectView()
    private weak var owner: WeaveInputController?
    private var lastCaret = NSRect.zero
    static let cornerRadius: CGFloat = 8

    private init() {
        panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 200, height: 40),
                        styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        panel.level = .popUpMenu
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = true
        panel.hidesOnDeactivate = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle, .transient]

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
    }

    /// 原地更新内容与位置，不重建窗口（换键不闪）。 Update in place, never rebuild (no flicker between keys).
    func show(_ state: CandidateState, caret: NSRect, owner: WeaveInputController) {
        self.owner = owner
        panel.appearance = Self.appearance(Preferences.shared.appearance)
        hosting.rootView = CandidateBar(state: state, pick: { [weak self] i in self?.owner?.pick(pageIndex: i) }, policy: { [weak self] i,text,mode in self?.owner?.setCandidatePolicy(pageIndex:i,expectedText:text,mode:mode) },
                                        toggle: { [weak self] in self?.owner?.toggleExpand() },
                                        pickExpanded: { [weak self] i in self?.owner?.pickExpanded(index: i) },
                                        loadMore: { [weak self] in self?.owner?.loadMoreExpanded() })
        hosting.layoutSubtreeIfNeeded()
        let size = hosting.fittingSize
        let caret = usable(caret)
        let screens = NSScreen.screens.map(\.visibleFrame)
        let origin = PanelPlacement.origin(size: size, caret: caret, screens: screens)
        let frame = NSRect(origin: origin, size: size).integral
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
    let state: CandidateState
    let pick: (Int) -> Void
    var policy: (Int,String,String) -> Void = {_,_,_ in}
    var toggle: () -> Void = {}
    var pickExpanded: (Int) -> Void = {_ in}
    var loadMore: () -> Void = {}

    private var font: Font { .system(size: state.fontSize) }
    private var small: Font { .system(size: max(10, state.fontSize * 0.72)) }
    /// 候选注释（大写金额、日期……）：比序号再小一点。 Candidate notes (大写金额, 日期…): a bit smaller than the numbers.
    private var note: Font { .system(size: max(9, state.fontSize * 0.66)) }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if !state.preedit.isEmpty || !state.hint.isEmpty || (cloud.status.enabled && cloud.status.updating) {
                HStack(spacing:6) {
                Text(state.preedit.isEmpty ? state.hint : state.preedit)
                    .font(state.preedit.isEmpty ? note : small)
                    .foregroundStyle(state.preedit.isEmpty ? Theme.hint : Theme.secondary)
                    .padding(.horizontal, 6)
                    .lineLimit(1)
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
        .fixedSize()
    }

    @ViewBuilder private var items: some View {
        ForEach(Array(state.candidates.enumerated()), id: \.offset) { i, c in
            let on = i == state.highlight
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text("\(i + 1)")
                    .font(small.monospacedDigit())
                    .foregroundStyle(on ? Theme.candidate : Theme.hint)
                Text(c.text)
                    .font(font)
                    .foregroundStyle(on ? Theme.candidate : Theme.label)
                if c.cloud {Image(systemName:"cloud.fill").font(note).foregroundStyle(.blue).help("来自已下载的云端热词库").accessibilityLabel("云端词")}
                if !c.comment.isEmpty {
                    Text(c.comment)
                        .font(note)
                        .foregroundStyle(Theme.hint)
                        .padding(.leading, 1)
                }
            }
            .lineLimit(1)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .frame(maxWidth: state.orientation == .vertical ? .infinity : nil, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(on ? Theme.accentSoft : .clear))
            .contentShape(Rectangle())
            .onTapGesture { pick(i) }
            .contextMenu {
                Button("固定为首选") { policy(i,c.text,"pin") }
                Button("取消固定／恢复排序") { policy(i,c.text,"") }
                Button("降低优先级") { policy(i,c.text,"down") }
                if c.user {Button("删除学习记录") {policy(i,c.text,"forget")}}
            }
        }
    }

    /// 下拉按钮：展开成全部候选。 The dropdown button: expand into the full list.
    @ViewBuilder private var expandButton: some View {
        if state.expandable {
            Button(action: toggle) {
                Image(systemName: "chevron.down")
                    .font(.system(size: max(9, state.fontSize * 0.6), weight: .semibold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 22, height: 22)
                    .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Theme.accentSoft))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help("展开全部候选")
            .accessibilityLabel("展开全部候选")
        }
    }

    /// 展开后的候选网格：滚动浏览，点选上屏，滚到底附近再取一批。 The expanded grid: scroll, click to pick, more load near the end.
    @ViewBuilder private func expandedGrid(_ all: [Candidate]) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ScrollView(.vertical, showsIndicators: true) {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 96, maximum: 260), spacing: 2, alignment: .leading)],
                          alignment: .leading, spacing: 2) {
                    ForEach(Array(all.enumerated()), id: \.offset) { i, c in
                        HStack(alignment: .firstTextBaseline, spacing: 4) {
                            Text(c.text).font(font).foregroundStyle(i == 0 ? Theme.candidate : Theme.label)
                            if c.cloud { Image(systemName: "cloud.fill").font(note).foregroundStyle(.blue) }
                            if !c.comment.isEmpty { Text(c.comment).font(note).foregroundStyle(Theme.hint) }
                        }
                        .lineLimit(1)
                        .padding(.horizontal, 7).padding(.vertical, 4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(i == 0 ? Theme.accentSoft : .clear))
                        .contentShape(Rectangle())
                        .onTapGesture { pickExpanded(i) }
                        .onAppear { if state.expandedMore, i >= all.count - 12 { loadMore() } }
                    }
                }
            }
            .frame(width: 560, height: min(300, max(80, CGFloat((all.count + 4) / 5) * (state.fontSize + 14))))
            HStack {
                Text("共 \(all.count)\(state.expandedMore ? "+" : "") 个 · 点选上屏，按任意键收起").font(note).foregroundStyle(Theme.hint)
                Spacer()
                Button(action: toggle) {
                    Image(systemName: "chevron.up")
                        .font(.system(size: max(9, state.fontSize * 0.6), weight: .semibold))
                        .foregroundStyle(Theme.accent).frame(width: 22, height: 22)
                        .background(RoundedRectangle(cornerRadius: 6, style: .continuous).fill(Theme.accentSoft))
                        .contentShape(Rectangle())
                }.buttonStyle(.plain).help("收起").accessibilityLabel("收起")
            }.padding(.horizontal, 6)
        }
    }

    @ViewBuilder private func arrows(vertical: Bool) -> some View {
        if state.hasPrevious || state.hasNext {
            HStack(spacing: 2) {
                Image(systemName: vertical ? "chevron.up" : "chevron.left")
                    .foregroundStyle(state.hasPrevious ? Theme.accent : Theme.hint.opacity(0.4))
                Image(systemName: vertical ? "chevron.down" : "chevron.right")
                    .foregroundStyle(state.hasNext ? Theme.accent : Theme.hint.opacity(0.4))
            }
            .font(.system(size: max(9, state.fontSize * 0.6), weight: .semibold))
            .padding(.leading, 4)
            .padding(.trailing, 2)
        }
    }
}
