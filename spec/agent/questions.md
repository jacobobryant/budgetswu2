1. The UI shows an "archetype popularity ranking," but the spec now only
   defines frequency. How is it ranked and displayed? Recommend competition
   ranking by frequency (1, 2, 2, 4; #1 most popular), shown as "Archetype
   popularity: #12 of 40" where 40 is the number of distinct archetypes.
   [UI](/spec/README.md#ui?lines=73-76)

2. Which decklists count toward popularity: all synced current-meta Premier
   decklists, or only recommendation-eligible ones (priced, with placement)?
   Recommend all synced current-meta Premier decklists.
   [Recommendations](/spec/README.md#recommendations?lines=54-55)

3. How are leader and base color identified? Recommend leader by full name
   with subtitle (so reprints match), and base color by the base card's
   aspects from swuapi card data, with aspectless bases as their own color.
   swuapi's own decklist archetype_uuid would be ignored.
   [Recommendations](/spec/README.md#recommendations?lines=51-54)

## Questions during implementation

4. swuapi decklists sometimes type the leader/base entry as "Deck", and some
   decklists have no cards at all. Should decklists without a known leader
   count toward popularity? Implemented: leader/base are identified via card
   types from the cards feed, and decklists with no leader are excluded.
   [Recommendations](/spec/README.md#recommendations?lines=51-56)
