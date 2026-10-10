import Foundation
import Testing
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct DictionaryDownloadProgressTests {
    private func row(bytes: Int64, done: Int64?, download: PackDownload? = nil) -> PackRow {
        PackRow(pack: DictPack(id: "abc", name: "测试", bytes: bytes, sha256: ""),
                state: .downloading(done: done), download: download, install: {}, cancel: {}, remove: {})
    }

    @Test func unknownSizeShowsReceivedBytesAndIndeterminateProgress() {
        let row = row(bytes: 0, done: 512)
        #expect(row.note == "下载中 · 512 B · 大小未知")
        #expect(row.progressFraction == nil)
        #expect(!row.note.contains("/ 0"))
        #expect(row.pack.summary.contains("大小未知"))
        #expect(PackFormat.size(0) == "0 B" && PackFormat.size(1) == "1 B")
    }

    @Test func connectingKeepsTheKnownSizeVisibleAndTheIndicatorIndeterminate() {
        let row = row(bytes: 2048, done: nil)
        #expect(row.note == "正在连接… · 2 KB")
        #expect(row.progressFraction == nil)
    }

    @Test func responseLengthCanSupplyTheProgressTotalAndIdentifyTheMirror() {
        let download = PackDownload(source: URL(string: "https://mirror.invalid/file")!, attempt: 2,
                                    sourceCount: 4, progress: FetchProgress(receivedBytes: 1024, totalBytes: 2048))
        let row = row(bytes: 0, done: 1024, download: download)
        #expect(row.note == "下载中 · 1 KB / 2 KB")
        #expect(row.progressFraction == 0.5)
        #expect(download.sourceLabel == "镜像 1/3 · mirror.invalid")
    }

    @Test func catalogSizeWinsOverMirrorLengthAndProgressStaysWithinTheBar() {
        let download = PackDownload(source: URL(string: "https://example.invalid/file")!, attempt: 1,
                                    sourceCount: 2, progress: FetchProgress(receivedBytes: 1, totalBytes: 999))
        #expect(row(bytes: 3, done: 1, download: download).totalBytes == 3)
        #expect(row(bytes: 3, done: 4).progressFraction == 1)
        #expect(row(bytes: 3, done: -1).progressFraction == 0)
        #expect(download.sourceLabel == "直连 · example.invalid")
    }
}
