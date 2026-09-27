import AppKit
import Foundation
import Testing
@testable import WeaveCore
@testable import WeaveText

/// 安装窗口在无界面的测试里也要有实在的大小（之前可能缩成零高，看起来是空窗口）。只画不装：目标目录不存在，也不点按钮。
/// The installer window must have a real size in a headless test too (it could collapse to zero height before and look
/// empty). Drawn only, never installed: the target directory does not exist and no button is pressed.
@MainActor @Suite struct InstallerWindowTests {
    private func model(package: URL? = nil, systemCopy: AppVersion? = nil,
                       opener: @escaping InstallerModel.Opener = { _, done in done(nil) }) -> InstallerModel {
        _ = NSApplication.shared
        let nowhere = FileManager.default.temporaryDirectory.appendingPathComponent("weavetext-window-\(UUID().uuidString)")
        return InstallerModel(source: nowhere.appendingPathComponent("织文输入法.app"),
                              installer: .system(inputMethodsDir: nowhere), package: .some(package),
                              systemCopy: .some(systemCopy), opener: opener)
    }

    @Test func hasANonZeroSizeInEveryState() throws {
        let model = model()
        let states: [(InstallerModel.Phase, InstallPlan)] = [
            (.ready, .fresh), (.working(.registering), .fresh), (.done(listed: true), .fresh),
            (.done(listed: false), .fresh), (.openedPackage, .fresh), (.failed(InstallError.badSignature.errorDescription!), .update(from: model.version)),
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

    @Test func aPackageNextToTheAppIsTheMainAction() {
        let pkg = URL(fileURLWithPath: "/Volumes/织文输入法/双击安装织文输入法.pkg")
        var opened: [URL] = []
        let model = model(package: pkg) { url, done in opened.append(url); done(nil) }
        #expect(model.mode == .package(pkg))
        #expect(model.actionTitle == "打开安装包")
        #expect(model.planText.contains("双击安装织文输入法.pkg") && model.planText.contains("/Library/Input Methods"))
        model.primary()
        #expect(opened == [pkg])
        #expect(model.phase == .openedPackage)
        #expect(InstallerView.blockedText.contains("仍要打开"))
    }

    @Test func aFailureToOpenShowsTheRealError() {
        let pkg = URL(fileURLWithPath: "/tmp/WeaveText.pkg")
        let error = NSError(domain: NSCocoaErrorDomain, code: NSFileReadNoSuchFileError,
                            userInfo: [NSLocalizedDescriptionKey: "文件不存在"])
        let model = model(package: pkg) { _, done in done(error) }
        model.primary()
        #expect(model.phase == .failed("没能打开安装包：文件不存在"))
    }

    @Test func withoutAPackageItCopiesForTheCurrentUser() {
        let model = model()
        #expect(model.mode == .copy)
        #expect(model.actionTitle == "安装")
        #expect(model.planText.contains("只对当前用户"))
    }

    @Test func aSystemWideCopyPointsToTheNewPackage() {
        var opened: [URL] = []
        let model = model(systemCopy: AppVersion("0.1.0")) { url, done in opened.append(url); done(nil) }
        #expect(model.mode == .systemInstalled(AppVersion("0.1.0")))
        #expect(model.actionTitle == "打开下载页")
        #expect(model.planText.contains("安装包"))
        model.primary()
        #expect(opened == [InstallerModel.releasesURL])
        #expect(model.phase == .ready)
    }
}
