//! A step result crossing the C boundary: the completion hands it to the host,
//! every accessor reads it in place, and the host frees it.

use super::*;

fn sample_output() -> reactor_core::step::StepOutput {
    use reactor_core::protocol::session::TrackKind;
    use reactor_core::protocol::wire::v1::step::FrameEncoding;
    use reactor_core::step::{DecodedFrame, StepFile, StepOutput, TrackOutput};
    StepOutput {
        step: 4,
        tracks: vec![
            TrackOutput {
                track: "main_video".into(),
                kind: TrackKind::Video,
                frames: vec![DecodedFrame {
                    encoding: FrameEncoding::Raw,
                    dtype: "uint8".into(),
                    shape: vec![1, 2, 3],
                    data: vec![1, 2, 3, 4, 5, 6],
                    metadata: Some(b"tag".to_vec()),
                    capture_time_us: Some(99),
                }],
                rate: 24.0,
            },
            TrackOutput {
                track: "main_audio".into(),
                kind: TrackKind::Audio,
                frames: vec![DecodedFrame {
                    encoding: FrameEncoding::Raw,
                    dtype: "int16".into(),
                    shape: vec![1, 2],
                    data: vec![0, 1, 0, 2],
                    metadata: None,
                    capture_time_us: None,
                }],
                rate: 48_000.0,
            },
        ],
        elapsed: 0.5,
        files: vec![
            StepFile {
                name: "depth.npy".into(),
                content_type: "application/octet-stream".into(),
                size: 4096,
            },
            StepFile {
                name: "caption.txt".into(),
                content_type: "text/plain".into(),
                size: 12,
            },
        ],
        error: None,
    }
}

/// The whole hand-over: the completion receives a result it owns, every
/// accessor reads it in place, and freeing it is the host's call.
#[test]
fn a_resolved_step_hands_the_host_a_result_it_reads_and_frees() {
    let recorded = StepRecorded::new();
    let completion = StepCompletion {
        f: Some(record_step),
        userdata: &recorded as *const StepRecorded as *mut c_void,
        gate: Arc::new(CallbackGate::new()),
    };
    completion.resolve(Ok(ReactorStepResult::from(sample_output())));
    let (ok, result, error) = recorded.wait();
    assert!(ok);
    assert!(error.is_empty());
    let result = result as *mut ReactorStepResult;
    assert!(!result.is_null());

    unsafe {
        let text = |p: *const c_char| CStr::from_ptr(p).to_str().unwrap().to_owned();
        assert_eq!(reactor_step_result_step(result), 4);
        assert_eq!(reactor_step_result_elapsed(result), 0.5);
        assert!(reactor_step_result_error(result).is_null());
        assert_eq!(reactor_step_result_file_count(result), 2);
        assert_eq!(text(reactor_step_result_file_name(result, 0)), "depth.npy");
        assert_eq!(
            text(reactor_step_result_file_content_type(result, 0)),
            "application/octet-stream"
        );
        assert_eq!(reactor_step_result_file_size(result, 0), 4096);
        assert_eq!(
            text(reactor_step_result_file_name(result, 1)),
            "caption.txt"
        );
        assert_eq!(
            reactor_step_result_file_name(result, 0),
            reactor_step_result_file_name(result, 0),
            "a file's name is borrowed, the same pointer every read"
        );
        assert!(reactor_step_result_file_name(result, 2).is_null());
        assert!(reactor_step_result_file_content_type(result, 2).is_null());
        assert_eq!(reactor_step_result_file_size(result, 2), 0);
        assert_eq!(reactor_step_result_track_count(result), 2);
        assert_eq!(
            text(reactor_step_result_track_name(result, 0)),
            "main_video"
        );
        assert_eq!(text(reactor_step_result_track_kind(result, 0)), "video");
        assert_eq!(text(reactor_step_result_track_kind(result, 1)), "audio");
        assert_eq!(reactor_step_result_track_rate(result, 0), 24.0);
        assert_eq!(reactor_step_result_track_rate(result, 1), 48_000.0);
        assert_eq!(reactor_step_result_track_rate(result, 2), 0.0);
        assert!(reactor_step_result_track_name(result, 2).is_null());
        assert_eq!(reactor_step_result_frame_count(result, 0), 1);
        assert_eq!(reactor_step_result_frame_count(result, 2), 0);
        assert_eq!(
            text(reactor_step_result_frame_encoding(result, 0, 0)),
            "raw"
        );
        assert_eq!(text(reactor_step_result_frame_dtype(result, 1, 0)), "int16");

        let mut ndim = 0u32;
        let shape = reactor_step_result_frame_shape(result, 0, 0, &mut ndim);
        assert_eq!(std::slice::from_raw_parts(shape, ndim as usize), &[1, 2, 3]);

        let mut len = 0usize;
        let data = reactor_step_result_frame_data(result, 0, 0, &mut len);
        assert_eq!(std::slice::from_raw_parts(data, len), &[1, 2, 3, 4, 5, 6]);
        // Borrowed, not copied: the same bytes every time.
        assert_eq!(reactor_step_result_frame_data(result, 0, 0, &mut len), data);
        assert!(reactor_step_result_frame_data(result, 0, 1, &mut len).is_null());
        assert_eq!(len, 0);

        let metadata = reactor_step_result_frame_metadata(result, 0, 0, &mut len);
        assert_eq!(std::slice::from_raw_parts(metadata, len), b"tag");
        assert!(reactor_step_result_frame_metadata(result, 1, 0, &mut len).is_null());
        assert_eq!(len, 0);

        let mut time = 0i64;
        assert_eq!(
            reactor_step_result_frame_capture_time_us(result, 0, 0, &mut time),
            1
        );
        assert_eq!(time, 99);
        assert_eq!(
            reactor_step_result_frame_capture_time_us(result, 1, 0, &mut time),
            0
        );

        reactor_step_result_free(result);
    }
}

/// A step that ran and failed completes with ok = 1 and a result whose
/// error names the reason, borrowed like every other string on it.
#[test]
fn a_step_that_ran_and_failed_hands_over_a_result_with_its_reason() {
    let output = reactor_core::step::StepOutput {
        files: vec![],
        error: Some("generate() raised".into()),
        ..sample_output()
    };
    let result = Box::into_raw(Box::new(ReactorStepResult::from(output)));
    unsafe {
        let error = reactor_step_result_error(result);
        assert!(!error.is_null());
        assert_eq!(CStr::from_ptr(error).to_str().unwrap(), "generate() raised");
        assert_eq!(reactor_step_result_error(result), error);
        assert_eq!(reactor_step_result_file_count(result), 0);
        reactor_step_result_free(result);
    }
}

/// After destroy the completion is not called, so nobody would ever free
/// the result: it is freed here instead.
#[test]
fn a_step_resolved_after_destroy_frees_its_result_without_calling_back() {
    let recorded = StepRecorded::new();
    let gate = Arc::new(CallbackGate::new());
    assert_eq!(gate.retire(), Quiescence::Complete);
    let completion = StepCompletion {
        f: Some(record_step),
        userdata: &recorded as *const StepRecorded as *mut c_void,
        gate,
    };
    completion.resolve(Ok(ReactorStepResult::from(sample_output())));
    assert!(recorded.calls.lock().unwrap().is_empty());
}

/// Each step failure a host handles differently keeps its own code across
/// the boundary, so a binding can type it without reading the message.
#[test]
fn a_step_failures_entry_and_command_code_cross_the_boundary() {
    let failed = CoreError::StepCommandFailed {
        message: "tag failed (queue_full): full".into(),
        command_code: Some("queue_full".into()),
        entry: Some("commands[2]".into()),
    };
    let json = error_json(&failed, "step").unwrap();
    let json: serde_json::Value = serde_json::from_str(json.to_str().unwrap()).unwrap();
    assert_eq!(
        json["code"],
        reactor_core::error::codes::STEP_COMMAND_FAILED
    );
    assert_eq!(json["entry"], "commands[2]");
    assert_eq!(json["command_code"], "queue_full");
}

#[test]
fn step_failures_cross_the_boundary_with_their_own_codes() {
    use reactor_core::error::codes;
    use reactor_core::protocol::session::GenerationMode;
    for (error, code) in [
        (
            CoreError::StepRefused {
                message: "no".into(),
                entry: Some("commands[0]".into()),
            },
            codes::STEP_REFUSED,
        ),
        (
            CoreError::StepNotReady {
                message: "starting".into(),
            },
            codes::STEP_NOT_READY,
        ),
        (
            CoreError::StepResultTooLarge {
                message: "big".into(),
            },
            codes::STEP_RESULT_TOO_LARGE,
        ),
        (
            CoreError::StepCommandFailed {
                message: "set_prompt failed".into(),
                command_code: Some("bad".into()),
                entry: Some("state.prompt".into()),
            },
            codes::STEP_COMMAND_FAILED,
        ),
        (
            CoreError::StepSessionEnded {
                message: "gone".into(),
            },
            codes::STEP_SESSION_ENDED,
        ),
        (
            CoreError::StepFailed {
                code: "model_error".into(),
                message: "boom".into(),
            },
            "model_error",
        ),
        (
            CoreError::UnsupportedFrameEncoding {
                track: "v".into(),
                encoding: 9,
            },
            codes::UNSUPPORTED_FRAME_ENCODING,
        ),
        (
            CoreError::MessageTooLarge { size: 2, max: 1 },
            codes::MESSAGE_TOO_LARGE,
        ),
        (
            CoreError::GenerationModeMismatch {
                requested: GenerationMode::Stepped,
                echoed: None,
            },
            codes::GENERATION_MODE_MISMATCH,
        ),
        (
            CoreError::ChunkingRequired,
            codes::DATA_CHANNEL_CHUNKING_REQUIRED,
        ),
    ] {
        let recorded = StepRecorded::new();
        StepCompletion {
            f: Some(record_step),
            userdata: &recorded as *const StepRecorded as *mut c_void,
            gate: Arc::new(CallbackGate::new()),
        }
        .resolve(Err(error));
        let (ok, result, json) = recorded.wait();
        assert!(!ok);
        assert_eq!(result, 0);
        let json: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(json["code"], code);
        assert_eq!(json["operation"], "step");
    }
}
