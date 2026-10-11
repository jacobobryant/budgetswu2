# Add

1. [ ] Add normalized storage for cards, current meta, tournaments,
   standings, decklists, card quantities, prices, sync state, and ordered
   recommendation IDs.
   [External data](/spec/README.md#external-data?lines=13-33)

2. [ ] Incrementally sync cards, current meta, and Premier decklists from
   swuapi, including tournament placement and merge/deletion handling.
   [External data](/spec/README.md#external-data?lines=13-23)

3. [ ] Sync each card's lowest non-foil price from tcgcsv.
   [External data](/spec/README.md#external-data?lines=25-26)

4. [ ] Run one sync job every 12 hours and persist its last attempt time.
   [External data](/spec/README.md#external-data?lines=28-31)

5. [ ] Calculate main-deck prices and tournament placement percentiles.
   [Placement](/spec/README.md#placement?lines=43-46)
   [Pricing](/spec/README.md#pricing?lines=48-50)

6. [ ] Compute and persist the current meta's ordered Pareto front after
   syncing.
   [Recommendations](/spec/README.md#recommendations?lines=35-41)

7. [ ] Have the sync job render home-page HTML to a file and serve that file
   at /.
   [UI](/spec/README.md#ui?lines=66-67)

8. [ ] Add a development-only endpoint that renders recommendations on
   request from the stored decklist IDs.
   [UI](/spec/README.md#ui?lines=68-69)

# Remove

1. [ ] Remove the home page's sign-in prompt.
   [UI](/spec/README.md#ui?lines=54-55)

# Change

1. [ ] Show recommendations on the public home page with leader, base,
   price, placement, player, date, tournament, and melee.gg link.
   [UI](/spec/README.md#ui?lines=54-64)

2. [ ] Let signed-in users see the home page instead of redirecting to /app.
   [UI](/spec/README.md#ui?lines=54-55)
