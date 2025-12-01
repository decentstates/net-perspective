# net-perspective/prspct

This is a command line tool for publishing, fetching and compiling relation data.

A relation is either non-transitive (->) or transitive (->>) and maps between
contexts (e.g. alice#context.a ->> bob#context.b).

Example applications include:
- Sharing tools transitively.
- Sharing websites, and dynamically compiling lists.
- Keeping track of contacts


## Getting started

**Flake Template Method**

Pre-requisites:
- nix with flakes enabled

```bash
mkdir my-perspect
cd my-perspect
git init
nix flake init -t git+https://git.sr.ht/~decentstates/net-perspective
# Nix needs things added to git, may as well commit:
git commit -am "Initial commit"

# Using direnv:
direnv allow

# Otherwise
nix develop

prspct init --init-generate-keys --init-name "Your Name or Pseudonym" --init-email "Your email"

# Send your public key to someone in the #underties network to get access to the server
cat .prspct/keys/id_prspct.pub
```


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


## Usage

Run `prspct init` in an empty directory to get an example starting `prspct.edn`
config and setup the `.prspct` dir containing fetch state:

```bash
$ prspct init
```

Fill in the placeholder fields in the script.

Publish your relations as per your config:

```bash
$ prspct publish
```

Fetch relations from the sources listed in your config:

```bash
$ prspct fetch
```

Now that we have collected other peoples relations, we can build our
perspectives, here are some examples:

```
$ prspct build flat-ssh-keys "#underties" > authorized_keys
$ prspct build flat-uris "#contacts.*" | grep -E ".vcard$" | generate-contacts.sh
$ prspct build tsv "#videos" | yt-dlp-download.sh
$ prspct build flat-emails "#nix" > users-to-trust
$ prspct build tsv "#nix.**" | generate-nix-awesome.sh
$ prspct build tsv "#food.**" | map-tsv-urls.sh > food-map.html
$ prspct build tsv "#products.headphones" > recommended-headphones.html
```

## prspct.edn

This is where you store your relations.

The format of the config `prspct.edn` is as so:

    ;; `ctx` defines a context.
    ;; The base context must start with a hash.
    ;; It can optionally have a map as the third parameter, this is the context-options map.
    (ctx "#context.path" {  }

        ;; The rest of the ctx
    
        (-> "<identifier>" 
        (->> "<identifier>" 

        ;; N
        (ctx "nested.path {
        [nested-contexts or nested relations])


See `src/schema.clj` for the fully specified schemas.

Nested contexts


Add contexts:

    (ctx "foo")


### Bugs

mailto:~decentstates/net-perspective-alpha@lists.sr.ht


## License

Copyright © 2025 decentstates

Licensed under AGPL see LICENSE
