# Add

1. [x] Group printings into overall cards, and store each card's lowest
   non-foil price across its printings.
   [External data](/spec/README.md#external-data?lines=29-35)

2. [x] Store how many copies (0-3) of each overall card each user owns.
   [External data](/spec/README.md#external-data?lines=29-32)
   [Your cards](/spec/README.md#your-cards?lines=103-105)

3. [x] Compute Premier legality from set rotation: the earliest legal set
   and everything printed after it; a card is legal if any printing is.
   [Your cards](/spec/README.md#your-cards?lines=107-114)

4. [x] Add a signed-in "Your cards" page listing legal, priced cards by price
   (high to low), 50 per page, with an editable owned count up to 3.
   [Your cards](/spec/README.md#your-cards?lines=101-116)

5. [x] Add a signed-in "For you" page that computes recommendations at
   request time, pricing each deck by only the cards the user doesn't own.
   [For you](/spec/README.md#for-you?lines=120-123)

6. [x] Add navigation between "For you" and "Your cards" for signed-in
   users.
   [Personalized recommendations](/spec/README.md?lines=94-95)

7. [x] After sign-in, send users with no cards entered to "Your cards" with
   an onboarding message; otherwise default to "For you".
   [Personalized recommendations](/spec/README.md?lines=95-97)

8. [x] Link each card on "Your cards" to its starwarsunlimited.com page in a
   new tab, using the swuapi external_uid of its newest Standard printing.
   [Your cards](/spec/README.md#your-cards?lines=118-122)

9. [x] Add a search bar to "Your cards" with fuzzy-find-style matching.
   [Your cards](/spec/README.md#your-cards?lines=102-105)

# Remove

1. [x] Remove the template demo page at /app (display name and background
   color settings), replacing it with the personalized pages.
   [Personalized recommendations](/spec/README.md?lines=94-97)

# Change

1. [x] Price decks by each card's overall price instead of the price of the
   specific printing listed in the decklist.
   [External data](/spec/README.md#external-data?lines=29-35)

2. [x] Update the intro text on "Your cards" and "Decks" to match the
   mockups. [Mockups](/spec/mockups/cards-desktop.txt?lines=27-28)

3. [x] Add the sign-in invitation to the home page description.
   [UI](/spec/README.md#ui?lines=73-79)
