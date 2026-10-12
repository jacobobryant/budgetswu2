1. What identifies an "overall card" across printings? swuapi gives each
   printing its own uuid and collector number. Recommend name + subtitle
   (e.g. "Luke Skywalker, Faithful Friend"), which also merges promo and
   alternate-art printings.
   [External data](/spec/README.md#external-data?lines=29-32)

2. How do we get set numbers and promo dates? swuapi's /sets has no release
   dates. Recommend: the latest released set is the current meta's set; set
   numbers come from the first two digits of a card's serial_code; a promo
   printing is legal if its tcgcsv group was published on or after the
   earliest legal set's group.
   [Your cards](/spec/README.md#your-cards?lines=107-112)

3. How do users find sign-in? The home page shows no sign-in form and the
   mockups show no link. Recommend a small "Sign in" link in the home header
   for signed-out users, and "For you" / "Your cards" / "Sign out" links for
   signed-in users.
   [UI](/spec/README.md#ui?lines=74-75)
   [Personalized recommendations](/spec/README.md?lines=94-97)

4. How is "remaining cost" computed? Recommend summing
   max(0, needed - owned) x price per card, then building the Pareto front
   and ordering exactly as on the home page using that cost.
   [For you](/spec/README.md#for-you?lines=120-123)

5. What does each "For you" card show? Recommend the home page card layout
   with the price stat showing cost to complete, plus the full price in
   smaller text (e.g. "$12.40 · full $64.96").
   [For you](/spec/README.md#for-you?lines=120-123)

6. How is the owned count edited, and is there search? Recommend a 0-3
   segmented control per card that saves immediately, and no search for now.
   [Your cards](/spec/README.md#your-cards?lines=103-105)

7. What counts as "entered anything", and what does onboarding say?
   Recommend: the user has at least one card with a count above 0; the
   message explains that owned cards lower "For you" prices and links there.
   [Personalized recommendations](/spec/README.md?lines=95-97)

8. Should the sign-in pages match the app's look? They still say
   "My Application" with Biff's default indigo color. Recommend app name
   "BudgetSWU" and the home page's orange accent.
   [Personalized recommendations](/spec/README.md?lines=94-97)

9. Should mockups be added for "For you" and "Your cards"? Recommend adding
   mobile and desktop mockups in spec/mockups/ before implementation.
   [Personalized recommendations](/spec/README.md?lines=92-123)

## Questions during implementation

10. Existing databases can't migrate automatically: sqldef won't drop the old
    NOT NULL columns or add deck_card.card_id while rows exist, so startup
    fails. Is there a deployed database? Recommend clearing the synced tables
    and applying the schema with drops enabled (only user data needs to
    survive), as I did for the dev database.
    [External data](/spec/README.md#external-data?lines=29-35)

11. Several promo printings reuse another card's collector number (e.g.
    SOR_001 is five different cards). Implemented: decklist cards are matched
    by name, and tcgcsv prices are mapped through Standard/Hyperspace
    printings only. OK?
    [External data](/spec/README.md#external-data?lines=29-35)

12. Promo sets without a numbered serial code or a matching tcgcsv group
    (e.g. P25, J25, C25, GG) have no known date, so they're treated as not
    legal. This only affects cards that exist solely as such promos. OK?
    [Your cards](/spec/README.md#your-cards?lines=107-112)

13. Tokens (e.g. "Token Unit", "Credit Token") are excluded from "Your
    cards" since they aren't deck cards. OK?
    [Your cards](/spec/README.md#your-cards?lines=101-105)

14. The home page is a cached file, so its header always says "Sign in". The
    link goes to /app, which sends signed-in users straight to their
    personalized page. OK, or should signed-in users see different links?
    [UI](/spec/README.md#ui?lines=87-90)

15. Search results are ranked by match quality (consecutive letters and
    word starts score higher), then by price, rather than strictly by price.
    OK? [Your cards](/spec/README.md#your-cards?lines=102-105)

16. The search and page number are kept per browser tab (in a tab_state
    table) rather than in the URL, so results survive live rerenders but a
    reload starts over. Rows are never cleaned up. Recommend a background
    job that deletes tab_state rows older than a day.
    [Your cards](/spec/README.md#your-cards?lines=102-105)
