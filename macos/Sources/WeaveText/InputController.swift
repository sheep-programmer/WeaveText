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
    /// 敲等号后给出的算式结果（候选窗里只有它一个）。 The result offered after `=`, the only item in the panel.
    private var calcResult: String?
    /// 候选窗里是上屏后的联想词。 The panel shows predictions after a commit.
    private var predicting = false
    /// 上一个动作是内核上屏（紧接着退格会撤销刚学到的词）。 The last action was an engine commit; a backspace right
    /// after it undoes what the commit learned.
    private var justCommitted = false

    override func recognizedEvents(_ sender: Any!) -> Int {
        // 鼠标点在文档里时收起联想词。 A click in the document dismisses the predictions.
        Int(NSEvent.EventTypeMask([.keyDown, .flagsChanged, .leftMouseDown]).rawValue)
    }

    override func activateServer(_ sender: Any!) {
        super.activateServer(sender)
        host.activeController = self
        host.engine?.clear()
        punctuation.reset()
        afterDigit = false
        preedit = ""
        calcResult = nil
        predicting = false
        justCommitted = false
        capsLock = NSEvent.modifierFlags.contains(.capsLock)
        host.syncClock()
    }

    override func deactivateServer(_ sender: Any!) {
        finishComposition(client: sender as? IMKTextInput)
        calcResult = nil
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
        dismissPredictions()
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
        case .leftMouseDown:
            dismissPredictions()
            justCommitted = false
            return false
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
        if calcResult != nil, handleCalcKey(key, client) { return true }
        let wasJustCommitted = justCommitted
        justCommitted = false
        if predicting {
            switch KeyMapper.predictionAction(for: key, count: pager.page.count, pageSize: prefs.pageSize) {
            case .select(let n):
                engine.select(pager.offset + n)
                refresh(client)
                return true
            case .dismiss:
                dismissPredictions()
                return true
            case .dismissAndHandle:
                dismissPredictions()
            }
        }
        let composing = engine.isComposing
        let ctx = KeyContext(composing: composing, chinese: host.chinese, pageSize: prefs.pageSize,
                             pageKeys: prefs.pageKeys,
                             vMode: composing && Calc.isVMode(preedit: preedit, scheme: host.scheme.id))
        let action = KeyMapper.action(for: key, in: ctx)
        if !composing, key.characters == "=", !key.command, !key.control, !key.option, offerCalc(client) {
            return true
        }
        let wasAfterDigit = afterDigit
        afterDigit = false

        switch action {
        case .pass:
            afterDigit = !ctx.composing && key.character?.isNumber == true && key.character?.isASCII == true
            if !ctx.composing, key.keyCode == KeyCode.delete, wasJustCommitted, !key.command, !key.option {
                // 上屏后马上退格：先让内核撤销刚学到的，再把退格交给应用删字。
                // A backspace right after a commit: let the engine undo what it learned, then the app deletes.
                engine.backspace()
            } else if !ctx.composing, ctx.chinese, !key.command, !key.control, !key.option,
                      key.character.map({ !$0.isWhitespace || $0 == " " }) == true, !isFunction(key.keyCode) {
                // 直接上屏的字符隔开前后两次上屏，不让它们连成新词。 Text typed straight through breaks the chain
                // of consecutive commits, so they don't join into a new word.
                engine.commitFirst()
            }
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
            // 标点隔开前后两次上屏。 Punctuation breaks the chain of consecutive commits.
            if !ctx.composing { engine.commitFirst() }
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

    /// 收起联想词（换输入框、点击文档、空格回车等）。 Dismiss the predictions (focus change, a click, space, return…).
    private func dismissPredictions() {
        guard predicting else { return }
        predicting = false
        host.engine?.dismissPredictions()
        pager.reset(total: 0, pageSize: prefs.pageSize) { _, _ in [] }
        if calcResult == nil { CandidatePanel.shared.hide() }
    }

    private func isFunction(_ code: UInt16) -> Bool {
        [KeyCode.returnKey, KeyCode.keypadEnter, KeyCode.tab, KeyCode.delete, KeyCode.escape, KeyCode.forwardDelete,
         KeyCode.home, KeyCode.end, KeyCode.pageUp, KeyCode.pageDown, KeyCode.left, KeyCode.right, KeyCode.up,
         KeyCode.down].contains(code)
    }

    private var fetch: Pager.Fetch {
        { [weak self] offset, limit in self?.host.engine?.candidates(offset: offset, limit: limit) ?? [] }
    }

    // MARK: - 等号算式 / The result after `=`

    /// 光标前是算式：自己输出等号，并把结果放进只有一项的候选窗。 An expression before the caret: type the `=`
    /// ourselves and put the result in a one-item panel.
    private func offerCalc(_ client: IMKTextInput) -> Bool {
        guard let before = textBeforeCaret(client), let expr = Calc.expression(before: before),
              let result = WeaveSession.eval(expr) else { return false }
        insert("=", client)
        calcResult = result
        showCalc(client)
        return true
    }

    /// 结果显示中：1 或空格接上结果，其他键收起它再照常处理。 While the result shows: 1 or Space appends it,
    /// any other key dismisses it and is handled as usual.
    private func handleCalcKey(_ key: KeyInput, _ client: IMKTextInput) -> Bool {
        let plain = !key.command && !key.control && !key.option
        if plain, key.keyCode == KeyCode.space || key.characters == "1" {
            acceptCalc(client)
            return true
        }
        calcResult = nil
        CandidatePanel.shared.hide()
        return false
    }

    private func acceptCalc(_ client: IMKTextInput) {
        guard let r = calcResult else { return }
        calcResult = nil
        CandidatePanel.shared.hide()
        insert(r, client)
    }

    private func showCalc(_ client: IMKTextInput) {
        guard let r = calcResult else { return }
        var caret = NSRect.zero
        client.attributes(forCharacterIndex: 0, lineHeightRectangle: &caret)
        let state = CandidateState(preedit: "", candidates: [Candidate(text: r, comment: "计算结果")], highlight: 0,
                                   hasPrevious: false, hasNext: false, orientation: prefs.orientation,
                                   fontSize: CGFloat(prefs.fontSize))
        CandidatePanel.shared.show(state, caret: caret, owner: self)
    }

    /// 光标前最多 64 个字符；应用不支持时 nil。 Up to 64 characters before the caret; nil when the app can't tell.
    private func textBeforeCaret(_ client: IMKTextInput, limit: Int = 64) -> String? {
        let sel = client.selectedRange()
        guard sel.location != NSNotFound, sel.location > 0 else { return nil }
        let start = max(0, sel.location - limit)
        return client.attributedSubstring(from: NSRange(location: start, length: sel.location - start))?.string
    }

    /// 选中候选窗里的第 n 个（鼠标点选）。 Pick the n-th visible candidate (mouse click).
    func pick(pageIndex n: Int) {
        if calcResult != nil, let client = client() {
            acceptCalc(client)
            return
        }
        guard let engine = host.engine, n < pager.page.count, let client = client() else { return }
        engine.select(pager.offset + n)
        refresh(client)
    }

    // MARK: - 上屏与显示 / Commit and display

    private func insert(_ text: String, _ client: IMKTextInput) {
        justCommitted = false
        client.insertText(text, replacementRange: NSRange(location: NSNotFound, length: 0))
    }

    /// 读取内核状态：上屏、更新组合串与候选。 Read the engine: commit, update marked text and candidates.
    private func refresh(_ client: IMKTextInput?) {
        guard let engine = host.engine else { return }
        let s = engine.snapshot()
        if !s.commit.isEmpty, let client {
            insert(s.commit, client)
            justCommitted = true
        }
        predicting = !s.composing && s.predicting && !s.candidates.isEmpty
        if s.composing || predicting {
            let first = s.candidates
            pager.reset(total: s.total, pageSize: prefs.pageSize) { [weak self] offset, limit in
                offset + limit <= first.count
                    ? Array(first[offset..<offset + limit])
                    : self?.host.engine?.candidates(offset: offset, limit: limit) ?? []
            }
            if predicting {
                // 联想词没有组合串。 Predictions have no marked text.
                if s.commit.isEmpty, !preedit.isEmpty, let client { setMarked("", client) }
                preedit = ""
            } else {
                preedit = s.preedit
                if let client { setMarked(s.preedit, client) }
            }
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
        // 联想词不高亮（空格不选它），也不翻页。 Predictions have no highlight (space doesn't pick) and no paging.
        let state = CandidateState(preedit: preedit, candidates: pager.page, highlight: predicting ? -1 : pager.highlight,
                                   hasPrevious: !predicting && pager.hasPrevious, hasNext: !predicting && pager.hasNext,
                                   orientation: prefs.orientation, fontSize: CGFloat(prefs.fontSize),
                                   hint: predicting ? "联想" : "")
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
            if host.engine?.hasSchema(s.id) == false { item.action = nil }
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
