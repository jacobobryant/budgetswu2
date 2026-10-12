1. Users who already have accounts never had a "first sign-in" after this
   feature, so they have no stored set. Recommend treating a missing set
   like a first sign-in: initialize it from the home page set during the
   next sync and don't email them that time.
   [Email notifications](/spec/README.md#email-notifications?lines=143-145)

2. The base URL key is written `:budgetswu/base-url`, but the project's
   convention is the main namespace (`:com.budgetswu/...`). Recommend
   `:com.budgetswu/base-url`, reading BASE_URL. Also recommend skipping
   emails (with a logged warning) if BASE_URL or the mailing address is
   unset, and building the og:image preview URL from the base URL too
   (it currently uses DOMAIN), so there's one setting for absolute links.
   [Email notifications](/spec/README.md#email-notifications?lines=173-175)

3. What is the "<decklist label>" in the subject? Recommend the leader's
   full name plus base, e.g. "Boba Fett, Krayt's Claw Commander / Contested
   Caverns".
   [Email notifications](/spec/README.md#email-notifications?lines=159-161)

4. What happens with an expired or unknown unsubscribe token? Recommend a
   page saying "This link has expired" with a link to sign in and
   unsubscribe from Settings.
   [Email notifications](/spec/README.md#email-notifications?lines=168-171)

5. What is on "Settings"? Recommend a single "Email me when there are new
   deck recommendations" on/off toggle, so users can resubscribe too, plus
   a "Settings" link in the signed-in nav. Should mockups be added?
   Recommend yes (desktop and mobile).
   [Email notifications](/spec/README.md#email-notifications?lines=166)

6. Where do users land after signing in, now that /app isn't in the URL
   list? Recommend: the sign-in redirect goes to /decks, and /decks
   redirects to /cards only right after sign-in when the user owns no
   cards (keep a small internal /app redirect route for this).
   [Personalized recommendations](/spec/README.md?lines=96-99)
   [URLs](/spec/README.md#urls?lines=180-186)

## Questions during implementation

7. The table is named `notification` (singular) to match the other tables
   (`user`, `owned_card`, ...) rather than `notifications`. OK?
   [Email notifications](/spec/README.md#email-notifications?lines=166-168)

8. Unsubscribe tokens are random UUIDs from biff.fx's injected
   `random-uuid4-seq`, which uses java.util.Random rather than a
   cryptographically secure generator. A guessed token could only
   unsubscribe someone. Recommend switching to SecureRandom (e.g. an fx
   handler or injected value) if that matters.
   [Email notifications](/spec/README.md#email-notifications?lines=166-171)

9. If BASE_URL or MAILING_ADDRESS is unset, the sync logs a warning and
   sends no emails (per question 2). The og:image preview URL now also uses
   BASE_URL instead of DOMAIN. OK?
   [Email notifications](/spec/README.md#email-notifications?lines=173-175)

10. Deployed databases that already have the old schema need no manual
    migration for this change (new nullable columns and a new table). In
    dev, the `notification` table was briefly created with a TEXT token
    column and had to be dropped; that won't affect a fresh deploy.
    [Email notifications](/spec/README.md#email-notifications?lines=166-168)
