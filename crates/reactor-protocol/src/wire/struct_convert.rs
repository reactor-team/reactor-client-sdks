//! Conversions between `serde_json::Value` and `prost_types::Struct`,
//! mirroring `reactor-runtime`'s `dict_to_struct`/`struct_to_dict`
//! (`src/reactor_runtime/protocol/common.py`).

use prost_types::value::Kind;
use prost_types::{ListValue, Struct, Value as ProstValue};
use serde_json::{Map, Number, Value};

/// Converts a JSON value into a protobuf `Struct`. Returns `None` if `value`
/// is not a JSON object — a `Struct`'s fields are a string-keyed map, so it
/// cannot represent a bare scalar or array at the top level.
pub fn value_to_struct(value: Value) -> Option<Struct> {
    match value {
        Value::Object(map) => Some(object_to_struct(map)),
        _ => None,
    }
}

/// Converts a protobuf `Struct` back into a JSON object.
pub fn struct_to_value(s: Struct) -> Value {
    Value::Object(
        s.fields
            .into_iter()
            .map(|(k, v)| (k, prost_value_to_value(v)))
            .collect(),
    )
}

fn object_to_struct(map: Map<String, Value>) -> Struct {
    Struct {
        fields: map
            .into_iter()
            .map(|(k, v)| (k, value_to_prost_value(v)))
            .collect(),
    }
}

fn value_to_prost_value(value: Value) -> ProstValue {
    let kind = match value {
        Value::Null => Kind::NullValue(0),
        Value::Bool(b) => Kind::BoolValue(b),
        Value::Number(n) => Kind::NumberValue(n.as_f64().unwrap_or(0.0)),
        Value::String(s) => Kind::StringValue(s),
        Value::Array(items) => Kind::ListValue(ListValue {
            values: items.into_iter().map(value_to_prost_value).collect(),
        }),
        Value::Object(map) => Kind::StructValue(object_to_struct(map)),
    };
    ProstValue { kind: Some(kind) }
}

fn prost_value_to_value(value: ProstValue) -> Value {
    match value.kind {
        None | Some(Kind::NullValue(_)) => Value::Null,
        Some(Kind::BoolValue(b)) => Value::Bool(b),
        Some(Kind::NumberValue(n)) => Number::from_f64(n)
            .map(Value::Number)
            .unwrap_or(Value::Null),
        Some(Kind::StringValue(s)) => Value::String(s),
        Some(Kind::ListValue(l)) => {
            Value::Array(l.values.into_iter().map(prost_value_to_value).collect())
        }
        Some(Kind::StructValue(s)) => struct_to_value(s),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn roundtrip_scalars_and_nesting() {
        let original = json!({
            "prompt": "a cat",
            "strength": 0.5,
            "enabled": true,
            "tags": ["a", "b"],
            "nested": {"x": 1.0},
            "missing": null,
        });
        let s = value_to_struct(original.clone()).unwrap();
        let back = struct_to_value(s);
        assert_eq!(original, back);
    }

    #[test]
    fn non_object_top_level_is_rejected() {
        assert!(value_to_struct(json!(1)).is_none());
        assert!(value_to_struct(json!([1, 2])).is_none());
        assert!(value_to_struct(json!("x")).is_none());
    }

    #[test]
    fn empty_object_roundtrips() {
        let s = value_to_struct(json!({})).unwrap();
        assert_eq!(struct_to_value(s), json!({}));
    }
}
