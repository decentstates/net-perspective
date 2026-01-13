# net-perspective

See `./net-perspect.org/` for documentation.
See `./prsp/README.md` for the tool.


### Quickstart

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

# Otherwise:
nix develop

prsp init --init-generate-keys --init-name "Your Name or Pseudonym" --init-email "Your email"

# Send your public key to someone in the #underties network to get access to the server
cat .prsp/keys/id_prsp.pub


# Test it out:
prsp fetch
prsp publish

# Add relations:
vim relations.edn

# Publish:
prsp publish

# Build perspectives:
prsp build tsv
```


### Bugs/Feedback

mailto:~decentstates/net-perspective-alpha@lists.sr.ht


## License

Copyright © 2025 decentstates

Licensed under AGPL see LICENSE
