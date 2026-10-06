import AppKit
import SwiftUI
import Testing
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct WindowAppearanceTests {
    @Test func themeCardsRespondToAnActualMouseClick() async throws {
        _=NSApplication.shared
        let suite="weave-theme-click-\(UUID().uuidString)",defaults=UserDefaults(suiteName:suite)!
        defer{defaults.removePersistentDomain(forName:suite)}
        let prefs=Preferences(defaults:defaults),nav=SettingsNavigation();nav.page = .appearance
        let hosting=NSHostingView(rootView:SettingsRoot(prefs:prefs,navigation:nav))
        let window=NSWindow(contentRect:NSRect(x:0,y:0,width:880,height:660),styleMask:.titled,backing:.buffered,defer:false)
        window.isReleasedWhenClosed=false;window.contentView=hosting
        defer{window.close()}
        window.makeKeyAndOrderFront(nil);hosting.layoutSubtreeIfNeeded()
        try await Task.sleep(nanoseconds:350_000_000)
        // Centre of the third theme card at the supported 880 × 660 settings size.
        let point=NSPoint(x:620,y:435)
        for type in [NSEvent.EventType.leftMouseDown,.leftMouseUp] {
            let event=try #require(NSEvent.mouseEvent(with:type,location:point,modifierFlags:[],timestamp:ProcessInfo.processInfo.systemUptime,windowNumber:window.windowNumber,context:nil,eventNumber:1,clickCount:1,pressure:1))
            window.sendEvent(event)
        }
        try await Task.sleep(nanoseconds:100_000_000)
        #expect(prefs.colorTheme == .mint)
    }
    @Test func alreadyOpenWindowsSwitchImmediatelyAndCanReturnToSystemAppearance() {
        _=NSApplication.shared
        let suite="weave-window-theme-\(UUID().uuidString)",defaults=UserDefaults(suiteName:suite)!
        defer{defaults.removePersistentDomain(forName:suite)}
        let prefs=Preferences(defaults:defaults),coordinator=WindowAppearance(prefs:prefs)
        let normal=NSWindow(contentRect:NSRect(x:0,y:0,width:400,height:300),styleMask:.titled,backing:.buffered,defer:false)
        let input=InputPanel(contentRect:NSRect(x:0,y:0,width:400,height:300),styleMask:[.titled,.nonactivatingPanel],backing:.buffered,defer:false)
        normal.isReleasedWhenClosed=false;input.isReleasedWhenClosed=false
        coordinator.track(normal);coordinator.track(input)
        prefs.appearance = .dark
        #expect(normal.appearance?.bestMatch(from:[.aqua,.darkAqua]) == .darkAqua)
        #expect(input.appearance?.bestMatch(from:[.aqua,.darkAqua]) == .darkAqua)
        prefs.appearance = .light
        #expect(normal.appearance?.bestMatch(from:[.aqua,.darkAqua]) == .aqua)
        #expect(input.appearance?.bestMatch(from:[.aqua,.darkAqua]) == .aqua)
        prefs.appearance = .system
        #expect(normal.appearance == nil && input.appearance == nil)
        #expect(!input.canBecomeKey && !input.canBecomeMain)
        normal.close();input.close()
    }
    @Test func eachThemeHasItsOwnPaletteAndTheSettingsFitTheMinimumWindowWidth() {
        _=NSApplication.shared
        #expect(Set(ColorTheme.allCases.map{Theme.palette($0).accentHex.0}).count == 4)
        let suite="weave-theme-layout-\(UUID().uuidString)",defaults=UserDefaults(suiteName:suite)!
        defer{defaults.removePersistentDomain(forName:suite)}
        let prefs=Preferences(defaults:defaults),nav=SettingsNavigation();nav.page = .appearance
        let view=NSHostingView(rootView:SettingsRoot(prefs:prefs,navigation:nav))
        view.frame=NSRect(x:0,y:0,width:780,height:560);view.layoutSubtreeIfNeeded()
        #expect(view.fittingSize.width <= 780)
    }
}
