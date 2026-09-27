import Carbon
import Foundation

/// 向系统登记并启用输入源（install.sh 调用 `WeaveText --register`）。
/// Registers and enables the input source with the system (install.sh runs `WeaveText --register`).
enum Registration {
    static let bundleID = "com.weavetext.inputmethod.WeaveText"
    static let modeID = "com.weavetext.inputmethod.WeaveText.pinyin"

    static func register(bundleURL: URL) -> Bool {
        let status = TISRegisterInputSource(bundleURL as CFURL)
        guard status == noErr else {
            fputs("TISRegisterInputSource failed: \(status)\n", stderr)
            return false
        }
        let all = sources()
        guard !all.isEmpty else {
            fputs("registered, but no input source with bundle id \(bundleID) was found\n", stderr)
            return false
        }
        for s in all where bool(s, kTISPropertyInputSourceIsEnableCapable) { TISEnableInputSource(s) }
        if let mode = all.first(where: { string(s: $0, kTISPropertyInputSourceID) == modeID }),
           bool(mode, kTISPropertyInputSourceIsSelectCapable) {
            TISSelectInputSource(mode)
        }
        return true
    }

    /// 卸载前停用。 Disable before uninstalling.
    static func disable() -> Bool {
        let all = sources()
        for s in all { TISDisableInputSource(s) }
        return !all.isEmpty
    }

    private static func sources() -> [TISInputSource] {
        let filter = [kTISPropertyBundleID as String: bundleID] as CFDictionary
        return (TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource]) ?? []
    }

    private static func string(s: TISInputSource, _ key: CFString) -> String? {
        guard let p = TISGetInputSourceProperty(s, key) else { return nil }
        return Unmanaged<CFString>.fromOpaque(p).takeUnretainedValue() as String
    }

    private static func bool(_ s: TISInputSource, _ key: CFString) -> Bool {
        guard let p = TISGetInputSourceProperty(s, key) else { return false }
        return CFBooleanGetValue(Unmanaged<CFBoolean>.fromOpaque(p).takeUnretainedValue())
    }
}
