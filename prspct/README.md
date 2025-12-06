# net-perspective/prspct

This is a command line tool for publishing, fetching and compiling relation data.

A relation is either non-transitive (->) or transitive (->>) and maps between
contexts (e.g. alice#context.a ->> bob#context.b).

Example applications include:
- Sharing tools transitively.
- Sharing websites, and dynamically compiling lists.
- Keeping track of contacts


## Getting started

See the quickstart in the README at the repo root.


## Development

Download from https://git.sr.ht/~decentstates/net-perspective

Start a dev shell:

    $ nix develop

Or use direnv.


Development repl:

    $ clj -M:dev/repl

Testing:

    $ clj -M:test

Building:

    $ clj -T:build ci

Nix build:

    $ nix build .#prspct


## config.edn

This primarily configures where you fetch data from (a "source") and where you
send your data to (a "publisher").

The recommended init process comes with this file pre-configured.


## prspct.edn

This is where you store your relations.

It is a series of context blocks:
```clojure
(ctx "#name.of.context" ...)
```

Containing within them relations:
```clojure
; (colons start a comment)

; A single arrow means you only directly relate to them, not who they relate to (non-transitive.)
(->> "ssh-key:[...]" "#name.of.context")

; A double arrow means you relate to them, _and_ who they relate to (transitive.)
(->> "ssh-key:[...]" "#name.of.context")

; If you want to make the relation public, add `:public` to the end, this means it will get published.
(->> "ssh-key:[...]" "#name.of.context" :public)


; If you are relating to a uri, you likely won't care about the "object-context", so you can ommit it:
(-> "uri:http://wikipedia.com/")
; (It defaults to `#`, the "root context".)
```

Using identifiers all the time is a bit cumbersome, especially with ssh keys,
instead we can use "context-includes": 
```clojure
;; We have an ordinary context which holds identifiers
(ctx "#friends.smith"
    ;; LINE A
    (->> "ssh-key:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi" "#self"))


;; And when we want to reference all the identifiers in that context we use a context include:
(ctx "#good-food"
    ;; The context include looks like `:</the.context.you.want.to.include`, e.g.:
    (->> :</friends.smith "#food")) ;; LINE B
```

The context include expands the relation to any identifiers under the context,
in the above example, `LINE B` expands into at least this:
```clojure
(->> "ssh-key:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi" "#self")
```

I say "at least", because if smith has any other identifiers in their "#self"
context, that will get transitively included too, since `LINE A` uses the `->>`
arrow. This can be a bit mind bending, but should become intuitive after a
little use and is a powerful concept for managing identities.

Tips:
- Make sure the idents are prefixed with either: "email:", "ssh-key:", or
  "uri:" or you will get an error.
- "ssh-key:" must not have comments attached, must be exactly "ssh-key:[ssh
  public key with comment stripped]", note there is no space between the colon
  and the key.

See `src/schema.clj` for the fully specified schemas.


### Bugs

mailto:~decentstates/net-perspective-alpha@lists.sr.ht


## License

Copyright © 2025 decentstates

Licensed under AGPL see LICENSE
