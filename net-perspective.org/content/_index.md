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



## net-perspective - The Idea

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
A link or user goes missing one day to the next, you can see the added and
removed items when you fetch, you have an archive of historical publications
and can track how things went missing, you can add them to your publication if
you wish.

**net-perspective adds discovery, moderation, and curation under a single
simple mechanism to other tools.**


## prsp - net-perspective The Tool

[**_prsp_**](https://git.sr.ht/~decentstates/net-perspective/tree/main/item/prsp/README.md) (pronounced "persp" as in "perspective") is a command-line tool that can build publications, publish them, fetch publications, and build perspectives.

<aside>
Yes, a UI can come at a later stage.
</aside>

To initialize a directory run:
```
$ prsp init --init-generate-keys --init-name "<NAME>" --init-email "<EMAIL>"
```

<aside>
  Name and email are added to publications as contact details, feel free to use pseudonyms and throwaway email addresses.
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
     (->> "prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi" "#self"))

(ctx "#film"
     (->> :</friends.ds "#film" :public)
     (->  "https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)" :public))

(ctx "#music"
     (->  "https://yolatengo.bandcamp.com/album/i-can-hear-the-heart-beating-as-one-25th-anniversary-deluxe-edition" :public))
```
> An example `relations.edn`


This expands in terms of the relations syntax defined in [definitions](#some-quick-definitions) to:
```
:self#friends.ds ->> "prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi"#self

(public) :self#film ->> "prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi"#film 
(public) :self#film ->> "https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)"#

(public) :self#music -> "https://yolatengo.bandcamp.com/album/i-can-hear-the-heart-beating-as-one-25th-anniversary-deluxe-edition"#
```

Meaning your publication would be:
```
:self#film ->> "prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi"#film 
:self#film ->> "https://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)"#

:self#music -> "https://yolatengo.bandcamp.com/album/i-can-hear-the-heart-beating-as-one-25th-anniversary-deluxe-edition"#
```


Let's now take a closer look at the syntax.



#### idents

An ident is how you refer to someone or something.

- Often it is **a URI string**:

  ```
  "http://en.wikipedia.org/wiki/A_Scanner_Darkly_(film)"
  "geo:37.78918,-122.40335?z=14&(Wikimedia+Foundation)"
  "prsp-id:ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOirp5rceowRPLnkCT2/vlTPgxtRWPeKdMIPnJ7ixJfi"
  ```

  As shown above there is a special URI scheme: `prsp-id`, which carries a ssh
  public key. This is how you can refer to other users.

- Otherwise it can be **a context-include**:
  
  ```
  :</friends.ds
  :</context.path.you.want.to.include
  ```

  It includes the identities from a contexts *perspective*, which is often used
  as a contacts system so you don't have to use `prsp-id:` URIs all the time.

  This is a subtle concept, more on this later.

- Lastly it can be <strong>the symbol <code>:self</code></strong> which refers to you, so you can follow
  yourself in different contexts.



#### `(ctx ...)`, `(-> ...)`, `(->> ...)` blocks

`(ctx "#subject.context" ...)` is a context-block that contains any number of either `(-> ...)` non-transitive or `(->> ...)` transitive relation-blocks.

A relation block can take the following forms:
```
(arrow ident)
(arrow ident :public)
(arrow ident context)
(arrow ident context :public)
```

Where arrow is one of:
- `->` non-transitive relate.
- `->>` transitive relate.

Notes:
- If the object-context is missing, it becomes `#` the "root context."
- Unless a relation has `:public`, it is private and not published.

<br />

<aside>
<strong>Advanced</strong>

You can add asterisks around the arrows for "globbing":

- `[arrow]*` object-glob - all the object contexts immediate children are related to under your subject context.
- `*[arrow]*` subject-glob - you mirror as subject child contexts all of the object child contexts.

These are an important tool in terms of curation but not needed for everyday use.
</aside>


### Usage

`config.edn` comes preconfigured for the moment.




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


### Captcha/


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
