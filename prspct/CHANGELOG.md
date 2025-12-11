# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
### Removed
### Fixed

## 0.2.0 2025-12-11

### Changed
- Improved documentation and quickstart
- Updated flake template to use stable branch
- Implement '-i' flag for building from other peoples identifiers:
  e.g.:
  - `prspct build -i :</contacts.ds`
  - `prspct build -i "ssh-key:..."`

  It supports multiple identifiers:
  - `prspct build -i :</contacts.a -i :</contacts.b`
- Message filters are now implemented as headers on publication messages.
  Using `--log-level info` will now show the headers of failing messages.
- We calculate a single instant at the cli start for use throughout the app.
- Publication signatures are kept in the message headers rather than the publication.

### Removed
### Fixed

## 0.1.0 - 2025-12-05

First release.

[Unreleased]: https://github.com/net-perspective/prspct/compare/0.1.1...HEAD
[0.1.1]: https://github.com/net-perspective/prspct/compare/0.1.0...0.1.1
