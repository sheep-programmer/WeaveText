import Foundation

public struct NamedExpression: Decodable, Equatable, Identifiable, Sendable {
    public let text:String
    public let name:String
    public let group:String
    public var id:String {text}
}
public struct ExpressionCatalog: Decodable, Sendable {
    public let format:Int
    public let emojiVersion:String
    public let emoji:[NamedExpression]
    public let kaomoji:[NamedExpression]
    public let skinToneBases:Set<String>
    public static func load(_ url:URL) throws -> Self {
        let catalog=try JSONDecoder().decode(Self.self,from:Data(contentsOf:url))
        guard catalog.format==1 else {throw PluginFailure("表情目录版本不支持")}
        return catalog
    }
}
