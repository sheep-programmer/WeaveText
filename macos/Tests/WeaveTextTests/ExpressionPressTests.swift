import AppKit
import Testing
@testable import WeaveText

@MainActor @Suite struct ExpressionPressTests {
    @Test func aHeldPressShowsItsNameWithoutInsertingButATapInserts() async throws {
        _=NSApplication.shared
        let tip=ExpressionTip(),cell=ExpressionCellView(frame:NSRect(x:0,y:0,width:60,height:60))
        let window=NSWindow(contentRect:NSRect(x:100,y:100,width:60,height:60),styleMask:.borderless,backing:.buffered,defer:false)
        window.contentView=cell;cell.tip=tip;cell.text="😀";cell.name="嘿嘿"
        var insertions=0;cell.insert={insertions+=1}
        func event(_ type:NSEvent.EventType)->NSEvent {NSEvent.mouseEvent(with:type,location:NSPoint(x:20,y:20),modifierFlags:[],timestamp:0,windowNumber:window.windowNumber,context:nil,eventNumber:0,clickCount:1,pressure:1)!}
        cell.mouseDown(with:event(.leftMouseDown))
        try await Task.sleep(nanoseconds:500_000_000)
        #expect(tip.text=="嘿嘿" && insertions==0)
        cell.mouseUp(with:event(.leftMouseUp));#expect(tip.text.isEmpty && insertions==0)
        cell.mouseDown(with:event(.leftMouseDown));cell.mouseUp(with:event(.leftMouseUp))
        #expect(insertions==1 && tip.text.isEmpty)
    }
}
