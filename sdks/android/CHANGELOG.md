# Changelog

All notable changes to the Reactor Android SDK are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`release-android.yml` refuses to publish a version bump that has no matching
`## [x.y.z]` heading below, so the entry is written by hand in the same pull
request as the bump.

## [Unreleased]

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
