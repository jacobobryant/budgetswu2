1. How is a decklist assigned to the current meta, including when an era
   changes or a tournament spans eras? Recommend using its tournament date
   and the meta era's published date range.
   [External data](/spec/README.md#external-data?lines=13-20)

2. Which placement and field size should be used when standings are missing
   or incomplete? Recommend using final placement and tournament player count,
   and excluding decklists missing either value.
   [Placement](/spec/README.md#placement?lines=43-46)

3. How should card printings map to tcgcsv products, and should leader/base
   cards count toward price? Recommend pricing each main-deck printing by
   quantity, including leader and base only if present in the main deck data.
   [Pricing](/spec/README.md#pricing?lines=48-50)

4. What happens when a card has no non-foil price? Recommend excluding its
   decklist from recommendations until all main-deck cards have prices.
   [External data](/spec/README.md#external-data?lines=25-26)

5. How should decklists with equal price and percentile be ordered?
   Recommend retaining each and sorting them by decklist ID.
   [Recommendations](/spec/README.md#recommendations?lines=37-41)

6. Should a failed or partial sync update recommendations? Recommend keeping
   the previous list, recording the attempt, and retrying after 12 hours.
   [External data](/spec/README.md#external-data?lines=28-31)

7. What should / serve before the first sync has written its HTML file?
   Recommend serving an empty-state page until a successful sync writes it.
   [UI](/spec/README.md#ui?lines=66-69)
