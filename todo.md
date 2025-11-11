### Before release:

- [ ] Can we apply licenses to the messages?
- [ ] Set up a clojure-like contributor agreement
- [ ] net-perspective/netperspective github
- [ ] set up mail forwarding on main domains


### Decisions

- [x] Context format
  Hash tag seems sensible, as it is like a tag:
  - #context.path.like.usenet - I like this as it is traditional, distinctive
    and unique.
  - #context/path/like/fs - this would better match what they see in an
    email... unless we have everything flat :o That might encourage people to
    have fewer contexts.
  => usenet like format


### Features

- [ ] did documents
- [ ] unicode contexts? multilingual?
- [ ] add message limits
- [ ] some kind of did hashing for anonymnity?


### Simplification

- [ ]


### Maintenance

- [x] Install SLF4J connector


### Security

- [ ] Some kind of audit


### Spread

- [ ] Google Takeout parser
- [ ] Browser extension for captures - generates command line to run, or email.
- [ ] RSS/Atom feed to mail-gossip gateway - usable for blogs, fediverse, youtube.


## clojure misc

- [ ] auto offline doc downloader
- [ ] Ensure schema is an extension of previous schema
- [ ] Keep track of all schema versions
- [ ] file template system
- [ ] cool if there was a "do not use variable below here signal"
      ;; kill: variable
