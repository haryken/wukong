// Port of mods/mg_blackjack.go — Web Blackjack vs a simple dealer bot. UCI: "deal", "hit", "stand".
// Dealer draws one card at a time (with pause + speak) instead of all at once.
(function (WG) {
  'use strict';

  function bjNewDeck() {
    var suits = ['S', 'H', 'D', 'C'];
    var ranks = ['A', '2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K'];
    var deck = [];
    for (var i = 0; i < suits.length; i++) {
      for (var j = 0; j < ranks.length; j++) {
        deck.push(ranks[j] + suits[i]);
      }
    }
    var r = WG.shuffle(deck);
    return Array.isArray(r) ? r : deck;
  }

  // bjCardRank extracts the rank portion of a "<rank><suit>" card, e.g. "10S" -> "10".
  function bjCardRank(card) {
    if (card.length >= 3 && card[0] === '1' && card[1] === '0') return '10';
    if (card.length === 0) return '';
    return card.slice(0, 1);
  }

  function bjCardValue(card) {
    var r = bjCardRank(card);
    switch (r) {
      case 'A': return 11;
      case 'J': case 'Q': case 'K': case '10': return 10;
      default: {
        var n = /^[+-]?[0-9]+$/.test(r) ? parseInt(r, 10) : 0;
        return n;
      }
    }
  }

  // bjHandValue sums a hand, softening aces (11 -> 1) while over 21.
  function bjHandValue(hand) {
    var total = 0, aces = 0;
    for (var i = 0; i < hand.length; i++) {
      total += bjCardValue(hand[i]);
      if (bjCardRank(hand[i]) === 'A') aces++;
    }
    while (total > 21 && aces > 0) {
      total -= 10;
      aces--;
    }
    return total;
  }

  function bjSpeakRank(card) {
    var r = bjCardRank(card);
    switch (r) {
      case 'A': return ['Át', 'Ace'];
      case 'J': return ['J', 'Jack'];
      case 'Q': return ['Q', 'Queen'];
      case 'K': return ['K', 'King'];
      default: return [r, r];
    }
  }

  function bjSpeakCardMode(card) {
    var lang = WG.gameSpeakLang();
    if (lang === 'en') return bjSpeakRank(card)[1];
    if (lang === 'vi') return bjSpeakRank(card)[0];
    return WG.bjRankSpoken(card);
  }

  function bjHint(status, winner) {
    var hint = 'Blackjack';
    if (status === 'win' && winner === 'human') {
      hint = 'Blackjack. Chúc mừng! Bạn thắng.';
    } else if (status === 'lose') {
      hint = 'Blackjack. Bạn thua.';
    }
    return hint;
  }

  class BjGame extends WG.MiniCommon {
    constructor() {
      super();
      this.deck = [];
      this.playerHand = [];
      this.dealerHand = [];
      this.dealt = false;
      this.dealerRevealed = false;
      this.bank = 100;
      this.lastCard = '';
      this.drawAnimTo = '';
      this.status = 'playing';
      this.winner = '';
      this.lastMove = '';
      this.history = [];
      this.humanTurn = true;
      this.botThinking = false;
      this.thinkGen = 0;
      this.moves = 0;
      this.difficulty = WG.diffMedium;
      this.message = WG.viOrEN('Ván Blackjack mới. Bấm Bắt đầu để chia bài.', 'New Blackjack round. Press Start to deal.')[0];
    }

    draw() {
      if (this.deck.length === 0) this.deck = bjNewDeck();
      return this.deck.pop();
    }

    askHitMsg(justCard) {
      var pv = bjHandValue(this.playerHand);
      if (justCard !== '') {
        return WG.speakf(
          'Bạn đã rút được lá %s. Tổng điểm hiện tại là %d. Bạn có muốn rút thêm không?',
          'You drew a %s. Your total is %d. Do you want to hit again?',
          bjSpeakCardMode(justCard), pv
        )[0];
      }
      return WG.speakf(
        'Điểm hiện tại của bạn là %d. Bạn có muốn rút thêm không?',
        'Your current score is %d. Do you want to hit?',
        pv
      )[0];
    }

    finishResolve() {
      var pv = bjHandValue(this.playerHand), dv = bjHandValue(this.dealerHand);
      if (pv > 21) {
        this.status = 'lose'; this.winner = 'bot'; this.bank -= 10;
        this.message = WG.viOrEN('Quắc! Bạn thua.', 'Bust! You lose.')[0];
      } else if (dv > 21) {
        this.status = 'win'; this.winner = 'human'; this.bank += 10;
        this.message = WG.speakf(
          'Nhà cái quắc với %d điểm! Bạn thắng!',
          'Dealer busts with %d! You win!',
          dv
        )[0];
      } else if (pv > dv) {
        this.status = 'win'; this.winner = 'human'; this.bank += 10;
        this.message = WG.speakf(
          'Bạn %d — nhà cái %d. Bạn thắng!',
          'You %d — dealer %d. You win!',
          pv, dv
        )[0];
      } else if (pv < dv) {
        this.status = 'lose'; this.winner = 'bot'; this.bank -= 10;
        this.message = WG.speakf(
          'Bạn %d — nhà cái %d. Bạn thua.',
          'You %d — dealer %d. You lose.',
          pv, dv
        )[0];
      } else {
        this.status = 'draw'; this.winner = '';
        this.message = WG.speakf(
          'Hoà (push) ở %d điểm.',
          'Push at %d.',
          pv
        )[0];
      }
      this.humanTurn = false;
      this.drawAnimTo = '';
    }

    // Flips to dealer phase (hole card revealed) and schedules stepped draws via runDealerThink.
    beginDealerTurn() {
      this.humanTurn = false;
      this.dealerRevealed = true;
      this.botThinking = true;
      this.thinkGen++;
      this.drawAnimTo = '';
      this.lastCard = '';
      var dv = bjHandValue(this.dealerHand);
      this.message = WG.speakf(
        'Đủ rồi — tới lượt nhà cái. Nhà cái đang có %d điểm.',
        "Standing — dealer's turn. Dealer shows %d.",
        dv
      )[0];
      return this.thinkGen;
    }

    snapshot() {
      var dealerShown = this.dealerHand.slice();
      var dealerValue = bjHandValue(this.dealerHand);
      if (this.dealt && this.status === 'playing' && !this.dealerRevealed && dealerShown.length > 0) {
        dealerShown = [dealerShown[0], '??'];
        dealerValue = bjCardValue(this.dealerHand[0]);
      }
      var extra = {
        playerHand: this.playerHand.slice(),
        dealerHand: dealerShown,
        playerValue: bjHandValue(this.playerHand),
        dealerValue: dealerValue,
        bank: this.bank,
        dealt: this.dealt,
        dealerRevealed: this.dealerRevealed,
        lastCard: this.lastCard,
        drawAnimTo: this.drawAnimTo
      };
      return this.baseSnap('blackjack', 'cards', extra);
    }

    summaryText() {
      return WG.sprintf('Blackjack web. Đã chia bài: %v. Điểm người chơi: %d. Trạng thái: %s. Bank: %d.',
        this.dealt, bjHandValue(this.playerHand), this.status, this.bank);
    }

    reset() {
      var prevDiff = this.difficulty, prevBank = this.bank;
      this.thinkGen++;
      this.botThinking = false;
      var ng = new BjGame();
      if (prevDiff) ng.difficulty = prevDiff;
      ng.bank = prevBank;
      inst = ng;
      return ng.snapshot();
    }

    setDifficulty(level) {
      this.difficulty = WG.normalizeGameDifficulty(level);
      return this.difficulty;
    }

    getDifficulty() {
      return WG.normalizeGameDifficulty(this.difficulty);
    }

    legalUCIs() {
      if (this.botThinking) return null;
      if (!this.dealt || this.status !== 'playing') return ['deal'];
      if (!this.humanTurn) return null;
      return ['hit', 'stand'];
    }

    playUCI(uci) {
      if (this.botThinking) {
        throw new Error('nhà cái đang rút bài, vui lòng chờ');
      }
      var cmd = String(uci).trim().toLowerCase();
      var needDealer = false;
      var thinkGen = 0;
      var pv;

      switch (cmd) {
        case 'deal':
          this.deck = bjNewDeck();
          this.playerHand = [this.draw(), this.draw()];
          this.dealerHand = [this.draw(), this.draw()];
          this.status = 'playing';
          this.winner = '';
          this.humanTurn = true;
          this.dealt = true;
          this.dealerRevealed = false;
          this.drawAnimTo = 'player';
          this.lastCard = this.playerHand[this.playerHand.length - 1];
          this.moves++;
          this.lastMove = 'deal';
          pv = bjHandValue(this.playerHand);
          if (pv === 21) {
            // Natural / soft 21 — go straight to dealer.
            thinkGen = this.beginDealerTurn();
            needDealer = true;
          } else {
            this.message = this.askHitMsg('');
          }
          break;

        case 'hit': {
          if (!this.dealt || this.status !== 'playing' || !this.humanTurn) {
            throw new Error('chưa đến lượt rút của bạn');
          }
          var card = this.draw();
          this.playerHand.push(card);
          this.lastCard = card;
          this.drawAnimTo = 'player';
          this.lastMove = 'hit';
          this.moves++;
          pv = bjHandValue(this.playerHand);
          if (pv > 21) {
            this.status = 'lose'; this.winner = 'bot'; this.bank -= 10;
            this.humanTurn = false;
            this.dealerRevealed = true;
            this.message = WG.speakf(
              'Bạn rút lá %s — tổng %d. Quắc! Bạn thua.',
              'You drew a %s — total %d. Bust! You lose.',
              bjSpeakCardMode(card), pv
            )[0];
          } else if (this.playerHand.length >= 5) {
            this.status = 'win'; this.winner = 'human'; this.bank += 10;
            this.humanTurn = false;
            this.dealerRevealed = true;
            this.message = WG.viOrEN('Năm lá Charlie! Bạn thắng!', 'Five-card Charlie! You win!')[0];
          } else if (pv === 21) {
            thinkGen = this.beginDealerTurn();
            needDealer = true;
          } else {
            this.message = this.askHitMsg(card);
          }
          break;
        }

        case 'stand':
          if (!this.dealt || this.status !== 'playing' || !this.humanTurn) {
            throw new Error('chưa đến lượt của bạn');
          }
          this.lastMove = 'stand';
          this.moves++;
          this.drawAnimTo = '';
          this.lastCard = '';
          thinkGen = this.beginDealerTurn();
          needDealer = true;
          break;

        default:
          throw new Error('lệnh không hợp lệ (deal/hit/stand)');
      }

      var resp = this.snapshot();
      var msg = this.message;
      WG.queueGameSpeak('blackjack', msg, bjHint(this.status, this.winner));
      if (needDealer) {
        this.runDealerThink(thinkGen);
      }
      return resp;
    }

    async runDealerThink(gen) {
      // Let the stand / 21 message + player anim settle first.
      await WG.sleep(1600);

      var msg;
      for (var step = 0; step < 12; step++) {
        if (gen !== this.thinkGen || !this.botThinking) return;
        if (this.status !== 'playing') {
          this.botThinking = false;
          return;
        }

        var dv = bjHandValue(this.dealerHand);
        if (dv >= 17) {
          this.finishResolve();
          this.botThinking = false;
          msg = this.message;
          WG.queueGameSpeak('blackjack', msg, bjHint(this.status, this.winner));
          return;
        }

        var card = this.draw();
        this.dealerHand.push(card);
        this.lastCard = card;
        this.drawAnimTo = 'dealer';
        this.lastMove = 'dealer_hit';
        dv = bjHandValue(this.dealerHand);
        var rank = bjSpeakCardMode(card);
        if (dv > 21) {
          this.message = WG.speakf(
            'Nhà cái rút lá %s. Tổng nhà cái %d — quắc!',
            'Dealer draws a %s. Dealer total %d — bust!',
            rank, dv
          )[0];
        } else if (dv >= 17) {
          this.message = WG.speakf(
            'Nhà cái rút lá %s. Tổng nhà cái %d — dừng rút.',
            'Dealer draws a %s. Dealer total %d — stands.',
            rank, dv
          )[0];
        } else {
          this.message = WG.speakf(
            'Nhà cái rút lá %s. Tổng nhà cái hiện tại %d — rút tiếp.',
            'Dealer draws a %s. Dealer total is %d — hits again.',
            rank, dv
          )[0];
        }
        msg = this.message;

        WG.queueGameSpeak('blackjack', msg, 'Blackjack. Nhà cái: ' + bjSpeakCardMode(card));
        await WG.sleep(2000);
      }

      if (gen === this.thinkGen && this.botThinking && this.status === 'playing') {
        this.finishResolve();
        this.botThinking = false;
        msg = this.message;
        WG.queueGameSpeak('blackjack', msg, 'Blackjack');
      }
    }
  }

  var inst = null;
  function getBlackjackWeb() {
    if (!inst) inst = new BjGame();
    return inst;
  }

  WG.registerBoardGame({
    name: 'BlackjackWeb',
    exitDefault: 'bjweb',
    summary: function () { return getBlackjackWeb().summaryText(); },
    snapshot: function () { return getBlackjackWeb().snapshot(); },
    reset: function () { return getBlackjackWeb().reset(); },
    setDifficulty: function (s) { return getBlackjackWeb().setDifficulty(s); },
    getDifficulty: function () { return getBlackjackWeb().getDifficulty(); },
    playUCI: function (u) { return getBlackjackWeb().playUCI(u); },
    legalUCIs: function () { return getBlackjackWeb().legalUCIs(); },
    newGameSpeak: function () {
      var say = WG.viOrEN('Ván Blackjack mới. Bấm Bắt đầu để chia bài.', 'New Blackjack round. Press Start to deal.')[0];
      return [say, 'Blackjack. Ván mới.'];
    }
  });
})(window.WG);
