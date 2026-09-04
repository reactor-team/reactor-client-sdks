import Foundation

/// A JSON value, for the places where the shape is the model's rather than
/// this SDK's.
///
/// A command's arguments and a model's reply are whatever that model declares,
/// so there is no Swift type to name them with. `[String: Any]` would be the
/// obvious answer and is not `Sendable`, which means it cannot cross the
/// concurrency boundaries this SDK is built on. This is the same idea with a
/// type the compiler can check.
///
/// ```swift
/// try await reactor.sendCommand("set_prompt", ["prompt": "a red bicycle"])
/// ```
///
/// A caller who has modelled the command should use the `Encodable` and
/// `Decodable` overloads instead and skip this entirely — this is for the
/// ad-hoc case, which is most of a first script.
public enum JSONValue: Sendable, Hashable, Codable {

    /// A JSON string.
    case string(String)

    /// A JSON number. JSON has one numeric type; this SDK does not invent two.
    case number(Double)

    /// `true` or `false`.
    case bool(Bool)

    /// A JSON object.
    case object([String: JSONValue])

    /// A JSON array.
    case array([JSONValue])

    /// `null`.
    case null

    /// Decode from any JSON.
    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            // Before number: a JSON `true` decodes as a number on some paths,
            // and a bool that became 1 is a bool nobody can get back.
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container, debugDescription: "not a JSON value")
        }
    }

    /// Encode as plain JSON.
    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    // MARK: - Reading

    /// The string, if this is one.
    public var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    /// The number, if this is one.
    public var doubleValue: Double? {
        if case .number(let value) = self { return value }
        return nil
    }

    /// The number as an `Int`, if this is a number an `Int` can hold exactly.
    ///
    /// `nil` rather than a trap for a NaN, an infinity, or a magnitude past
    /// `Int`, and `nil` rather than a silent truncation for `1.5`. The payloads
    /// this reads are written by the model, so `Int(doubleValue)` made merely
    /// *looking* at a field like `1e300` a fatal error inside the SDK — a crash
    /// a caller cannot defend against, in a convenience accessor whose whole
    /// contract is to answer `nil` when the value is not what was asked for.
    ///
    /// Use ``doubleValue`` for a number that is genuinely fractional or larger
    /// than `Int`.
    public var intValue: Int? {
        doubleValue.flatMap(Int.init(exactly:))
    }

    /// The boolean, if this is one.
    public var boolValue: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }

    /// The object's entries, if this is an object.
    public var objectValue: [String: JSONValue]? {
        if case .object(let value) = self { return value }
        return nil
    }

    /// The array's elements, if this is an array.
    public var arrayValue: [JSONValue]? {
        if case .array(let value) = self { return value }
        return nil
    }

    /// A member of this object, or `nil` if this is not an object or has no such
    /// member.
    public subscript(key: String) -> JSONValue? {
        objectValue?[key]
    }
}

// MARK: - Writing one by hand

extension JSONValue: ExpressibleByStringLiteral {

    /// `"text"` is a JSON string.
    public init(stringLiteral value: String) { self = .string(value) }
}

extension JSONValue: ExpressibleByIntegerLiteral {

    /// `7` is a JSON number.
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
}

extension JSONValue: ExpressibleByFloatLiteral {

    /// `0.5` is a JSON number.
    public init(floatLiteral value: Double) { self = .number(value) }
}

extension JSONValue: ExpressibleByBooleanLiteral {

    /// `true` is a JSON boolean.
    public init(booleanLiteral value: Bool) { self = .bool(value) }
}

extension JSONValue: ExpressibleByNilLiteral {

    /// `nil` is JSON null.
    public init(nilLiteral: ()) { self = .null }
}

extension JSONValue: ExpressibleByArrayLiteral {

    /// `[1, 2]` is a JSON array.
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
}

extension JSONValue: ExpressibleByDictionaryLiteral {

    /// `["a": 1]` is a JSON object.
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(Dictionary(elements) { first, _ in first })
    }
}

// MARK: - Printing

extension JSONValue: CustomStringConvertible {

    /// The value as compact JSON.
    ///
    /// Without this, printing a model message gives
    /// `object(["type": Reactor.JSONValue.string("state"), …])` — the enum's
    /// synthesised description, which is unreadable exactly where a caller is
    /// most likely to be printing: an example, or a log line about a message that
    /// arrived. Found by running example 01 and reading its output.
    ///
    /// Keys are sorted, so the same value prints the same way twice.
    public var description: String {
        let encoder = JSON.encoder()
        encoder.outputFormatting.insert(.sortedKeys)
        guard let data = try? encoder.encode(self),
            let text = String(data: data, encoding: .utf8)
        else {
            // Unreachable for a JSONValue, which is JSON by construction — but a
            // description that can throw is worse than one that says so.
            return "<unprintable JSON>"
        }
        return text
    }
}
