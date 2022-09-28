(ns poker.discord.display
  "The display namespace contains functions to create chat messages for poker games in Discord.
  The emotes used for the cards come from the Playing Card Emojis Discord server:
  https://top.gg/servers/623564336052568065"
  (:require [poker.logic.pots :as pots]
            [poker.logic.game :as poker]
            [clojure.string :as strings]
            [clojure.tools.cli :as cli]
            [poker.discord.command :as cmd]
            [discljord.formatting :refer [mention-emoji mention-user code-block]]
            [clojure.string :as string]))

(def ^:private black-ranks
  {:ace   1024477002092253215
   :two   1024476992676048938
   :three 1024476993825296404
   :four  1024476994374750289
   :five  1024476995578499143
   :six   1024476996677410887
   :seven 1024476997558226964
   :eight 1024476998820696074
   :nine  1024477000200634428
   :ten   1024477001186299946
   :jack  1024477003098882058
   :queen 1024477006882160750
   :king  1024477004206198804})

(def ^:private red-ranks
  {:ace   1024477020836614264
   :two   1024477011537825862
   :three 1024477012091473922
   :four  1024477013458821130
   :five  1024477014293483580
   :six   1024477014985556079
   :seven 1024477016600354856
   :eight 1024477017732812860
   :nine  1024477018856890418
   :ten   1024477019771248740
   :jack  1024477021662892103
   :queen 1024477024489848883
   :king  1024477023177019443})

(defn- with-suit [rank-map suit]
  (reduce-kv (fn [acc rank id]
               (assoc acc (conj [suit] rank) id))
             {}
             rank-map))

(def upper-halves
  (merge (with-suit black-ranks :clubs)
         (with-suit black-ranks :spades)
         (with-suit red-ranks :hearts)
         (with-suit red-ranks :diamonds)
         {[nil nil] 1024477006122991686}))

(def lower-halves
  {:clubs    1024477007733588008
   :spades   1024477010623467571
   :hearts   1024477009637806171
   :diamonds 1024477008769597561
   nil       1024477005107970110})

(defn- halves-str [cards upper separate-at]
  (let [keyfn (if upper (juxt :suit :rank) :suit)
        halves-map (if upper upper-halves lower-halves)
        emotes (->> cards
                    (map keyfn)
                    (map halves-map)
                    (map mention-emoji))]
    (string/join
      " "
      (if separate-at
        (let [[left right] (split-at separate-at emotes)]
          (concat left ["    "] right))
        emotes))))


(defn cards->str
  ([cards separate-at fill-to]
   (let [cards (concat cards (repeat (- fill-to (count cards)) nil))]
     (str
       (halves-str cards true separate-at)
       "\n"
       (halves-str cards false separate-at))))
  ([cards separate-at] (cards->str cards separate-at 0))
  ([cards] (cards->str cards nil)))

(defn- pots->str [pots]
  (strings/join
    "\n"
    (map (fn [{:keys [name money]}]
           (str "**" name ":** `" money "` chips"))
         pots)))

(defn game-state-message
  [{:keys [community-cards] :as game}]
  (str
    "**Community Cards:**\n"
    (cards->str community-cards nil 5) "\n"
    (pots->str (:pots (pots/flush-bets game)))))

(defn- move->str [{:keys [action cost]}]
  (str (strings/capitalize (name action)) " - `" cost "` chips"))

(defn turn-message
  [{[player-id] :cycle :keys [budgets] :as game}]
  (str
    "It's your turn, " (mention-user player-id) "!\n"
    "What would you like to do? You still have `" (budgets player-id) "` chips.\n"
    (strings/join "\n" (map move->str (poker/possible-moves game)))))

(defn instant-win-message
  [{[{[winner] :winners :keys [money]}] :pots}]
  (str
    "Everybody except " (mention-user winner) " has folded!\n"
    "They win the main pot of `" money "` chips."))

(defn- hands->str [hands player-cards]
  (strings/join
    "\n"
    (map (fn [[player-id {:keys [name cards]}]]
           (str (mention-user player-id) " - " name "\n"
                (cards->str (concat cards (player-cards player-id)) 5)))
         hands)))

(defn- pot-win->str
  [{[winner & more :as winners] :winners :keys [prize name]}]
  (if more
    (str (strings/join ", " (map mention-user winners)) " split the " name " for `" prize "` chips each!")
    (str (mention-user winner) " wins the " name " and gets `" prize "` chips!")))

(defn- wins->str [pots]
  (strings/join "\n" (map pot-win->str pots)))

(defn showdown-message
  [{:keys [hands pots player-cards]}]
  (str
    "**Showdown!** Let's have a look at the hands...\n\n"
    (hands->str hands player-cards)
    "\n\nThis means that:\n"
    (wins->str pots)))

(defn player-notification-message
  [{:keys [order player-cards budgets]} player-id]
  (str
    "Hi " (mention-user player-id) ", here are your cards for this game:\n"
    (cards->str (player-cards player-id)) "\n"
    "You have a budget of `" (budgets player-id) "` chips.\n"
    "We're playing no-limit Texas hold'em. You can read up on the rules here:\n"
    "<https://en.wikipedia.org/wiki/Texas_hold_%27em>\n\n"
    "Those are the participants, in order: " (strings/join ", " (map mention-user order)) "\n"
    "**Have fun!** :black_joker:"))

(def ^:const handshake-emoji "\uD83E\uDD1D")

(def ^:const fast-forward-emoji "\u23E9")

(def ^:const x-emoji "\u274C")

(defn- host-message [player-id]
  (str (mention-user player-id)
       " is the host of the game and can :x: abort it or :fast_forward: start it immediately."))

(defn new-game-message [player-id timeout buy-in]
  (str
    (mention-user player-id) " wants to play Poker!\n"
    "You have " (quot timeout 1000) " seconds to join by reacting with :handshake:!\n"
    "Everybody will start with `" buy-in "` chips.\n\n"
    (host-message player-id)))

(defn blinds-message [{:keys [big-blind small-blind big-blind-value small-blind-value]}]
  (str
    (mention-user small-blind) " places the small blind of `" small-blind-value "` chips.\n"
    (mention-user big-blind) " places the big blind of `" big-blind-value "` chips."))


(defn- budgets->str [budgets]
  (strings/join
    "\n"
    (map (fn [[player-id budget]]
           (str (mention-user player-id) " - `" budget "` chips"))
         budgets)))

(defn restart-game-message [player-id {:keys [budgets]} timeout buy-in]
  (str
    "This round of the game is over, but you can keep playing!\n"
    "Players of the last round, you now have:\n"
    (budgets->str budgets) "\n\n"
    "You will enter the next round with this if you continue playing.\n"
    "New players can also join! They will start with `" buy-in "` chips.\n"
    "If you want to play, react with :handshake: within the next " (quot timeout 1000) " seconds.\n\n"
    (host-message player-id)))

(defn already-ingame-message [user-id]
  (str "You are already in a game, " (mention-user user-id) "!"))

(defn channel-mention [id]
  (str "<#" id ">"))

(defn channel-occupied-message [channel-id user-id]
  (str "There already is an active poker session in " (channel-mention channel-id) ", " (mention-user user-id)))

(defn channel-waiting-message [channel-id user-id]
  (str "There already is a game waiting for players to join in " (channel-mention channel-id)
       ". Maybe you want to join there, " (mention-user user-id) "?"))

(defn invalid-raise-message [game]
  (let [minimum (poker/minimum-raise game)]
    (str "You must raise to an amount between `" minimum "` and `"
         (poker/possible-bet game) "` chips. E.g.: `raise " minimum "`")))

(defn info-message [{:keys [default-wait-time default-timeout]} user-id]
  (str
    "Hi, " (mention-user user-id) "!\n"
    "I am a Discord bot that allows you to play Poker (No limit Texas hold' em) against up to 19 other people in chat. "
    "To start a new game, simply type `holdem! <buy-in amount>`. The (optional) buy-in is the amount of chips everyone will start with.\n"
    (->> (cli/parse-opts [] (cmd/poker-options default-wait-time default-timeout))
        :summary
        (str "Here are the options for the command (option, default value, description):\n")
        code-block) \newline
    "Grab a bunch of friends and try it out!\n\n"
    "You can find links to invite the bot, to join the support server and to view the source code here: <https://top.gg/bot/461791942779338762>"))

(defn timed-out-message [{[current] :cycle}]
  (str (mention-user current) " did not respond in time and therefore folds automatically."))

