+++
title = "Frequently Asked Questions"
date = 2026-01-01T00:00:00+00:00
+++

{{< toc >}}

## Isn't publishing this data dangerous? Why no zero-trust?

This tool is explicitly meant for people who want to make things public.

There is a warning against using it for secrets, or for political purposes,
which is a non-goal for this project.

It is an alternative for e.g. GitHub profiles, twitter profiles, containing
your follows and implicit relations that are already being data-mined to
determine relation data. If you do already want to share public relation data,
and I believe many people do, this is a free and open-source alternative.

That said, I believe it has a preferable privacy model to already "trusted"
Bitcoin and can have an on-par privacy model to certain group chats depending
on usage. Not that they are particularly private.

--

In bitcoin you can also generate addresses at will, and publish relation data
(transactions between addresses), but in practice you have to interact
explicitly with external relation systems such as fiat, or implicitly via a
simultaneous to bitcoin transfer of physical assets (e.g. cars, houses, boats,
food) which can be corrolated to your addresses. Additionally it requires a
public central ledger that keeps all your transactions forever.

With net-perspective there is no need to interact with external systems, it is
possible to create networks where each node only knows their direct relations
and doesn't otherwise attach their id to personally identifiable information.
There is also no central public ledger for this data, you can directly send
this data to others in any way you see fit.

--

For group chats you know all the members ids in a group and everyone shares a
copy of the messages. They are sent end to end encrypted between members. If
there are `n` members there will be `n` unencrypted copies of the messages plus
group metadata on `n` devices. If any of these devices are compromised or
leaked then all the data is compromised.

With this system, using an SFTP server, data will be accessible to `n`
users and `1` server, with the data being sent via the end-to-end encrypted
ssh protocol. Similarly if anyone compromises or leaks their device data
(including the server) then all the data is compromised.


## Is this some kind of social credit system?

No, this is about personal trust. Each person makes decisions on who their
trusted relations are, and they will have a different "perspective" based on
that.

A social credit system is single perspective, e.g. h-index, airbnb review
score, google map review score, money in general (how much money you
have/are willing to spend has always been a form of social credit.)

net-perspective is multi-perspective, each user will likely have an entirely
different graph depending on who they choose to closely relate to.

Yes it is a graph and graphs are a part of social credit systems, but anyone
wanting to build a social credit system would find it easier to do so without
this codebase.

As far as data goes, you don't need to tie your real world identity to your
identifier in net-perspective, and you can create as many identifiers as you'd
like without require any form of passport, government-issued-id, email-address,
postal-address etc.

Yes, data is accessible/leakable, but the data on most platforms are
accessible to people with money and motivation. Maybe you are privacy conscious
enough to not exist on Meta, Alphabet, or Microsoft, but in general everyone
else does exist there, and your non-existence won't make a difference.


## Isn't this the same as PKI / PGP web of trust?

PKI is about centralised trust over domain name ownership, and linking domain
names to trusted keys for secure communication with domain names. 

PGP/GPG's web of trust aims to be decentralized PKI, linking identity to keys, for
secure communication with identities.

net-perspective doesn't care about identities (an identifier != identity).
There is no guarantee an identifier is a specific person. An identifier can add
an email address to their `#self` context, but this doesn't mean that the
email-address trusts the identifier, it means the identifier trusts the email
address - e.g. it means if you want to contact or give permissions to the
identifier you can use the email, and *not* if you want to contact the email
securely you can use the public-key in the identifer. This is kinda opposite
to PGP/GPG.

net-perspective's trust isn't about trust in identity, it is about moderation
and curation.


## Is this similar to lobste.rs?

Lobste.rs uses an invitation system to participate in discussion, this is used
for moderation purposes, and the whole tree is public: https://lobste.rs/users

net-perspective is decentralised, it is about pieces of a graph instead of a
single tree, and we each put together those pieces to form separate
perspectives. Further, there is no general trust, there is only trust under a
specific context. Under net-perspective, your data is also transportable.


## Is this crytpo(coin) related?

This project is entirely unrelated to anything crypto, it is not made for
making money, it is not made for transporting assets of any sort between
people, it doesn't use the underlying tech of crypto.
