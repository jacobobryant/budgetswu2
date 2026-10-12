# Add

1. [x] Store each user's last recommendation set (decklist IDs) that they
   viewed on "Decks" or were emailed about.
   [Email notifications](/spec/README.md#email-notifications?lines=138-140)

2. [x] When a user signs in for the first time, initialize their stored set
   with the recommendations currently shown on the home page.
   [Email notifications](/spec/README.md#email-notifications?lines=143-145)

3. [x] When a user views "Decks" and their current recommendations differ
   from the stored set, replace the stored set.
   [Email notifications](/spec/README.md#email-notifications?lines=140-141)

4. [x] Add a `notifications` table with a record (user, created-at,
   unsubscribe token) for each notification email sent.
   [Email notifications](/spec/README.md#email-notifications?lines=166-168)

5. [x] At the end of the sync job (same thread), find subscribed users
   whose current recommendations include decks not in their stored set
   (removals alone don't count) and who haven't been emailed in 24 hours.
   [Email notifications](/spec/README.md#email-notifications?lines=133-136)
   [Email notifications](/spec/README.md#email-notifications?lines=147-152)
   [Email notifications](/spec/README.md#email-notifications?lines=177-178)

6. [x] For each such user, store the new set and a notifications record,
   then send an email listing only the new decks, formatted like the
   website, with a CTA to "Decks", an unsubscribe link, and the mailing
   address.
   [Email notifications](/spec/README.md#email-notifications?lines=142-143)
   [Email notifications](/spec/README.md#email-notifications?lines=154-156)
   [Email notifications](/spec/README.md#email-notifications?lines=166-174)

7. [x] Use the subject "New deck: <label>" / "New decks: <label> and N-1
   other(s)", naming the highest-ranked new deck.
   [Email notifications](/spec/README.md#email-notifications?lines=156-164)

8. [x] Add a mailing address setting (env var / config.edn) included in
   every notification email, and a base URL setting (BASE_URL) used for
   links in the emails.
   [Email notifications](/spec/README.md#email-notifications?lines=173-175)

9. [x] Add an unsubscribe page at /unsubscribe/<token>: the token is valid
   for 30 days, and a button POSTs to unsubscribe.
   [Email notifications](/spec/README.md#email-notifications?lines=168-171)
   [URLs](/spec/README.md#urls?lines=186)

10. [x] Add a signed-in "Settings" page where users can unsubscribe from
    notification emails.
    [Email notifications](/spec/README.md#email-notifications?lines=166)
    [URLs](/spec/README.md#urls?lines=185)

# Change

1. [x] Move pages to the spec's URLs: /decks (was /app/for-you), /cards
   (was /app/cards), /settings; rename the `app.for-you` namespace to
   match "Decks".
   [URLs](/spec/README.md#urls?lines=180-186)
   [Personalized recommendations](/spec/README.md?lines=96-99)
