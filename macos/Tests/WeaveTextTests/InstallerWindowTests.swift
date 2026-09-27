import AppKit
import Foundation
import Testing
@testable import WeaveCore
@testable import WeaveText

/// 安装窗口在无界面的测试里也要有实在的大小（之前可能缩成零高，看起来是空窗口）。只画不装：目标目录不存在，也不点按钮。
/// The installer window must have a real size in a headless test too (it could collapse to zero height before and look
/// empty). Drawn only, never installed: the target directory does not exist and no button is pressed.
@MainActor @Suite struct InstallerWindowTests {
    private func model() -> InstallerModel {
        _ = NSApplication.shared
        let nowhere = FileManager.default.temporaryDirectory.appendingPathComponent("weavetext-window-\(UUID().uuidString)")
        return InstallerModel(installer: .system(inputMethodsDir: nowhere))
    }

    @Test func hasANonZeroSizeInEveryState() throws {
        let model = model()
        let states: [(InstallerModel.Phase, InstallPlan)] = [
            (.ready, .fresh), (.working(.registering), .fresh), (.done(listed: true), .fresh),
            (.done(listed: false), .fresh), (.failed(InstallError.badSignature.errorDescription!), .update(from: model.version)),
        ]
        for (phase, plan) in states {
            model.preview(phase, plan: plan)
            let window = InstallerAppDelegate.window(model: model) {}
            let content = try #require(window.contentView)
            content.layoutSubtreeIfNeeded()
            #expect(content.fittingSize.height > 200, "\(phase)")
            #expect(content.fittingSize.width >= InstallerView.width, "\(phase)")
            #expect(window.contentLayoutRect.height > 200, "\(phase)")
            #expect(window.frame.width >= InstallerView.width, "\(phase)")
            window.close()
        }
    }

    @Test func tellsWhereToFindTheInputSource() {
        #expect(InstallerView.doneText.contains("菜单栏右上角") && InstallerView.doneText.contains("「织文拼音」"))
        #expect(InstallerView.reloginText.contains("注销后重新登录"))
    }
}
