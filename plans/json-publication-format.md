# Replace Outer Publication .eml Format with JSON

## Goal

Replace the email-style outer envelope (headers + CRLF CRLF + body) with a JSON
wrapper. Body remains a JSON string (not nested object) so signing is exact.

## New file format

Files change from `.eml` to `.json`. Content changes from:

```
from: Alice <alice@example.com>
subject: Publication
...

{"publication/version":"alpha-do-not-spread",...}
```

to:

```json
{
  "headers": {"from": "Alice <alice@example.com>", ...},
  "body": "{\"publication/version\":\"alpha-do-not-spread\",...}"
}
```

Body stays as a JSON string inside the outer JSON so signing remains stable
(no re-serialization of parsed objects, no key-order issues).

## Changes

### schemas.cljc
- Add `simple-message->json` / `json->simple-message`
- Add `edn-message->json` / `json->edn-message`
- Update `example-source-config` and `example-publisher-config` to use `*.json`

### message_transfer.cljc
- `write-edn-message!`: use `edn-message->json`, `.json` extension, rename hash var
- `load-fetch`: glob `**.json` instead of `**.eml`
- Update `shell-publisher` docstring

### publication.cljc
- `sign-publication-message`: use `edn-message->simple-message` directly (no round-trip via eml)
- `verify-publication-message`: use `json->simple-message` to extract body

### tests
- `message_transfer_test.clj`: update `*.eml` → `*.json` and `**.eml` → `**.json`
