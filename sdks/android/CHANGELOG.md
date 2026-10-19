# Changelog

All notable changes to the Reactor Android SDK are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`release-android.yml` refuses to publish a version bump that has no matching
`## [x.y.z]` heading below, so the entry is written by hand in the same pull
request as the bump.

## [Unreleased]

### Added

- `Reactor.fetchJwt(apiUrl, apiKey, options, local)`, the standalone key→token
  exchange Java, Swift and Python already expose. Scope tokens with
  `options = """{"models":["owner/name"]}"""` before handing them to a client
  you do not control; an unrecognised option key is an error, a rejected key
  reports UNAUTHORIZED, and an unreachable coordinator NETWORK_ERROR.

### Fixed

- `ReactorOptions.apiKey` no longer connects with no credentials. The option was
  accepted but never read, so an app holding only a key connected without a
  token and the platform answered 401 "Missing Authorization header". `connect()`
  now does the key→token exchange the other SDKs do: minting a token scoped to
  what the connect reaches (the model when creating a session, the session when
  adopting one), re-minting when the scope changes, and rebuilding the native
  client around the new token. A caller-supplied `jwt` still wins, and `local`
  still never authenticates. `downloadClip()` likewise passes the resolved
  token, not the (possibly absent) options one.
- Same-session `reconnect()` could time out after 30 s ("transport readiness
  did not complete") on high-latency links. Each ICE candidate was POSTed to
  the coordinator inline in the peer-event pump, so a reconnect's candidate
  burst (dozens of candidates, one round trip each) held the connection's
  readiness events queued behind it past the connect timeout. Candidates now
  flush from a dedicated background task. (Core fix lands on main separately;
  the SDK pulls it in on its next native rebuild.)
