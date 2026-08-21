import type { Capabilities } from '../types';
import type { Capabilities as WireCapabilities } from './reactor-wasm.types';

/** The wasm binding's snake_case wire shape → the public, camelCase one —
 *  what `Reactor.getCapabilities()`/`capabilitiesReceived` hand back to a
 *  caller. */
export function toPublicCapabilities(wire: WireCapabilities): Capabilities {
  const capabilities: Capabilities = {
    protocolVersion: wire.protocol_version,
    tracks: wire.tracks,
  };

  if (wire.commands !== undefined) {
    capabilities.commands = wire.commands;
  }
  if (wire.emission_fps !== undefined) {
    capabilities.emissionFps = wire.emission_fps;
  }
  return capabilities;
}
