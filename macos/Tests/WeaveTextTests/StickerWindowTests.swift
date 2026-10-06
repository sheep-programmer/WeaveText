import AppKit
import Foundation
import SwiftUI
import Testing
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct StickerWindowTests {
    private var fixtures: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("tests/fixtures/stickers")
    }
    private func library() throws -> (URL, StickerStore, StickerCollectionModel) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("weave-sticker-window-\(UUID().uuidString)", isDirectory: true)
        let store = try StickerStore(directory: directory)
        for name in ["sample.png", "animated.gif", "animated.webp"] {
            _ = try store.importData(Data(contentsOf: fixtures.appendingPathComponent(name)), name: name)
        }
        return (directory, store, StickerCollectionModel(store: store))
    }
    private func prefs() throws -> (UserDefaults, String, Preferences) {
        let suite = "weave-sticker-window-prefs-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        return (defaults, suite, Preferences(defaults: defaults))
    }
    private func waitForDeletion(_ model: StickerCollectionModel) async {
        let deadline = Date().addingTimeInterval(3)
        while model.deleting && Date() < deadline { await Task.yield() }
        #expect(!model.deleting)
    }

    @Test func singleContextDeletionNeedsConfirmationAndCancelPreservesSelectionAndOriginals() throws {
        let (directory, store, model) = try library()
        defer { try? FileManager.default.removeItem(at: directory) }
        let all = store.list()
        let target = try #require(all.first)
        let selected = try #require(all.last)
        model.selected = [selected.id]
        model.requestDeletion([target.id])
        let request = try #require(model.deletionRequest)
        #expect(request.ids == [target.id] && request.count == 1 && model.confirmDelete)
        #expect(model.selected == [selected.id] && store.list().count == 3)
        model.cancelDeletion()
        #expect(model.deletionRequest == nil && !model.confirmDelete && !model.deleting)
        #expect(model.selected == [selected.id] && store.list().count == 3)
        for item in all {
            let original = try Data(contentsOf: store.file(item))
            let fixture = try Data(contentsOf: fixtures.appendingPathComponent(item.name))
            #expect(original == fixture)
        }
    }

    @Test func bulkConfirmationKeepsItsOriginalIDsDespiteSelectionAndFilterChanges() async throws {
        let (directory, store, model) = try library()
        defer { try? FileManager.default.removeItem(at: directory) }
        let all = store.list()
        let captured = Set(all.prefix(2).map(\.id))
        let survivor = try #require(all.last)
        model.selected = captured
        model.deleteSelected()
        let request = try #require(model.deletionRequest)
        #expect(request.ids == captured && request.count == 2 && store.list().count == 3)
        model.query = survivor.name
        model.selected = [survivor.id]
        model.deleteSelected() // A repeated request cannot replace the pending confirmation.
        #expect(model.deletionRequest?.id == request.id && model.deletionRequest?.ids == captured)
        // SwiftUI can set the presentation binding false before running the destructive button.
        model.confirmDelete = false
        model.confirmDeletion(request)
        #expect(model.deleting)
        model.confirmDeletion(request)
        model.requestDeletion([survivor.id])
        #expect(model.deletionRequest == nil)
        await waitForDeletion(model)
        #expect(Set(store.list().map(\.id)) == [survivor.id])
        #expect(model.selected == [survivor.id])
        #expect(FileManager.default.fileExists(atPath: try store.file(survivor).path))
        for item in all where captured.contains(item.id) {
            #expect(!FileManager.default.fileExists(atPath: try store.file(item).path))
        }
    }

    @Test func staleConfirmationAfterCancellationCannotDeleteANewerRequest() async throws {
        let (directory, store, model) = try library()
        defer { try? FileManager.default.removeItem(at: directory) }
        let items = store.list()
        let first = items[0], second = items[1]
        model.requestDeletion([first.id])
        let cancelled = try #require(model.deletionRequest)
        model.cancelDeletion()
        model.requestDeletion([second.id])
        let active = try #require(model.deletionRequest)
        model.confirmDeletion(cancelled)
        #expect(!model.deleting && model.deletionRequest?.id == active.id && store.list().count == 3)
        model.confirmDeletion(active)
        await waitForDeletion(model)
        #expect(store.list().contains(where: { $0.id == first.id }))
        #expect(!store.list().contains(where: { $0.id == second.id }))
    }

    @Test func minimumWidthLayoutAndRealPanelResizeRemainFlexibleInBothDensities() throws {
        _ = NSApplication.shared
        let (directory, store, model) = try library()
        let (defaults, suite, prefs) = try prefs()
        defer { try? FileManager.default.removeItem(at: directory); defaults.removePersistentDomain(forName: suite) }
        for item in store.list() {
            try store.edit(item.id, name: String(repeating: "很长的表情名称", count: 10),
                           group: String(repeating: "很长的分组", count: 10), tags: [], favorite: false)
        }
        model.reload(); model.selecting = true; model.selected = Set(model.items.map(\.id))
        let controller = StickerWindow(model: model, defaults: defaults, prefs: prefs)
        let panel = controller.makePanel()
        defer { panel.close() }
        #expect(panel.styleMask.contains(.resizable) && panel.styleMask.contains(.nonactivatingPanel))
        #expect(panel.contentMinSize == NSSize(width: 340, height: 300))
        let hosting = try #require(panel.contentView as? NSHostingView<StickerCollectionView>)
        #expect(hosting.sizingOptions.isEmpty)
        for compact in [false, true] {
            model.compact = compact
            for size in [NSSize(width: 340, height: 300), NSSize(width: 460, height: 420),
                         NSSize(width: 900, height: 720), NSSize(width: 340, height: 550)] {
                panel.setContentSize(size)
                hosting.layoutSubtreeIfNeeded()
                #expect(abs(hosting.bounds.width - size.width) < 1 && abs(hosting.bounds.height - size.height) < 1)
                #expect(hosting.fittingSize.width <= size.width + 1)
                #expect(hosting.fittingSize.height <= size.height + 1)
            }
        }
        #expect(StickerWindowLayout.usesNarrowHeader(width: 340, compact: false))
        #expect(!StickerWindowLayout.usesNarrowHeader(width: 900, compact: false))
    }

    @Test func compactToggleAndReopeningPreserveManualFrameAndPosition() throws {
        _ = NSApplication.shared
        let (directory, store, model) = try library()
        let (defaults, suite, prefs) = try prefs()
        defer { try? FileManager.default.removeItem(at: directory); defaults.removePersistentDomain(forName: suite) }
        let controller = StickerWindow(model: model, defaults: defaults, prefs: prefs)
        let panel = controller.makePanel()
        panel.setContentSize(NSSize(width: 472, height: 408))
        panel.setFrameOrigin(NSPoint(x: 160, y: 180))
        let manual = panel.frame
        controller.toggleCompact()
        #expect(model.compact && panel.frame == manual)
        controller.toggleCompact()
        #expect(!model.compact && panel.frame == manual)
        panel.setFrame(NSRect(x: 140, y: 150, width: 530, height: 460), display: false)
        let resized = panel.frame
        controller.windowDidResize(Notification(name: NSWindow.didResizeNotification, object: panel))
        controller.windowDidMove(Notification(name: NSWindow.didMoveNotification, object: panel))
        panel.close()
        let reopened = StickerWindow(model: StickerCollectionModel(store: store), defaults: defaults, prefs: prefs)
        let restored = reopened.makePanel()
        defer { restored.close() }
        #expect(restored.frame == resized)
        #expect(restored.contentMinSize == NSSize(width: 340, height: 300))
    }
}
