# Budgetswu

This application has features related to the TCG "Star Wars: Unlimited."

## Decklist recommender

The home page displays recommended decklists based on tournament performance and
price. Decklists and their performance data ultimately comes from melee.gg while
pricing data comes from TCGPlayer, although we fetch the data from 3rd parties.

### External data

We use api.swuapi.com to incrementally sync the following data into our
application's database:

- cards
- current meta
- decklists, including tournament percentile placement (e.g. "3rd place out of
  60"). We only sync premier decklists. We only sync decklists from the current
  meta.

We follow the instructions at https://api.swuapi.com/docs#incremental-sync so we
don't put unnecessary load on the API.

We sync pricing data from tcgcsv.com. We store the lowest non-foil price for
each card.

There is a single background job that syncs data from both providers (swuapi and
tcgcsv). It runs every 12 hours. It's last attempted sync time is recorded
persistently so that restarting the application doesn't cause a sync sooner than
12 hours.

We store all data in a structured, normalized form.

### Recommendations

The background job that does the syncing also updates the decklist
recommendations (an ordered list of decklist primary keys). We consider all
decklists that are in the current meta. The recommendations are a simple pareto
front based on price and tournament percentile placement, with lowest priced
decklists coming first.

#### Placement

A decklist that came first in a 10-person tournament has a percentile of 90%,
and the first place decklist in a 20-person tournament has a percentile of 95%.

#### Pricing

We only consider cards from the main decklist, no the sideboard.

### UI

Recommendations are displayed directly on the home page. Users are not shown a
sign-in form, and sign-in is not required to view the recommendations. Each
recommendation includes:

- leader name and base
- price
- placement
- player name
- date
- tournament name
- link to the decklist on melee.gg

Since the page only changes whenever the 12-hour sync job runs, that job also
renders html and writes it to a file. That file is served as the home page.
There is a separate endpoint meant for development only that renders the page at
request time from the recommended decklist primary keys stored by the sync job.
