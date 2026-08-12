//! `reactor_wire.v1` — the protobuf wire protocol for data/control channels.
//!
//! The types below are generated at build time by `build.rs` from the
//! vendored `.proto` sources in `proto/` (`prost-build`), then re-exported
//! through the same `common`/`data`/`control`/`model`/`platform`/`track`
//! modules this crate has always exposed — one per original `.proto` file —
//! so callers are unaffected by the switch from hand-written types.

mod generated {
    #![allow(clippy::doc_lazy_continuation)]
    include!(concat!(env!("OUT_DIR"), "/reactor_wire.v1.rs"));
}

pub mod common {
    pub use super::generated::{Error, MessageKind};
}

pub mod model {
    pub use super::generated::{Command, ModelMessage, UploadReference};
}

pub mod platform {
    pub use super::generated::{
        ClipFailed, ClipReady, FileUploaded, ModelSchema, Moderation, Ping, RequestClip,
        RequestRecording, RequestSchema,
    };
}

pub mod track {
    pub use super::generated::{
        PauseTrack, PublishTrack, PublishTrackResponse, ResumeTrack, UnpublishTrack,
    };
}

pub mod data {
    pub use super::generated::{
        data_client_message, data_server_message, DataClientMessage, DataServerMessage,
    };
}

pub mod control {
    pub use super::generated::{
        control_client_message, control_server_message, ControlClientMessage,
        ControlServerMessage,
    };
}
