+++
title = "net-perspective intro"
date = 2026-01-01T00:00:00+00:00
+++

**An idea and tool for *emergent perspectivised* moderation and
curation.**

**Emergent:** Individuals actings selfishly in a small way creates moderation and
curation for everyone in a large way.

**Perspectivized:** Moderation and curation is individual to your decisions. As
opposed to centralised or algorithmic.

<br />

Not AI or crypto based. A boring technology.

https://git.sr.ht/~decentstates/net-perspective/

<br />
<br />
<br />
<br />


{{< toc >}}


## Some Quick Definitions

### "context"

```
#comp.lang.c
```

A hierarchical "hashtag."


### "relate"

```
alice#treasure -> bob#trash
```

> Alice relates to bob's trash as her treasure.

<br />


Like a "trust" or "follow" or "like", but involves contexts:

```
subject#subject-context -> object#object-context
```

<br />


You can relate to other users, or to arbitrary uris:

```
alice#film -> "https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)"
```



### "super-relate" (aka "transitive-relate")

```
alice#music ->> cat#music
```

> Alice relates to Cat's music, and to whoever Cat relates to in music.

You relate to who they relate to. Note the double arrow `->>`.


### "publication"

```
alice#treasure      ->  bob#trash
alice#music         ->> cat#music
alice#music         ->> dom#music.country
alice#music         ->  "https://yolatengo.bandcamp.com/album/i-can-hear-the-heart-beating-as-one-25th-anniversary-deluxe-edition"#
alice#film          ->  dom#film.bad
alice#film          ->  "https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)"#
alice#film.terrible ->> dom#film.favourites
```

> Alice's publication.

Relates shared by an entity.


### "perspective"

```
"https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)"
dom#film.bad
"https://en.wikipedia.org/wiki/The_Stupids_(film)"
```

> Alice's perspective of `#film`.

A set of `user#context` or URIs, after combining other's publications, and following relates and super-relates.



## The Idea

You publish your direct relations, others publish their direct relations, you
fetch and browse their relations, you relate to each others relations.

Publication repositories are simple file stores, they proliferate, you can
automatically publish and fetch from a number of repositories.

You build perspectives, for music, film, products, for areas of interest.

You have transitive lists of people and sites you trust under different
contexts - your perspectives.

You feed perspectives into your website as a form of webring. You use those
perspectives to share research. You build curated trees of links using
contexts hierachies. You filter your perspective for rss feeds and feed that
using contexts as tags into your rss feed reader, your rss feed reader now has
human curation-based discovery and moderation.
 
You become a well known publication producer. You become a publication
aggregator that collects publications and maps and organises them into neat
taxonomies.

You don't like someone in your webring, you contact the next hop and if that
doesn't resolve it you simply remove the intermediate relation or make it
non-transitive. Doing so improves the lives of countless others downstream.

You are getting spam in your rss feed, slop research is appearing in your
contexts, you can work out who it is coming from and who is passing that along.
A link or user goes missing one day to the next, you can see added and removed
items when you fetch, you have an archive of historical publications and can
track how things went missing, you can add them to your publication if you
wish.

**net-perspective adds discovery, moderation, and curation under a single
simple mechanism to other tools.**


## prsp - net-perspective the tool

[**_prsp_**](https://git.sr.ht/~decentstates/net-perspective/tree/main/item/prsp/README.md) (pronounced "persp" as in "perspective") is a command-line tool that can build publications, publish them, fetch publications, and build perspectives.

To initialize a directory run:
```
$ prsp init --init-generate-keys --init-name "<NAME>" --init-email "<EMAIL>"
```

<aside>
  Name and email are added to publications, but aren't checked.
</aside>

You and all users are represented by an ssh-key.
```
$ cat .prsp/keys/id_prspct.pub
```

You will need to send your key to an already connected user to gain access to the pre-configured network.


### relations.edn

`relations.edn` contains your direct relations:


```
(ctx "#friend.ds"
     (->> "mailto:ds@decentstates.com" "#self" :public)
     (->> "https://git.sr.ht/~decentstates" "#self" :public)
     (->> "prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi" "#self" :public))

(ctx "#film"
     (->> :</friends.ds "#film" :public)
     (->  "https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)" :public))

(ctx "#music"
     (->  "https://yolatengo.bandcamp.com/album/i-can-hear-the-heart-beating-as-one-25th-anniversary-deluxe-edition" :public))
```
> An example `relations.edn`



### relations.edn spec

#### CONTEXT-NAME

A string containing the context: `"#context"`


#### IDENT

An ident is how you refer to someone or something.

It's just a URI:

```
http://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)
geo:37.78918,-122.40335?z=14&(Wikimedia+Foundation)
prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi 
```

As shown above there is an extension: `prsp-id`, which carries a ssh public
key. This is how you refer to other users.



#### CONTEXT-BLOCK

A context block contains your relations under a context

```
(ctx CONTEXT-NAME RELATION-BLOCK...)
```

#### RELATION-BLOCK

One of:
```
(-> IDENT)
(-> IDENT :public)
(-> IDENT CONTEXT-NAME)
(-> IDENT CONTEXT-NAME :public)

(->> IDENT)
(->> IDENT :public)
(->> IDENT CONTEXT-NAME)
(->> IDENT CONTEXT-NAME :public)
```

A double arrow means it is a transitive relation.

#### RELATIONS-EDN

A file of CONTEXT-BLOCKs.

<aside>

Example:

```
(ctx "#subject.context.path"
  (-> "uri-a" "#object.context.path.a" :public)
  (->> "uri-b" "#object.context.path.b"))
```

Has direct relations:

```
self#subject.context.path -> "uri-a"#object.context.path.a
self#subject.context.path ->> "uri-b"#object.context.path.b
```

And would result in the publication of:

```
self#subject.context.path -> "uri-a"#object.context.path.a
```

</aside>

By default relations are private, if you want to include a relation in your publication, add `:public` to the end of the relation or super-relation block:
```
(ctx "#subject.context.path"
  (-> "uri:a" "#object.context.path" :public)
  (->> "uri:b" "#object.context.path" :public))
```

#### object globs

`(->* ...)` or  `(->>* ...)`

This is where you want to collapse all children contexts into your context.

#### subject globs

`(*->* ...)` or  `(*->>* ...)`

If you want to carry forward someone elses categories.

#### context includes

A final and mysterious feature is called "context includes", this is where you
can reference a context where you would normally have an identity or url.

A context include looks like `:</subject.context.path` referring to `#subject.context.path` in your relations:
```
(-> :</context.i.want.to.include "#object.context.path")
```

What it does is expand the relation to all identities found in the *referenced perspective*.

A key use-case is for contacts:



### Example usage

You build a set of relations, in your `relations.edn` like this:

This implies you have the following relations:

```
you#film ->> "https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)"#
```

*This is not your publication,* by default your relations are private,



## Example Applications

This tool intends to be a simple primitive, with applications built on top.

Here are a few examples of how it could be used.

### Purchasing Headphones

You find a friend who has been through the same process and super-relate to their `#purchases.headphones`, it relates to several other users, including one who is a headphone reviewer. You find a few new links in your research and add them in there too:

```
(ctx "#products.headphones"
  (->> :</friends.carl "#purchases.headphones" :public)
  (->  "https://.../headphone-comparison.xslt" :public))
```

You build your perspective, you have a list of useful blog posts, reddit threads, spreadsheets, or links to products.

For any item in your perspective, you can trace it through users to find out how it got to you.

You decide on a particular pair of headphones and after using it for a month you add a link in a new perspective:

```
(ctx "#products-i-love.furniture"
  (-> "https://ikea.com/..." :public))

(ctx "#products-i-love.headphones"
  (-> "https://amazon.com/..." :public))
```

And all your research is reusable to others.


### Scientific Publication Curation

Are you an expert in a field? Or even just a hobbiest? You could publish curated lists of articles you trust.

You can also transitively trust others in your field and build a comprehensive perspective of articles.

Perhaps multiple distinct perspectives will form that trust different people and sources.


### Trust-Based-Access-Control (TBAC)

You can use context to provide permissions to users in a scalable expanding way.

What about human tests? An alternative to cloudflare, captchas, and anime girls checking your computer.


### Music recommendations

Maybe you have pretty specific genre cutting taste in music.

Maybe someone has simulated all artists available on wikipedia as publications:

```edn
(ctx "#music.influences"
  (-> ...))
```

You can provide bandcamp links, spotify links, it can all mix together.


### Restaurants recommendations

You have a pretty specific diet (vegan/paleo/carivore/gluten-free/whatever).

You follow a few people that share links to places.

You take these links, maybe mixing google and openstreetmaps and add them to a tool, it turn the links into a map.



### Group member tracking across platforms


### Cross-use of perspectives

Once you have built a network for one context, you can reuse it for other contexts.


## Caveats

Who is this for?
- Public people.
- Reviewers, listmakers.
- Scientists, Information workers.
- Information consumers.

Who is this not for?
- Journalistic sources.
- Activists.
- Anyone that should hide their relations.


## More Information

- [Initial Design Doc](/design.html)
- [Frequently Asked Questions](/faq.html)

## The creator

I am `decentstates`, an ordinary low-profile software-engineer.

I'm interested in trust systems, maybe this tool is kinda dumb, but I want to
see if I can develop it into something useful.

```
"prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi"
```
