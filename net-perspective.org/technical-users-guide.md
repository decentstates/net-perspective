## prspct init

## prspct.edn

## Publication Invalidation

Publications have a mandatory `:publication/valid-until` entry, which is set
via `:publication-validity-seconds` in your config. It is recommended not to
set it longer than a week, preferably shorter and publish frequently.

Each new set of publications sets
`:publication/invalidates-previous-publications-until` which invalidates
previous publications.

Make sure your clocks are set correctly. Servers should also detect
publications published without recent timestamps or with expiry dates too far
into the future and discard and notify the user.
