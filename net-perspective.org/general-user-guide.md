--------------
DO NOT SPREAD.
--------------

Current stage: Extreme alpha

Do not share, like at all, this is half-baked.
Everything can and will break, and fewer involved will make it easier to
manually fix.

--------------
DO NOT SPREAD.
--------------


welcome to #underties


## help/bugs/patches

need help? 

join the mailing list:
https://lists.sr.ht/~decentstates/net-perspective-alpha

follow mailing list etiquette:
https://man.sr.ht/lists.sr.ht/etiquette.md


want to submit a bug or a patch?

you must submit a contributors agreement before submitting code


## warnings:

- not safe for secret, political or otherwise sentitive intentions.
- consider your relations as public.

if you don't understand why these are listed as limitations, you should
definitely be following them.

- things can get messy, but i believe this is better than status quo
- the most important thing is trusting the people close to you


## privacy

- know that this service is pseudo-anonymous at best
- use tor or a proxy service
- use separate keys to what you use elsewhere
- for the paranoid, use separate private keys and identifiers per context


## underties 

if you are viewing this you are a member of the `decentstates#underties` perspective

perspective rules:
- context: `#underties`, no mapping allowed.
- eligibility: human, decent, technical, non-commercial intentions
- relates (->): know someone in person before relating to them.
- super-relates (->>): observe and trust them before superrelating
- unrelates: you are expected to unrelate to someone breaking the rules, if not
  you are breaking the rules and others will be expected to unrelate to you.
  just the way it's got to go.


special access:
- Access to:
  - `sftp://np@alpha.underties.net/srv/np-publication-submit` (publishing)
  - `sftp://np@alpha.underties.net/srv/np-publications` (sourcing)
  - `sftp://np@alpha.underties.net/srv/messages-submit` (publishing)
  - `sftp://np@alpha.underties.net/srv/messages` (sourcing)
  Via your ssh key identifier, or `#self.ssh-keys -> :external:ssh-key:[your-ssh-key]`.
- If you expose `#self.client-certs -> :external:cert:[my-cert]` 


messages rules:
- be decent and respectful
- don't try to break the server
- nothing illegal
- host files somewhere else, patches are ok though.
- maybe post controversial stuff on your own server


## relations

- prefer relating to identities tied to people, not abstract entities
  they are less likely to change under you
- if you are an abstract entity, you should only really relate to identities
  that are part of your organisation


## contexts

- follow usenet contexts for inspiration, they provide a nice baseline of categories
- don't be too afraid with your contexts, someone else can always remap
- try not to remove contexts, rather mark as deprecated (TODO), and redirect a
  new context to your old context
- you can also search for who relates to your context and let them know


## demo applications

- trust-based-access-control (TBAC)
  - website access via client certs
  - server access via ssh keys
- map locations
- bookmarks
- rss feed config generator
- mail context-news


## invites process

- do not spread this tool publicly, instead invite and show people how to use it
- in person, most preferably
- don't have a template or process, just natural human interation

your tasks:
- relate to them in #underties
- help them set up their intitial config
- answer questions and provide support


## hosting

recommendations:
- very simple sftp/ssh server
- little to no durability or backups needed
- follow the underties.net access above as a convention, using ssh-identifier,
  #self.ssh-keys, #self.client-certs
- use find+cron to ensure file-size/recency/quotas
- generate `authorized_keys` from your perspective, can be used with
  client-certs and nginx to gate servers generally.

