# FAQ


## Isn't publishing this data dangerous? Why no zero-trust?

This tool is explicitly meant for people who want to make things public.

There is a warning against using it for secrets, or for political purposes,
which is an explicit non-goal as per the design doc.

It is an alternative for e.g. GitHub profiles, twitter profiles, containing
your follows and implicit relations that are already being data-mined to
determine relation data. If you do already want to share public relation data,
and I believe many people do, this is a free and open-source alternative.

Saying that, I believe it has a preferable privacy model to already "trusted"
Bitcoin and can have an on-par privacy model to certain group chats depending
on usage. Not that they are particularly private.

--

In bitcoin you can also generate addresses at will, and publish relation data
(transactions between addresses), but in practice you have to interact
explicitly with external relation systems such as fiat, or implicitly via a
transfer of physical assets (e.g. cars, houses, boats, food) which can be
corrolated to your addresses. Additionally it requires a central ledger that
permanently tracks all transactions forever.

With this system there is no need to interact with external systems, it is
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


## Isn't this the same as PKI / PGP web of trust?

PKI is about centralised trust over domain name ownership, and linking domain
names to trusted keys for secure communication with domain names. 

PGP's web of trust aims to be decentralized PKI, linking identity to keys, for
secure communication with identities.

Net Perspective doesn't care about identities (an identifier != identity). It
is about content within contexts, and trust in content under that context.
This trust isn't about trust in identity, it is about moderation and curation.


## Is this similar to lobste.rs?

Lobste.rs uses an invitation system to participate in discussion, this is used
for moderation purposes, and the whole tree is public: https://lobste.rs/users

Net Perspective is decentralised, it is about pieces of a graph instead of a
single tree, and we each put together those pieces to form separate
perspectives. Further, there is no general trust, there is only trust under a
specific context. Under Net Perspective, your data is also transportable.


## Is this crytpo(coin) related?

This project is entirely unrelated to anything crypto, it is not made for
making money, it is not made for transporting assets of any sort between
people, it doesn't use the underlying tech of crypto.

The idea is people will (hopefully) use this because of the inherent
(non-monetary) value of a tool that improves their lives rather then
speculative value of some future that may or may not be actualised.
