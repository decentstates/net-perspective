
this is an experiment

motivation:
- decreasing trust, increasing scams, spam, and slop
- growing "market for lemons" effect
- open internet feels like it is breaking
- i don't need a 4.8 star with 1000 reviews, i need one good review from
  someone i trust


goals:
- improve trust in general
- focus on relations, not content
- a small simple collective datastructure, producing simpler data for use in
  applications
- very small specification
- transport/store independant


non-goals:
- writing a server-client protocol
- an addiction machine
- trying to be twitter/facebook/youtube/reddit
- integration with activity-pub
- integration with atproto
- secrets and secrecy
- realtime, instant feedback
- commercialisation
- usuable ui, can come later


ideas:
- there is no trust without context
- a generalisation of trust is a relation e.g. also food and music taste
- relation is moderation and mapping contexts is curation
- moderation and curation is finding truth
- finding trust requires finding truth

- pull is simpler than push and is fundamental to giving users control


implementation:
- data format + semantics: relations, publications
- independent transport of publications 


previous iterations:
- classical client-server model with relays
- email-server based message relay-system


scaling:
- users will automatically shard away from busy servers
- presense of aggregator users in networks
- users can limit hops in search
- users can use more detailed contexts, which will have less relations
- presence in graph is O(n) but paths between nodes is complex
  initially supporting paths everywhere, but later only displaying paths in
  special places or on demand
