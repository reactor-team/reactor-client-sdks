//! File upload types (`POST /sessions/{id}/uploads` + presigned PUT).

use serde::{Deserialize, Serialize};

/// `POST /sessions/{id}/uploads` request body.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateUploadRequest {
    pub name: String,
    pub size: u64,
    pub mime_type: String,
}

/// `POST /sessions/{id}/uploads` response body. The client PUTs the raw
/// file bytes to `presigned_url`, then references the upload by
/// `presigned_id` in subsequent commands.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CreateUploadResponse {
    pub presigned_id: String,
    pub presigned_url: String,
    pub path: String,
}

/// Handle to a completed upload, embeddable in command payloads via the
/// envelope's `uploads` section and sent in the `fileUploaded` runtime
/// notification.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FileRef {
    pub upload_id: String,
    pub name: String,
    pub mime_type: String,
    pub size: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn upload_round_trip() {
        let resp: CreateUploadResponse = serde_json::from_str(
            r#"{"presigned_id": "up_1", "presigned_url": "https://bucket/x?sig=1", "path": "uploads/x"}"#,
        )
        .unwrap();
        assert_eq!(resp.presigned_id, "up_1");

        let fr = FileRef {
            upload_id: resp.presigned_id,
            name: "x.bin".into(),
            mime_type: "application/octet-stream".into(),
            size: 3,
        };
        let v = serde_json::to_value(&fr).unwrap();
        assert_eq!(v["upload_id"], "up_1");
        assert_eq!(v["size"], 3);
    }
}
