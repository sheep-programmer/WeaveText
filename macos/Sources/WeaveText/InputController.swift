import AppKit
import InputMethodKit
import WeaveCore

/// 一个输入会话（系统为每个客户端建一个；内核会话全进程共用）。
/// One input session; the system makes one per client, the engine session is shared process-wide.
@objc(WeaveInputController)
final class WeaveInputController: IMKInputController {
    private var host: EngineHost { .shared }
    private var prefs: Preferences { host.prefs }
    private var pager = Pager(pageSize: 7)
    private var punctuation = Punctuation()
    private var shiftTap = ShiftTapDetector()
    private var capsLock = false
    /// 上一个直通的键是数字（3.14 里的句点保持半角）。 The last passed-through key was a digit.
    private var afterDigit = false
    private var preedit = ""

    override func recognizedEvents(_ sender: Any!) -> Int {
        Int(NSEvent.EventTypeMask([.keyDown, .flagsChanged]).rawValue)
    }

    override func activateServer(_ sender: Any!) {
        super.activateServer(sender)
        host.activeController = self
        host.engine?.clear()
        punctuation.reset()
        afterDigit = false
        preedit = ""
        capsLock = NSEvent.modifierFlags.contains(.capsLock)
    }

    override func deactivateServer(_ sender: Any!) {
        finishComposition(client: sender as? IMKTextInput)
        CandidatePanel.shared.hide()
        host.engine?.flush()
        if host.activeController === self { host.activeController = nil }
        super.deactivateServer(sender)
    }

    override func commitComposition(_ sender: Any!) {
        finishComposition(client: sender as? IMKTextInput)
    }

    /// 把正在组合的字母原样上屏并复位。 Commit the typed letters as they are and reset.
    func finishComposition(client: IMKTextInput? = nil) {
        guard let engine = host.engine, engine.isComposing else { return }
        engine.commitRaw()
        refresh(client ?? self.client())
    }

    // MARK: - 按键 / Keys

    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, let client = sender as? IMKTextInput, host.engine != nil else { return false }
        host.activeController = self
        switch event.type {
        case .flagsChanged:
            flagsChanged(event, client)
            return false
        case .keyDown:
            return keyDown(event, client)
        default:
            return false
        }
    }

    private func flagsChanged(_ event: NSEvent, _ client: IMKTextInput) {
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let caps = flags.contains(.capsLock)
        if caps != capsLock {
            capsLock = caps
            // 打开大写锁定时收尾组合，之后直通大写。 Caps Lock on: finish composing, then pass capitals through.
            if caps { finishComposition(client: client) }
        }
        guard prefs.toggleKey == .shift else { return }
        let others = !flags.intersection([.command, .control, .option, .function]).isEmpty
        let isShiftKey = event.keyCode == 56 || event.keyCode == 60
        if shiftTap.flagsChanged(shift: flags.contains(.shift) && isShiftKey, otherModifiers: others,
                                 at: event.timestamp) {
            host.toggleChinese()
        }
    }

    private func keyDown(_ event: NSEvent, _ client: IMKTextInput) -> Bool {
        shiftTap.keyDown()
        guard let engine = host.engine else { return false }
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let key = KeyInput(keyCode: event.keyCode, characters: event.characters ?? "",
                           shift: flags.contains(.shift), control: flags.contains(.control),
                           option: flags.contains(.option), command: flags.contains(.command),
                           capsLock: flags.contains(.capsLock))
        let ctx = KeyContext(composing: engine.isComposing, chinese: host.chinese, pageSize: prefs.pageSize,
                             pageKeys: prefs.pageKeys)
        let action = KeyMapper.action(for: key, in: ctx)
        let wasAfterDigit = afterDigit
        afterDigit = false

        switch action {
        case .pass:
            afterDigit = !ctx.composing && key.character?.isNumber == true && key.character?.isASCII == true
            return false
        case .swallow:
            return true
        case .letter(let c):
            if engine.input(c) {
                refresh(client)
                return true
            }
            if ctx.composing { return true }
            return false
        case .punctuation(let c):
            if ctx.composing {
                if engine.input(c) {
                    refresh(client)
                    return true
                }
                commitHighlighted(engine, all: true)
                refresh(client)
            }
            guard host.scheme.isChinese else {
                if ctx.composing { insert(String(c), client) }
                return ctx.composing
            }
            insert(punctuation.convert(c, afterDigit: wasAfterDigit && !ctx.composing), client)
            return true
        case .backspace:
            engine.backspace()
        case .clear:
            engine.clear()
        case .commitRaw:
            engine.commitRaw()
        case .commitHighlighted:
            commitHighlighted(engine)
        case .select(let n):
            guard n < pager.page.count else { return true }
            engine.select(pager.offset + n)
        case .pagePrevious:
            pager.previous(fetch: fetch)
            showCandidates(client)
            return true
        case .pageNext:
            pager.next(fetch: fetch)
            showCandidates(client)
            return true
        case .highlightPrevious:
            pager.highlightPrevious(fetch: fetch)
            showCandidates(client)
            return true
        case .highlightNext:
            pager.highlightNext(fetch: fetch)
            showCandidates(client)
            return true
        }
        refresh(client)
        return true
    }

    /// 空格：第一页首选时整句上屏，否则选高亮的那个。 Space: the whole sentence for the top pick, else the highlight.
    /// all：标点前要整段上屏，选完高亮后剩下的也按首选上屏。 all: before punctuation, commit the rest too.
    private func commitHighlighted(_ engine: WeaveSession, all: Bool = false) {
        let i = pager.highlightedIndex
        if i == 0 || pager.page.isEmpty { engine.commitFirst() } else { engine.select(i) }
        if all, engine.isComposing { engine.commitFirst() }
    }

    private var fetch: Pager.Fetch {
        { [weak self] offset, limit in self?.host.engine?.candidates(offset: offset, limit: limit) ?? [] }
    }

    /// 选中候选窗里的第 n 个（鼠标点选）。 Pick the n-th visible candidate (mouse click).
    func pick(pageIndex n: Int) {
        guard let engine = host.engine, n < pager.page.count, let client = client() else { return }
        engine.select(pager.offset + n)
        refresh(client)
    }

    // MARK: - 上屏与显示 / Commit and display

    private func insert(_ text: String, _ client: IMKTextInput) {
        client.insertText(text, replacementRange: NSRange(location: NSNotFound, length: 0))
    }

    /// 读取内核状态：上屏、更新组合串与候选。 Read the engine: commit, update marked text and candidates.
    private func refresh(_ client: IMKTextInput?) {
        guard let engine = host.engine else { return }
        let s = engine.snapshot()
        if !s.commit.isEmpty, let client { insert(s.commit, client) }
        if s.composing {
            let first = s.candidates
            pager.reset(total: s.total, pageSize: prefs.pageSize) { [weak self] offset, limit in
                offset + limit <= first.count
                    ? Array(first[offset..<offset + limit])
                    : self?.host.engine?.candidates(offset: offset, limit: limit) ?? []
            }
            preedit = s.preedit
            if let client { setMarked(s.preedit, client) }
            showCandidates(client)
        } else {
            if s.commit.isEmpty, !preedit.isEmpty, let client { setMarked("", client) }
            preedit = ""
            pager.reset(total: 0, pageSize: prefs.pageSize) { _, _ in [] }
            CandidatePanel.shared.hide()
        }
    }

    private func setMarked(_ text: String, _ client: IMKTextInput) {
        let attrs = mark(forStyle: kTSMHiliteRawText, at: NSRange(location: 0, length: (text as NSString).length))
            as? [NSAttributedString.Key: Any] ?? [.underlineStyle: NSUnderlineStyle.single.rawValue]
        client.setMarkedText(NSAttributedString(string: text, attributes: attrs),
                             selectionRange: NSRange(location: (text as NSString).length, length: 0),
                             replacementRange: NSRange(location: NSNotFound, length: 0))
    }

    private func showCandidates(_ client: IMKTextInput?) {
        guard let client else { return }
        var caret = NSRect.zero
        client.attributes(forCharacterIndex: 0, lineHeightRectangle: &caret)
        let state = CandidateState(preedit: preedit, candidates: pager.page, highlight: pager.highlight,
                                   hasPrevious: pager.hasPrevious, hasNext: pager.hasNext,
                                   orientation: prefs.orientation, fontSize: CGFloat(prefs.fontSize))
        CandidatePanel.shared.show(state, caret: caret, owner: self)
    }

    // MARK: - 输入法菜单 / Input menu

    override func menu() -> NSMenu! {
        let menu = NSMenu()
        menu.addItem(withTitle: "设置…", action: #selector(openSettings(_:)), keyEquivalent: "").target = self
        menu.addItem(.separator())
        for (i, s) in InputScheme.all.enumerated() {
            let item = NSMenuItem(title: s.name, action: #selector(selectScheme(_:)), keyEquivalent: "")
            item.tag = i
            item.target = self
            item.state = s.id == prefs.schema ? .on : .off
            menu.addItem(item)
        }
        menu.addItem(.separator())
        let trad = NSMenuItem(title: "繁体输出", action: #selector(toggleTraditional(_:)), keyEquivalent: "")
        trad.target = self
        trad.state = prefs.traditional ? .on : .off
        menu.addItem(trad)
        return menu
    }

    /// IMK 把菜单项放在字典里传来。 IMK passes the menu item inside a dictionary.
    private func menuItem(_ sender: Any?) -> NSMenuItem? {
        if let item = sender as? NSMenuItem { return item }
        return (sender as? NSDictionary)?[kIMKCommandMenuItemName] as? NSMenuItem
    }

    @objc func openSettings(_ sender: Any?) {
        SettingsWindow.shared.show()
    }

    @objc func selectScheme(_ sender: Any?) {
        guard let tag = menuItem(sender)?.tag, InputScheme.all.indices.contains(tag) else { return }
        prefs.schema = InputScheme.all[tag].id
    }

    @objc func toggleTraditional(_ sender: Any?) {
        prefs.traditional.toggle()
    }
}
