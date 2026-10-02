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
    private var reconversionOriginal: (text:String,schema:String,range:NSRange)?
    private weak var reconversionClient: AnyObject?
    /// 敲等号后给出的算式结果（候选窗里只有它一个）。 The result offered after `=`, the only item in the panel.
    private var calcResult: String?
    /// 候选窗里是上屏后的联想词。 The panel shows predictions after a commit.
    private var predicting = false

    override func recognizedEvents(_ sender: Any!) -> Int {
        // 鼠标点在文档里时收起联想词。 A click in the document dismisses the predictions.
        Int(NSEvent.EventTypeMask([.keyDown, .flagsChanged, .leftMouseDown]).rawValue)
    }

    override func activateServer(_ sender: Any!) {
        super.activateServer(sender)
        host.activeController = self
        host.engine?.clear()
        // 换了输入框：上一次上屏与这里无关。 A new field: the previous commit has nothing to do with it.
        host.engine?.setContext(nil)
        punctuation.reset()
        afterDigit = false
        preedit = ""
        calcResult = nil
        predicting = false
        capsLock = NSEvent.modifierFlags.contains(.capsLock)
        host.syncClock()
        host.cloud.refreshIfStale()
    }

    override func deactivateServer(_ sender: Any!) {
        HandwritingWindow.shared.dismiss(owner:self)
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
        if reconversionOriginal != nil { cancelReconversion(); return }
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
            if reconversionOriginal != nil { cancelReconversion() }
            dismissPredictions()
            // 光标挪了：之后的退格删的不是刚上屏的词。 The caret moved: a later backspace isn't deleting that commit.
            host.engine?.setContext(nil)
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
        if reconversionOriginal != nil && !ownsReconversion(client) {cancelReconversion()}
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        let key = KeyInput(keyCode: event.keyCode, characters: event.characters ?? "",
                           shift: flags.contains(.shift), control: flags.contains(.control),
                           option: flags.contains(.option), command: flags.contains(.command),
                           capsLock: flags.contains(.capsLock))
        if calcResult != nil, handleCalcKey(key, client) { return true }
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
        let ctx = KeyContext(composing: composing, chinese: host.chinese && host.scheme.isChinese, pageSize: prefs.pageSize,
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
            if ctx.composing && !ctx.chinese { engine.commitRaw(); refresh(client) }
            afterDigit = !ctx.composing && key.character?.isNumber == true && key.character?.isASCII == true
            if !ctx.composing { passIdle(key, engine) }
            return false
        case .swallow:
            return true
        case .letter(let c):
            if engine.input(c) {
                refresh(client)
                return true
            }
            if ctx.composing { return true }
            passIdle(key, engine)
            return false
        case .punctuation(let c):
            if ctx.composing {
                if engine.input(c) {
                    refresh(client)
                    return true
                }
                if ctx.chinese { commitHighlighted(engine, all: true) } else { engine.select(0) }
                refresh(client)
            }
            guard ctx.chinese else {
                if ctx.composing {
                    insert(String(c), client)
                } else {
                    passIdle(key, engine)
                }
                return ctx.composing
            }
            insert(punctuation.convert(c, afterDigit: wasAfterDigit && !ctx.composing), client)
            return true
        case .backspace:
            engine.backspace()
        case .clear:
            if reconversionOriginal != nil {cancelReconversion()} else {engine.clear()}
        case .commitRaw:
            if reconversionOriginal != nil {engine.select(pager.highlightedIndex)} else {engine.commitRaw()}
        case .commitHighlighted:
            commitHighlighted(engine)
        case .commitEnglishWord:
            engine.select(pager.highlightedIndex)
            refresh(client)
            insert(" ", client)
            return true
        case .finishEnglishAndPass:
            engine.commitRaw()
            refresh(client)
            passIdle(key, engine)
            return false
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

    /// 不在组合时把键交给应用：退格先给内核（由它决定是否撤销刚学到的词），会写字或挪光标的键断开连续上屏。
    /// A key handed to the app while idle: backspace reaches the engine first (it decides whether to undo what was
    /// just learned); keys that write or move the caret break the chain of commits.
    private func passIdle(_ key: KeyInput, _ engine: WeaveSession) {
        switch KeyMapper.idlePass(for: key) {
        case .backspace: engine.backspace()
        case .breakChain: engine.breakChain()
        case .none: break
        }
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
        if reconversionOriginal != nil && !ownsReconversion(client) {cancelReconversion();return}
        engine.select(pager.offset + n)
        refresh(client)
    }

    @objc func openHandwriting(_ sender:Any?) {HandwritingWindow.shared.show(owner:self)}
    func commitHandCandidate(_ index:Int) {
        guard let engine=host.engine,let client=client() else{return}
        engine.select(index);refresh(client)
    }
    func setCandidatePolicy(pageIndex:Int,expectedText:String,mode:String) {
        guard let engine=host.engine, pageIndex<pager.page.count,let client=client() else{return}
        let text=pager.page[pageIndex].text
        guard text==expectedText,engine.candidates(offset:pager.offset+pageIndex,limit:1).first?.text==text else{return}
        if mode=="forget" {engine.forget(pager.offset+pageIndex);refresh(client);return}
        engine.features(["op":"policy","index":pager.offset+pageIndex,"text":text,"mode":mode])
        refresh(client)
    }
    @objc func reconvertSelection(_ sender:Any?) {
        guard let client=client(),let engine=host.engine,!engine.isComposing else{return}
        let range=client.selectedRange()
        guard range.location != NSNotFound,range.length>0,range.length<=24,
              let text=client.attributedSubstring(from:range)?.string else{return}
        let schema=host.chinese ? host.scheme.id : "english"
        guard engine.features(["op":"reconvert","text":text]).bool("ok") else{return}
        reconversionOriginal=(text,schema,range);reconversionClient=client as AnyObject
        refresh(client)
    }

    private func ownsReconversion(_ client:IMKTextInput)->Bool {
        guard let original=reconversionOriginal,reconversionClient === client as AnyObject else{return false}
        let marked=client.markedRange();let selected=client.selectedRange()
        return marked.location != NSNotFound && marked.location==original.range.location &&
            selected.location>=marked.location && NSMaxRange(selected)<=NSMaxRange(marked) &&
            client.attributedSubstring(from:marked)?.string==preedit
    }

    private func cancelReconversion() {
        guard let original=reconversionOriginal else{return}
        if let target=reconversionClient as? IMKTextInput {
            let marked=target.markedRange()
            if marked.location != NSNotFound, marked.location==original.range.location, target.attributedSubstring(from:marked)?.string == preedit {write(original.text,target)}
        }
        reconversionOriginal=nil;reconversionClient=nil;preedit=""
        host.engine?.clear();host.engine?.setSchema(original.schema)
        CandidatePanel.shared.hide()
    }

    // MARK: - 上屏与显示 / Commit and display

    /// 输入法自己写的字（标点、等号、算式结果）：之后的退格不撤销学习，也不与前后的上屏连成新词。
    /// Text the IME writes itself (punctuation, `=`, the result): a later backspace undoes no learning, and the
    /// commits around it don't join into a new word.
    private func insert(_ text: String, _ client: IMKTextInput) {
        host.engine?.breakChain()
        write(text, client)
    }

    private func write(_ text: String, _ client: IMKTextInput) {
        client.insertText(text, replacementRange: NSRange(location: NSNotFound, length: 0))
    }

    /// 读取内核状态：上屏、更新组合串与候选。 Read the engine: commit, update marked text and candidates.
    private func refresh(_ client: IMKTextInput?) {
        guard let engine = host.engine else { return }
        var s = engine.snapshot()
        if !s.commit.isEmpty, let client {
            write(s.commit, client)
            if let original=reconversionOriginal {
                reconversionOriginal=nil;reconversionClient=nil
                engine.setSchema(original.schema);s=engine.snapshot()
            }
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
                if let client { setMarked(s.preedit, client, marks: s.marks) }
            }
            showCandidates(client)
        } else {
            if s.commit.isEmpty, !preedit.isEmpty, let client { setMarked("", client) }
            preedit = ""
            pager.reset(total: 0, pageSize: prefs.pageSize) { _, _ in [] }
            CandidatePanel.shared.hide()
        }
    }

    private func setMarked(_ text: String, _ client: IMKTextInput, marks: [PreeditMark] = []) {
        let attrs = mark(forStyle: kTSMHiliteRawText, at: NSRange(location: 0, length: (text as NSString).length))
            as? [NSAttributedString.Key: Any] ?? [.underlineStyle: NSUnderlineStyle.single.rawValue]
        let styled = NSMutableAttributedString(string: text, attributes: attrs)
        // 自动纠错改过的字母标红，一眼看出输入被改了哪里。 Letters the auto-correction changed are red.
        for r in PreeditMark.ranges(marks, in: text) {
            styled.addAttribute(.foregroundColor, value: NSColor.systemRed, range: r)
        }
        client.setMarkedText(styled,
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
        let link = LinkService.shared
        return InputMenu.make(target: self, schema: prefs.schema, chinese: host.chinese, traditional: prefs.traditional,
                              hasSchema: { self.host.engine?.hasSchema($0) ?? false },
                              phones: link.state.connected.map(\.displayName), canSend: link.canSend, progress: link.queueTitle)
    }

    /// IMK 把菜单项放在字典里传来。 IMK passes the menu item inside a dictionary.
    private func menuItem(_ sender: Any?) -> NSMenuItem? {
        if let item = sender as? NSMenuItem { return item }
        return (sender as? NSDictionary)?[kIMKCommandMenuItemName] as? NSMenuItem
    }

    override func showPreferences(_ sender: Any!) {
        SettingsWindow.shared.show()
    }

    @objc func toggleMode(_ sender: Any?) { host.toggleChinese() }
    @objc func openLink(_ sender: Any?) { SettingsWindow.shared.show(page: .link) }
    @objc func openAbout(_ sender: Any?) { SettingsWindow.shared.show(page: .about) }
    @objc func sendClipboard(_ sender: Any?) { LinkService.shared.sendClipboard() }
    @objc func sendFiles(_ sender: Any?) {
        DispatchQueue.main.async { LinkService.shared.chooseFiles() }
    }

    @objc func selectScheme(_ sender: Any?) {
        guard let tag = menuItem(sender)?.tag, InputScheme.all.indices.contains(tag) else { return }
        prefs.schema = InputScheme.all[tag].id
    }

    @objc func toggleTraditional(_ sender: Any?) {
        prefs.traditional.toggle()
    }
}
