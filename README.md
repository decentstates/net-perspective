# net-perspective

See `./net-perspect.org/` for documentation.
See `./prspct/README.md` for the tool.


### Quickstart


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

prspct init --init-generate-keys --init-name "Your Name or Pseudonym" --init-email "Your email"

# Send your public key to someone in the #underties network to get access to the server
cat .prspct/keys/id_prspct.pub


# Test it out:
prspct fetch
prspct publish

# Add relations:
vim relations.edn

# Publish:
prspct publish

# Build perspectives:
prspct build tsv
```


### Bugs/Feedback

mailto:~decentstates/net-perspective-alpha@lists.sr.ht


## License

Copyright © 2025 decentstates

Licensed under AGPL see LICENSE
