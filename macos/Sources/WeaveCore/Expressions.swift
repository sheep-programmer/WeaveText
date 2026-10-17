import Foundation

public struct NamedExpression: Decodable, Equatable, Identifiable, Sendable {
    public let text:String
    public let name:String
    public let group:String
    /// CLDR 英文名（离线按英文搜）；颜文字为空。 The CLDR English name (offline English search); empty for kaomoji.
    public let english:String
    /// 名字的无声调拼音，音节以空格分隔；旧目录没有这个字段。 Toneless pinyin, syllables space-separated.
    public let pinyin:String
    public var id:String {text}
    private enum CodingKeys:String,CodingKey {case text,name,group,english,pinyin}
    /// 旧目录没有 english / pinyin 字段时按空串处理。 Older catalogues without the fields decode as empty.
    public init(from decoder:Decoder) throws {
        let c=try decoder.container(keyedBy:CodingKeys.self)
        text=try c.decode(String.self,forKey:.text)
        name=try c.decode(String.self,forKey:.name)
        group=try c.decode(String.self,forKey:.group)
        english=try c.decodeIfPresent(String.self,forKey:.english) ?? ""
        pinyin=try c.decodeIfPresent(String.self,forKey:.pinyin) ?? ""
    }
    public init(text:String,name:String,group:String,english:String="",pinyin:String="") {
        self.text=text;self.name=name;self.group=group;self.english=english;self.pinyin=pinyin
    }
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
