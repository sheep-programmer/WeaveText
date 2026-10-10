import AppKit
import SwiftUI
import WeaveCore

/// 构建脚本用的自检与截图（不启动输入法服务）。 Self-test and snapshots for the build script (no IME server).
enum DevTools {
    static func snapshotExtensions(into dir:URL) throws {
        _=NSApplication.shared
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
        let suite="weave-ext-preview-\(UUID().uuidString)",defaults=UserDefaults(suiteName:suite)!
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(suite)
        defer {defaults.removePersistentDomain(forName:suite);try? FileManager.default.removeItem(at:root)}
        let prefs=Preferences(defaults:defaults),nav=SettingsNavigation(),store=ExtensionStore(root:root)
        for mode in [AppearanceMode.light,.dark] {
            prefs.appearance=mode
            for page in [SettingsPage.home,.market,.appearance] {
                nav.page=page
                try render(SettingsRoot(prefs:prefs,navigation:nav,extensionStore:store),size:NSSize(width:880,height:660),dark:mode == .dark,to:dir.appendingPathComponent("extensions-\(page.rawValue)-\(mode.rawValue).png"))
            }
        }
    }
    static func snapshotThemes(into dir:URL) throws {
        _=NSApplication.shared
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
        let suite="weave-theme-snapshot-\(UUID().uuidString)",defaults=UserDefaults(suiteName:suite)!
        defer{defaults.removePersistentDomain(forName:suite)}
        let prefs=Preferences(defaults:defaults),nav=SettingsNavigation()
        nav.page = .appearance
        let words=[Candidate(text:"你好",pinyin:"nǐ hǎo"),Candidate(text:"你",pinyin:"nǐ"),Candidate(text:"拟好",pinyin:"nǐ hǎo")]
        let state=CandidateState(preedit:"ni'hao",candidates:words,highlight:0,hasPrevious:false,hasNext:true,orientation:.horizontal,fontSize:16,expandable:true)
        for theme in ColorTheme.allCases {
            prefs.colorTheme=theme
            for mode in [AppearanceMode.light,.dark] {
                prefs.appearance=mode
                let dark=mode == .dark
                try render(SettingsRoot(prefs:prefs,navigation:nav),size:NSSize(width:880,height:660),dark:dark,to:dir.appendingPathComponent("settings-\(theme.rawValue)-\(mode.rawValue).png"))
                try render(CandidateBar(state:state,pick:{_ in},theme:theme).background(Theme.palette(theme).surface).padding(12),size:nil,dark:dark,to:dir.appendingPathComponent("candidate-\(theme.rawValue)-\(mode.rawValue).png"))
            }
        }
        let expressions=ExpressionsModel()
        let voice=VoiceModel(prefs:prefs);voice.text="你好，欢迎使用织文。识别结果确认后再上屏。"
        for mode in [AppearanceMode.light,.dark] {
            prefs.colorTheme = .fresh;prefs.appearance=mode
            try render(ExpressionsView(model:expressions,prefs:prefs),size:NSSize(width:580,height:440),dark:mode == .dark,to:dir.appendingPathComponent("expressions-\(mode.rawValue).png"))
            try render(VoiceView(model:voice,prefs:prefs),size:NSSize(width:540,height:420),dark:mode == .dark,to:dir.appendingPathComponent("voice-\(mode.rawValue).png"))
        }
    }
    static func snapshotStickers(into dir:URL,fixtures:URL) throws {
        _ = NSApplication.shared
        try FileManager.default.createDirectory(at:dir,withIntermediateDirectories:true)
        let temporary=FileManager.default.temporaryDirectory.appendingPathComponent("weave-sticker-snapshot-\(UUID().uuidString)")
        defer {try? FileManager.default.removeItem(at:temporary)}
        let store=try StickerStore(directory:temporary)
        for (name,title) in [("sample.png","开心"),("animated.gif","晚安"),("animated.webp","收到")] {
            let (item,_)=try store.importFile(fixtures.appendingPathComponent(name),name:title)
            try store.edit(item.id,name:title,group:"日常",tags:["常用"],favorite:name=="sample.png")
        }
        let model=StickerCollectionModel(directory:temporary)
        let suite="weave-sticker-snapshot-prefs-\(UUID().uuidString)", defaults=UserDefaults(suiteName:suite)!
        defer { defaults.removePersistentDomain(forName:suite) }
        let prefs=Preferences(defaults:defaults)
        for dark in [false,true] {
            prefs.appearance=dark ? .dark : .light
            for (name,size,compact,selecting) in [
                ("stickers",NSSize(width:620,height:510),false,false),
                ("stickers-minimum",NSSize(width:340,height:300),false,true),
                ("stickers-compact",NSSize(width:340,height:360),true,false)
            ] {
                model.compact=compact; model.selecting=selecting
                model.selected=selecting ? Set(model.items.map(\.id)) : []
                try render(StickerCollectionView(model:model,prefs:prefs),size:size,dark:dark,to:dir.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png"))
            }
        }
    }
    /// 用包内词库打「nihao」，首选应为「你好」；上屏「今天」后有联想。 Type "nihao" with the bundled data; the top pick
    /// must be 你好, and committing 今天 must offer predictions.
    static func selfTest() -> Bool {
        let data = Bundle.main.resourceURL!.appendingPathComponent("data").path
        let user = FileManager.default.temporaryDirectory.appendingPathComponent("weavetext-selftest-\(getpid())")
        try? FileManager.default.createDirectory(at: user, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: user) }
        guard let e = WeaveSession(dataDir: data, userDir: user.path) else {
            print("selftest: engine failed to load \(data)")
            return false
        }
        var ok = true
        for scheme in InputScheme.all where !e.hasSchema(scheme.id) {
            print("selftest: no dictionary for \(scheme.id)")
            ok = false
        }
        e.setSchema("pinyin")
        "nihao".forEach { _ = e.input($0) }
        let first = e.snapshot().candidates.first?.text ?? "-"
        print("selftest: nihao → \(first)")
        e.clear()
        "v(128+32)*4".forEach { _ = e.input($0) }
        let calc = e.snapshot().candidates.first?.text ?? "-"
        print("selftest: v(128+32)*4 → \(calc)")
        e.clear()
        e.setOption("features.calculator", false)
        "v(128+32)*4".forEach { _ = e.input($0) }
        let calculatorOff = !e.snapshot().candidates.contains { $0.text == "640" }
        print("selftest: calculator disabled → \(calculatorOff)")
        e.clear(); e.setOption("features.calculator", true)
        "v2+3".forEach { _ = e.input($0) }
        let calculatorBack = e.snapshot().candidates.first?.text == "5"
        ok = ok && calculatorOff && calculatorBack
        e.clear()
        // 联想表随包：写了一串字（「我们今天」）后应有联想；只有一个词时不联想。
        // The prediction table ships: after a run of text (我们今天) there must be predictions; one bare word gives none.
        "womenjintian".forEach { _ = e.input($0) }
        if let i = e.snapshot().candidates.firstIndex(where: { $0.text == "我们今天" }) { e.select(i) }
        let next = e.snapshot()
        print("selftest: 我们今天 → \(next.candidates.prefix(5).map(\.text).joined(separator: " "))")
        e.setLearning(false)
        e.setOption("candidates.prediction", false)
        for (input, expected) in [("xiuba", "修吧"), ("mingtinajian", "明天见"), ("shagnhai", "上海")] {
            e.clear(); e.setContext(nil)
            input.forEach { _ = e.input($0) }
            let actual = e.snapshot().candidates.first?.text ?? "-"
            print("selftest: \(input) → \(actual)")
            ok = ok && actual == expected
        }
        return ok && first == "你好" && calc == "640" && next.predicting && !next.candidates.isEmpty
    }

    /// 把候选窗与设置页画成 PNG。 Render the candidate bar and the settings pages to PNG.
    static func snapshot(into dir: URL) throws {
        _ = NSApplication.shared
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let words = ["你好", "你", "妮", "拟好", "泥", "尼", "逆"]
        let horizontal = CandidateState(preedit: "ni'hao", candidates: words.map { Candidate(text: $0) }, highlight: 0,
                                        hasPrevious: false, hasNext: true, orientation: .horizontal, fontSize: 16)
        var vertical = horizontal
        vertical.orientation = .vertical
        vertical.highlight = 1
        vertical.hasPrevious = true
        vertical.candidates[3].pinyin = "nǐ hǎo"
        let money = CandidateState(
            preedit: "v1234",
            candidates: [Candidate(text: "1234"), Candidate(text: "壹仟贰佰叁拾肆元整", comment: "大写金额"),
                         Candidate(text: "一千二百三十四", comment: "中文数字"), Candidate(text: "1,234", comment: "千分位")],
            highlight: 0, hasPrevious: false, hasNext: false, orientation: .horizontal, fontSize: 16)
        // 展开后的全部候选。 The expanded list of all candidates.
        let many = (0..<42).map { Candidate(text: ["你好", "你", "妮", "拟好", "泥", "尼", "逆", "腻", "匿", "溺", "昵", "拟"][$0 % 12], pinyin:["nǐ hǎo","nǐ","nī","nǐ hǎo","ní","ní","nì","nì","nì","nì","nì","nǐ"][$0 % 12]) }
        var expandedState = horizontal
        expandedState.expandable = true
        expandedState.expanded = many
        expandedState.expandedMore = true
        var collapsedState = horizontal
        collapsedState.expandable = true
        for (name, state) in [("candidates-expandable", collapsedState), ("candidates-expanded", expandedState)] {
            for dark in [false, true] {
                let view = CandidateBar(state: state, pick: { _ in })
                    .background(Color(nsColor: .windowBackgroundColor))
                    .clipShape(RoundedRectangle(cornerRadius: CandidatePanel.cornerRadius))
                    .padding(12)
                try render(view, size: nil, dark: dark, to: dir.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png"))
            }
        }
        // 候选上方的完整声调注音。 Full toned pronunciation above candidates.
        var pinyin = horizontal
        for (i, hint) in ["nǐ hǎo", "nǐ", "nī", "nǐ hǎo", "ní", "ní", "nì"].enumerated() { pinyin.candidates[i].pinyin = hint }
        for (name, state) in [("candidates-horizontal", horizontal), ("candidates-vertical", vertical),
                              ("candidates-comments", money), ("candidates-pinyin", pinyin)] {
            for dark in [false, true] {
                let view = CandidateBar(state: state, pick: { _ in })
                    .background(Color(nsColor: .windowBackgroundColor))
                    .clipShape(RoundedRectangle(cornerRadius: CandidatePanel.cornerRadius))
                    .padding(12)
                try render(view, size: nil, dark: dark, to: dir.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png"))
            }
        }
        let hand=HandwritingModel();hand.candidates=[Candidate(text:"十"),Candidate(text:"土"),Candidate(text:"干")]
        for dark in [false,true] {
            try render(HandwritingView(model:hand),size:NSSize(width:520,height:360),dark:dark,to:dir.appendingPathComponent("handwriting-\(dark ? "dark" : "light").png"))
        }
        let cloudCandidates=CandidateState(preedit:"shi'jian'fu'za'du",candidates:[Candidate(text:"时间复杂度",cloud:true),Candidate(text:"时间"),Candidate(text:"实践")],highlight:0,hasPrevious:false,hasNext:false,orientation:.horizontal,fontSize:16)
        for dark in [false,true] {
            let view=CandidateBar(state:cloudCandidates,pick:{_ in}).background(Color(nsColor:.windowBackgroundColor)).padding(12)
            try render(view,size:nil,dark:dark,to:dir.appendingPathComponent("cloud-candidates-\(dark ? "dark" : "light").png"))
        }
        // 互联页与配对窗口用示例状态画；偏好用一次性的域，不碰真实设置。 The link page and pairing sheet use a sample
        // state; preferences live in a throwaway domain, never the real settings.
        let link = LinkService.shared
        link.preview(linkSample())
        let suite = "com.weavetext.inputmethod.WeaveText.snapshot"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = Preferences(defaults: defaults)
        let pluginPreview=PluginCenter(directory:EngineHost.userDirectory(),defaults:defaults,prefs:prefs)
        let previewData=Data(#"[{"id":"org.example.speech","name":"实时语音","description":"支持流式识别，录音结束后整理标点。","version":"1.2.0","kind":"speech","configSchema":[{"key":"url","label":"服务地址","type":"text","helpText":"填写你自己的语音服务地址。"},{"key":"token","label":"访问密钥","type":"password","required":true}],"networkHosts":["asr.example.com"],"unrestrictedNetwork":false},{"id":"org.example.custom","name":"自定义语音","description":"可配置服务与语言。","version":"0.3.0","kind":"speech","configSchema":[],"networkHosts":[],"unrestrictedNetwork":true}]"#.utf8)
        let examplePlugins=try JSONDecoder().decode([PluginInfo].self,from:previewData)
        let repo=try GitHubRepository.parse("example/voice-plugins")
        pluginPreview.preview(examplePlugins,catalogs:[GitHubCatalog(repository:repo,isPrivate:true,plugins:[.source("streaming-asr",[]),.release(1,"custom-voice.zip","v1.2",1000,nil)])])
        let toolsPreview=ToolsModel(directory:EngineHost.userDirectory())
        toolsPreview.preview(history:[ContentItem(text:"已固定：常用地址",time:Date(),pinned:true),ContentItem(text:"这是从手机同步过来的剪贴板内容。",time:Date())],phrases:[ContentItem(text:"好的，我稍后回复。",time:Date())])
        prefs.linkEnabled = true
        var pairing = linkSample()
        pairing.pairing = LinkPairing(json: [
            "code": "482913", "expiresIn": 120, "addrs": ["192.168.1.8:47811", "10.0.0.5:47811"],
            "uri": "weavelink://pair?v=1&id=4f2a9c0d1e7b&n=MacBook&p=mac&a=192.168.1.8%3A47811&c=482913",
        ])
        for dark in [false, true] {
            let sheet = PairingSheet(link: link).background(Color(nsColor: .windowBackgroundColor))
            link.preview(pairing)
            try render(sheet, size: nil, dark: dark, to: dir.appendingPathComponent("link-pairing-\(dark ? "dark" : "light").png"))
        }
        link.preview(linkSample())
        let nav = SettingsNavigation()
        for page in SettingsPage.allCases {
            nav.page = page
            let root = SettingsRoot(prefs: prefs, navigation: nav,pluginModel:pluginPreview,toolsModel:toolsPreview).tint(Theme.accent)
            try render(root, size: NSSize(width: 880, height: 660), dark: false,
                       to: dir.appendingPathComponent("settings-\(page.rawValue).png"))
        }
        for dark in [false,true] {
            try render(PluginsPage(model:pluginPreview,prefs:prefs).tint(Theme.accent),size:NSSize(width:620,height:1000),dark:dark,to:dir.appendingPathComponent("plugins-expanded-\(dark ? "dark" : "light").png"))
            prefs.clipboardRecord=true
            try render(ToolsPage(model:toolsPreview,prefs:prefs).tint(Theme.accent),size:NSSize(width:620,height:820),dark:dark,to:dir.appendingPathComponent("tools-\(dark ? "dark" : "light").png"))
        }
        let expressions=ExpressionsModel()
        for dark in [false,true] {
            expressions.kind="emoji"
            try render(ExpressionsView(model:expressions),size:NSSize(width:580,height:420),dark:dark,to:dir.appendingPathComponent("expressions-emoji-\(dark ? "dark" : "light").png"))
            expressions.kind="kaomoji"
            try render(ExpressionsView(model:expressions),size:NSSize(width:580,height:420),dark:dark,to:dir.appendingPathComponent("expressions-kaomoji-\(dark ? "dark" : "light").png"))
        }
        try render(SchemesPage(prefs:prefs).tint(Theme.accent),size:NSSize(width:620,height:1800),dark:false,to:dir.appendingPathComponent("schemes-full.png"))
        // 词库页：示例的专业词库与热词状态（只画，不下载）。 The dictionary pages with sample pack and hot-word states.
        let host = EngineHost.shared
        let sample = host.packs.packs
        var states: [String: PackState] = [:]
        if sample.count > 3 {
            states[sample[0].id] = .installed
            states[sample[1].id] = .downloading(done: sample[1].bytes / 2)
            states[sample[2].id] = .failed(DictPackStore.failure)
        }
        host.packs.preview(states)
        host.cloud.preview(CloudStatus(enabled: true, words: 1280, checkedAt: Date(timeIntervalSince1970: 1_790_000_000),
                                       attached: true))
        nav.page = .dictionary
        try render(SettingsRoot(prefs: prefs, navigation: nav).tint(Theme.accent), size: NSSize(width: 720, height: 560),
                   dark: false, to: dir.appendingPathComponent("settings-dictionary-cloud-on.png"))
        for dark in [false, true] {
            try render(DictPacksPage(store: host.packs).tint(Theme.accent), size: NSSize(width: 560, height: 900), dark: dark,
                       to: dir.appendingPathComponent("settings-dictionary-packs-\(dark ? "dark" : "light").png"))
        }
        let predicting = CandidateState(preedit: "", candidates: ["晚上", "早上", "下午", "的", "我们"].map { Candidate(text: $0) },
                                        highlight: -1, hasPrevious: false, hasNext: false, orientation: .horizontal,
                                        fontSize: 16, hint: "联想")
        for dark in [false, true] {
            let view = CandidateBar(state: predicting, pick: { _ in })
                .background(Color(nsColor: .windowBackgroundColor))
                .clipShape(RoundedRectangle(cornerRadius: CandidatePanel.cornerRadius))
                .padding(12)
            try render(view, size: nil, dark: dark, to: dir.appendingPathComponent("candidates-predictions-\(dark ? "dark" : "light").png"))
        }
        nav.page = .link
        try render(SettingsRoot(prefs: prefs, navigation: nav).tint(Theme.accent), size: NSSize(width: 720, height: 900),
                   dark: true, to: dir.appendingPathComponent("settings-link-dark.png"))
        nav.page = .about
        try render(SettingsRoot(prefs: prefs, navigation: nav).tint(Theme.accent), size: NSSize(width: 720, height: 1500),
                   dark: false, to: dir.appendingPathComponent("settings-about-full.png"))
        // 截图工具由顶层代码在主线程上调用。 The snapshot tool is called from top-level code on the main thread.
        try MainActor.assumeIsolated { try snapshotInstaller(into: dir) }
    }

    /// 安装窗口的各个状态（指向一个不存在的目录，只画不装）。 The installer window's states, pointed at a directory that
    /// does not exist; drawn only, never installed.
    @MainActor private static func snapshotInstaller(into dir: URL) throws {
        let nowhere = FileManager.default.temporaryDirectory.appendingPathComponent("weavetext-snapshot-\(getpid())")
        let model = InstallerModel(installer: .system(inputMethodsDir: nowhere))
        let v = model.version
        let states: [(String, InstallerModel.Phase, InstallPlan)] = [
            ("fresh", .ready, .fresh),
            ("update", .ready, .update(from: AppVersion("0.0.9", build: "90"))),
            ("reinstall", .ready, .reinstall),
            ("newer", .ready, .newerInstalled(AppVersion("9.0.0"))),
            ("working", .working(.registering), .fresh),
            ("done", .done(listed: true), .fresh),
            ("done-relogin", .done(listed: false), .fresh),
            ("failed", .failed(InstallError.replaceFailed("Operation not permitted").errorDescription!), .update(from: v)),
        ]
        for (name, phase, plan) in states {
            model.preview(phase, plan: plan)
            for dark in [false, true] where dark == false || name == "fresh" || name == "done" {
                try render(InstallerView(model: model).background(Color(nsColor: .windowBackgroundColor)), size: nil,
                           dark: dark, to: dir.appendingPathComponent("installer-\(name)-\(dark ? "dark" : "light").png"))
            }
        }
    }

    private static func linkSample() -> LinkState {
        var s = LinkState()
        s.running = true
        s.info.fingerprint = "AB12-CD34-EF56-7890"
        s.trusted = [
            LinkPeer(id: "a", name: "Pixel 9", platform: "android", connected: true, nearby: true),
            LinkPeer(id: "b", name: "家里的平板", platform: "android", connected: false, nearby: false),
        ]
        s.nearby = [LinkNearby(id: "c", name: "会议室的手机", platform: "android")]
        s.transfers = [
            LinkTransfer(id: "t1", name: "旅行照片.jpg", incoming: true, peer: "Pixel 9", done: 3_300_000, size: 7_800_000),
            LinkTransfer(id: "t2", name: "季度报告.pdf", incoming: false, peer: "Pixel 9", done: 1_200_000, size: 1_200_000,
                         state: .done),
        ]
        return s
    }

    private static func render<V: View>(_ view: V, size: NSSize?, dark: Bool, to url: URL) throws {
        let hosting = NSHostingView(rootView: view)
        let frame = NSRect(origin: .zero, size: size ?? hosting.fittingSize)
        let window = NSWindow(contentRect: frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        window.contentView = hosting
        hosting.frame = frame
        // 让 SwiftUI 完成几轮布局。 Let SwiftUI settle a few layout passes.
        for _ in 0..<5 {
            hosting.layoutSubtreeIfNeeded()
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        guard let rep = hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds) else { return }
        hosting.cacheDisplay(in: hosting.bounds, to: rep)
        try rep.representation(using: .png, properties: [:])?.write(to: url)
    }
}
