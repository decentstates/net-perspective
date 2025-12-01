### Before release:

- [ ] Simplify naming
- [ ] Can we apply licenses to the messages?
- [ ] net-perspective/netperspective github
- [ ] set up mail forwarding on main domains


### Decisions

- [ ]

### Features

- [x] init feature
- [x] flake init template with basic dev shell


### Simplification

- [ ] provide instant time in the context to pass through the system
- [x] nil contexts
- [x] shortening relations
- [ ] *->> ->>* relations


### Bugs

- [x] Ensure include contexts exist
- [x] Fix publish visibility 
- [x] Add a test for publish visibility
- [ ] Add example relations to youtube videos in init
- [ ] Add tutorial in init expaining the syntax
- [x] Need better failure output on publish, iterate over failed publishers
- [x] Need better failure output on fetch, iterate over failed fetches
- [x] Check why max iterations is being met on includes
- [ ] Add test on fixed point limit
- [ ] Add test for prspct full init, with publications working
- [x] Fix relative key path
- [x] Test flake init


### Maintenance

- [ ]


### Security

- [ ] Some kind of audit


### Spread

- [ ] Google Takeout parser
- [ ] Browser extension for captures - generates command line to run, or email.


## clojure misc

- [ ] auto offline doc downloader
- [ ] Ensure schema is an extension of previous schema
- [ ] Keep track of all schema versions
- [ ] file template system
