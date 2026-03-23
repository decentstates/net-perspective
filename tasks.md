# Tasks

For each big task you start, create a new section under little tasks following the example template.


## Big Tasks:

- [ ] Replace the outer publication .eml format with JSON.
- [ ] Set up github actions to build prsp binaries for releases.
      - Create release script.
      


## Little tasks:

### Replace outer publication .eml format with JSON
- [x] create plan in ./plans/json-publication-format.md
- [x] Add `simple-message->json` / `json->simple-message` to schemas.cljc
- [x] Add `edn-message->json` / `json->edn-message` to schemas.cljc
- [x] Update example-source-config / example-publisher-config in schemas.cljc
- [x] Update write-edn-message! and load-fetch in message_transfer.cljc
- [x] Update sign/verify in publication.cljc
- [x] Update tests
- [x] Create PR to decentstates/net-perspective

### (big task name)
- [ ] create plan in ./plans/
- [ ] (big task subitems)
- [ ] ...
- [ ] Create PR to decentstates/net-perspective
(example template do not remove)
