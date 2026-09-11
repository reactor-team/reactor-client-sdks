//! Assemble capture PCM into the 10 ms blocks required by WebRTC's ADM.

pub(crate) fn supported_format(sample_rate: u32, channels: u32) -> bool {
    matches!(
        sample_rate,
        8_000 | 16_000 | 24_000 | 32_000 | 44_100 | 48_000
    ) && matches!(channels, 1 | 2)
}

#[derive(Default)]
pub(crate) struct AudioInput {
    format: Option<(u32, u32)>,
    pending: Vec<i16>,
}

impl AudioInput {
    pub(crate) fn push(
        &mut self,
        mut pcm: &[i16],
        sample_rate: u32,
        channels: u32,
        mut deliver: impl FnMut(&[i16], u32, u32),
    ) {
        if !supported_format(sample_rate, channels) || !pcm.len().is_multiple_of(channels as usize)
        {
            log::warn!("audio push: invalid PCM format or incomplete interleaved frame");
            return;
        }
        if pcm.is_empty() {
            return;
        }
        let format = (sample_rate, channels);
        if self.format != Some(format) {
            if !self.pending.is_empty() {
                log::warn!("audio push: format changed; dropping the partial 10 ms block");
                self.pending.clear();
            }
            self.format = Some(format);
        }
        let block_samples = (sample_rate / 100 * channels) as usize;
        if !self.pending.is_empty() {
            let take = (block_samples - self.pending.len()).min(pcm.len());
            self.pending.extend_from_slice(&pcm[..take]);
            pcm = &pcm[take..];
            if self.pending.len() == block_samples {
                deliver(&self.pending, sample_rate, channels);
                self.pending.clear();
            }
        }
        let mut blocks = pcm.chunks_exact(block_samples);
        for block in &mut blocks {
            deliver(block, sample_rate, channels);
        }
        self.pending.extend_from_slice(blocks.remainder());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn arbitrary_chunks_preserve_pcm_and_format_at_every_supported_rate() {
        for rate in [8_000, 16_000, 24_000, 32_000, 44_100, 48_000] {
            for channels in [1, 2] {
                let block_len = (rate / 100 * channels) as usize;
                let pcm: Vec<i16> = (0..block_len * 5).map(|n| n as i16).collect();
                let mut input = AudioInput::default();
                let mut received = Vec::new();
                // One fragment, a push spanning several blocks, then the tail.
                let split = 17 * channels as usize;
                for chunk in [
                    &pcm[..split],
                    &pcm[split..pcm.len() - split],
                    &pcm[pcm.len() - split..],
                ] {
                    input.push(chunk, rate, channels, |block, hz, ch| {
                        assert_eq!((hz, ch, block.len()), (rate, channels, block_len));
                        received.extend_from_slice(block);
                    });
                }
                assert_eq!(received, pcm);
                assert!(input.pending.is_empty());
            }
        }
    }

    #[test]
    fn a_format_change_does_not_reinterpret_buffered_samples() {
        let mut input = AudioInput::default();
        input.push(&[7; 79], 8_000, 1, |_, _, _| panic!("partial block"));
        let pcm = [9; 320];
        let mut calls = 0;
        input.push(&pcm, 16_000, 2, |block, rate, channels| {
            assert_eq!(block, pcm);
            assert_eq!((rate, channels), (16_000, 2));
            calls += 1;
        });
        assert_eq!(calls, 1);
    }

    #[test]
    fn invalid_input_does_not_change_the_buffered_stream() {
        let mut input = AudioInput::default();
        input.push(&[7; 79], 8_000, 1, |_, _, _| panic!("partial block"));
        for (rate, channels, pcm) in [
            (0, 1, &[1, 2][..]),
            (48_000, 0, &[1, 2][..]),
            (48_000, 3, &[1, 2, 3][..]),
            (u32::MAX, 1, &[1, 2][..]),
            (48_000, 2, &[1][..]),
            (48_000, 2, &[][..]),
        ] {
            input.push(pcm, rate, channels, |_, _, _| panic!("invalid input"));
        }
        input.push(&[7], 8_000, 1, |block, rate, channels| {
            assert_eq!(block, [7; 80]);
            assert_eq!((rate, channels), (8_000, 1));
        });
        assert!(input.pending.is_empty());
    }
}
