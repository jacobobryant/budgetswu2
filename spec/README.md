# BudgetSWU

This application has features related to the TCG "Star Wars: Unlimited."

## Decklist recommender

The home page displays recommended decklists based on tournament performance,
price, and popularity (less popular is better). Decklists and their
performance/popularity data ultimately comes from melee.gg while pricing data
comes from TCGPlayer, although we fetch the data from 3rd parties.

### External data

We use the public api.swuapi.com endpoints to sync the following data into our
application's database:

- cards
- current meta
- decklists, including tournament percentile placement (e.g. "3rd place out of
  60"). We only sync premier decklists. We only sync decklists from the current
  meta.

Cards use the incremental feed described at
https://api.swuapi.com/docs#incremental-sync. Tournaments, players, decklists,
and standings use paginated public exports at
https://api.swuapi.com/docs#export-entity; their incremental feeds require an
API key. No API key is needed for this application.

Cards are sometimes printed as part of multiple sets, i.e. a card can have
multiple printings. We do not store separate prices or user-owned quantities
(see below) for each printing; those values are associated with the overall
card.

We sync pricing data from tcgcsv.com. We store the lowest non-foil price for
each card (for any of its printings).

There is a single background job that syncs data from both providers (swuapi and
tcgcsv). It runs every 12 hours. It's last attempted sync time is recorded
persistently so that restarting the application doesn't cause a sync sooner than
12 hours.

We store all data in a structured, normalized form.

### Recommendations

The background job that does the syncing also updates the decklist
recommendations (an ordered list of decklist primary keys). We consider all
decklists that are in the current meta. The recommendations are a simple pareto
front based on price, tournament percentile placement, and archetype popularity.

Feature details:

- Price: we only consider cards from the main decklist, not the sideboard. Lower
  prices are better.

- Popularity: we define the deck's archetype as a combination of the leader and
  the base color. Different types of bases of the same color are treated the
  same, e.g. a given leader with a red 30hp base is the same archetype as the
  same leader with a red 27hp base. We compute the frequency of each archetype
  in the set of all decklists in the current meta. Lower frequencies are better
  than higher frequencies.

- Placement: A decklist that came first in a 10-person tournament has a
  percentile of 90%, and the first place decklist in a 20-person tournament has
  a percentile of 95%. Higher percentiles are better.

Decklists are ordered first based on price (lowest first) and then by archetype
popularity (least popular first). Any remaining ties are sorted by decklist ID,
since the pareto front will not contain decklists with equal price and
popularity but different placement (one would be dominated).

### UI

Recommendations are displayed directly on the home page. Users are not shown a
sign-in form, and sign-in is not required to view the recommendations. Below
the site name, a short description says what the site does and invites visitors
to sign in to get recommendations based on the cards they already own. Each
recommendation includes:

- leader name and base
- price
- archetype popularity ranking
- tournament placement
- player name
- date
- tournament name
- link to the decklist on melee.gg

Since the page only changes whenever the 12-hour sync job runs, that job also
renders html and writes it to a file. That file is served as the home page.
There is a separate endpoint meant for development only that renders the page at
request time from the recommended decklist primary keys stored by the sync job.

## Personalized recommendations

If users create an account and sign in, they get access to 2 other pages: "For
you" and "Your cards". If the user has not yet entered anything on "Your cards"
(see below), then they're sent to that page after signing in and are shown an
onboarding message. Otherwise, "For you" is the default page.

### Your cards

This page has a list of all the cards that are currently legal in premier (e.g.
as of October 2026, only cards that were printed after sets 1-3 were published
are legal). The cards are sorted by price, high to low. The user can specify how
many copies of each card they have, up to 3 each. The list is paginated, 50 per
page. There is a search bar that does fuzzy-find-style searching. Search results
update as you type.

To infer legality, first determine the earliest legal set. There are always 4 -
6 legal sets at any time. When the first set of a calendar year is released
(i.e. set 1, set 4, set 7, ...), then the 3 sets from the calendar year two
years ago are "rotated out" and become illegal. e.g. when set 7 was released,
sets 1-3 became illegal. All sets/promos printed after the earliest legal set
are also legal. A card is legal if at least one of its printings is legal.

We do not need any logic for banned cards.

If cards don't have a price from tcgcsv, they are not shown.

Each card links to its page on starwarsunlimited.com, opening in a new tab,
e.g. https://starwarsunlimited.com/cards?cid=8487171598.

### For you

This page is similar to the home page except that when computing the
recommendations, the cost for each deck takes into account which cards the user
already has. i.e. we consider the cost for the user to buy only the remaining
cards which they don't already own. This page does not need to be cached.
