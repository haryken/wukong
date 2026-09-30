// Port of mods/mg_poker.go — Simplified 5-card draw poker vs bot: deal 5 each, human may
// discard/redraw once ("draw:1,3"), bot auto-draws, then "show" compares hand ranks.
(function (WG) {
  'use strict';

  var pokerRankOrder = {
    '2': 2, '3': 3, '4': 4, '5': 5, '6': 6, '7': 7, '8': 8, '9': 9, '10': 10,
    'J': 11, 'Q': 12, 'K': 13, 'A': 14
  };

  function trimPrefix(s, p) {
    return s.indexOf(p) === 0 ? s.slice(p.length) : s;
  }

  // strconv.Atoi: optional sign followed by ASCII digits only.
  function atoi(s) {
    if (!/^[+-]?[0-9]+$/.test(s)) return null;
    return parseInt(s, 10);
  }

  function pokerNewDeck() {
    var suits = ['S', 'H', 'D', 'C'];
    var ranks = ['2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K', 'A'];
    var deck = [];
    for (var i = 0; i < suits.length; i++) {
      for (var j = 0; j < ranks.length; j++) {
        deck.push(ranks[j] + suits[i]);
      }
    }
    var r = WG.shuffle(deck);
    return Array.isArray(r) ? r : deck;
  }

  function pokerCardRank(card) {
    if (card.length >= 3 && card[0] === '1' && card[1] === '0') return '10';
    if (card.length === 0) return '';
    return card.slice(0, 1);
  }

  function pokerCardSuit(card) {
    if (card.length >= 3 && card[0] === '1' && card[1] === '0') return card.slice(2);
    if (card.length < 2) return '';
    return card.slice(1);
  }

  function pokerRankVal(rank) {
    return Object.prototype.hasOwnProperty.call(pokerRankOrder, rank) ? pokerRankOrder[rank] : 0;
  }

  function pokerPick(viLines, enLines) {
    if (viLines.length === 0) return ['', ''];
    var i = WG.randInt(viLines.length);
    var vi = viLines[i];
    var en = i < enLines.length ? enLines[i] : vi;
    return [vi, en];
  }

  // pokerEvaluate5 scores a 5-card hand.
  // category 0=high .. 8=straight flush. key is stable for UI/i18n.
  function pokerEvaluate5(hand) {
    var ranks = [];
    var suits = [];
    var i;
    for (i = 0; i < hand.length; i++) {
      ranks.push(pokerRankVal(pokerCardRank(hand[i])));
      suits.push(pokerCardSuit(hand[i]));
    }
    ranks.sort(function (a, b) { return b - a; });

    var flush = true;
    for (i = 1; i < 5; i++) {
      if (suits[i] !== suits[0]) flush = false;
    }

    var dedup = [];
    for (i = 0; i < ranks.length; i++) {
      if (dedup.length === 0 || dedup[dedup.length - 1] !== ranks[i]) dedup.push(ranks[i]);
    }
    var straight = false, straightHigh = 0;
    if (dedup.length === 5) {
      if (dedup[0] - dedup[4] === 4) {
        straight = true; straightHigh = dedup[0];
      } else if (dedup[0] === 14 && dedup[1] === 5 && dedup[2] === 4 && dedup[3] === 3 && dedup[4] === 2) {
        straight = true; straightHigh = 5;
      }
    }

    var counts = {};
    for (i = 0; i < ranks.length; i++) {
      counts[ranks[i]] = (counts[ranks[i]] || 0) + 1;
    }
    var groups = [];
    Object.keys(counts).forEach(function (r) {
      groups.push({ rank: Number(r), count: counts[r] });
    });
    groups.sort(function (a, b) {
      if (a.count !== b.count) return b.count - a.count;
      return b.rank - a.rank;
    });

    function res(category, key, nameVI, nameEN, tiebreak) {
      return { category: category, key: key, nameVI: nameVI, nameEN: nameEN, tiebreak: tiebreak };
    }

    if (straight && flush) {
      return res(8, 'straight_flush', 'THÙNG PHÁ SẢNH', 'STRAIGHT FLUSH', [straightHigh]);
    } else if (groups[0].count === 4) {
      return res(7, 'four', 'TỨ QUÝ', 'FOUR OF A KIND', [groups[0].rank, groups[1].rank]);
    } else if (groups[0].count === 3 && groups.length > 1 && groups[1].count === 2) {
      return res(6, 'full_house', 'CÙ LŨ', 'FULL HOUSE', [groups[0].rank, groups[1].rank]);
    } else if (flush) {
      return res(5, 'flush', 'THÙNG', 'FLUSH', ranks);
    } else if (straight) {
      return res(4, 'straight', 'SẢNH', 'STRAIGHT', [straightHigh]);
    } else if (groups[0].count === 3) {
      return res(3, 'three', 'BỘ BA', 'THREE OF A KIND', [groups[0].rank, groups[1].rank, groups[2].rank]);
    } else if (groups[0].count === 2 && groups.length > 1 && groups[1].count === 2) {
      var hi = groups[0].rank, lo = groups[1].rank;
      if (lo > hi) { var t = hi; hi = lo; lo = t; }
      return res(2, 'two_pair', 'HAI ĐÔI', 'TWO PAIR', [hi, lo, groups[2].rank]);
    } else if (groups[0].count === 2) {
      var tb = [groups[0].rank];
      for (i = 1; i < groups.length; i++) tb.push(groups[i].rank);
      return res(1, 'pair', 'MỘT ĐÔI', 'ONE PAIR', tb);
    }
    return res(0, 'high', 'MẬU THẦU', 'HIGH CARD', ranks);
  }

  function pokerCompareHands(catA, tbA, catB, tbB) {
    if (catA !== catB) return catA > catB ? 1 : -1;
    var n = Math.min(tbA.length, tbB.length);
    for (var i = 0; i < n; i++) {
      if (tbA[i] !== tbB[i]) return tbA[i] > tbB[i] ? 1 : -1;
    }
    return 0;
  }

  function pokerBotDrawIndices(hand) {
    var counts = {};
    var i;
    for (i = 0; i < hand.length; i++) {
      var rk = pokerCardRank(hand[i]);
      counts[rk] = (counts[rk] || 0) + 1;
    }
    var discard = [];
    for (i = 0; i < hand.length; i++) {
      var r = pokerCardRank(hand[i]);
      if (counts[r] >= 2 || pokerRankVal(r) >= 11) continue;
      discard.push(i);
    }
    if (discard.length > 3) discard = discard.slice(0, 3);
    return discard;
  }

  class PokerGame extends WG.MiniCommon {
    constructor() {
      super();
      this.deck = [];
      this.playerHand = [];
      this.botHand = [];
      this.drawn = false;
      this.dealt = false;
      this.drawCount = 0;
      this.roundN = 0;
      // Go's pokerGame.history shadows miniCommon.history; baseSnap still sees the (empty) string history.
      this.pokerHistory = [];
      this.playerCat = 0;
      this.playerKey = '';
      this.playerName = '';
      this.botCat = 0;
      this.botKey = '';
      this.botName = '';
      this.status = 'playing';
      this.winner = '';
      this.lastMove = '';
      this.history = [];
      this.humanTurn = true;
      this.botThinking = false;
      this.thinkGen = 0;
      this.moves = 0;
      this.difficulty = WG.diffMedium;
      var p = pokerPick(
        [
          'Tôi sẽ chia bài cho bạn.',
          'Chúc bạn may mắn.',
          'Hy vọng hôm nay vận may đứng về phía bạn.',
          'Đừng quên chọn những lá bài cần đổi.'
        ],
        [
          "I'll deal you in.",
          'Good luck.',
          'Hope luck is on your side today.',
          "Don't forget which cards to redraw."
        ]
      );
      this.message = WG.viOrEN(p[0], p[1])[0];
    }

    refreshEval() {
      var e;
      if (this.playerHand.length === 5) {
        e = pokerEvaluate5(this.playerHand);
        this.playerCat = e.category;
        this.playerKey = e.key;
        this.playerName = WG.pokerHandSpoken(e.key);
      }
      if (this.botHand.length === 5) {
        e = pokerEvaluate5(this.botHand);
        this.botCat = e.category;
        this.botKey = e.key;
        this.botName = WG.pokerHandSpoken(e.key);
      }
    }

    commentAfterDeal() {
      this.refreshEval();
      var name = this.playerName;
      var p;
      switch (this.playerCat) {
        case 0:
          p = pokerPick(
            ['Bạn đang có mậu thầu. Có vài lá nên đổi.', 'Bộ bài này còn yếu — hãy chọn lá để đổi.', 'Có tiềm năng nếu đổi đúng lá.'],
            ['Just a high card. Some cards should go.', 'This hand is weak — pick cards to redraw.', "There's potential if you redraw well."]
          );
          return WG.viOrEN(p[0], p[1])[0];
        case 1:
          p = pokerPick(
            ['Bạn đã có một đôi. Tôi thấy bộ bài này có tiềm năng.', 'Một đôi là khởi đầu tốt. Nếu là tôi, tôi sẽ giữ đôi đó.', 'Bạn vừa có một đôi — khá ổn.'],
            ["You've got a pair. This hand has potential.", "A pair is a solid start — I'd keep it.", 'One pair already — not bad.']
          );
          return WG.viOrEN(p[0] + ' (' + name + ')', p[1] + ' (' + name + ')')[0];
        case 2:
          p = pokerPick(
            ['Hai đôi rồi! Bộ bài khá mạnh.', 'Bạn đang đi đúng hướng với hai đôi.'],
            ['Two pair already! Strong start.', "You're on the right track with two pair."]
          );
          return WG.viOrEN(p[0], p[1])[0];
        default:
          p = pokerPick(
            ['Wow — ' + name + ' ngay từ đầu!', 'Bộ bài đang rất đẹp: ' + name + '.', 'Tôi sẽ giữ nguyên nếu là bạn.'],
            ['Wow — ' + name + ' right away!', 'Beautiful hand: ' + name + '.', "I'd stand pat if I were you."]
          );
          return WG.viOrEN(p[0], p[1])[0];
      }
    }

    commentAfterDraw() {
      this.refreshEval();
      var p;
      switch (this.playerCat) {
        case 0:
          p = pokerPick(
            ['Chưa được như mong đợi.', 'Vẫn là mậu thầu — không sao.', 'Lần sau sẽ tốt hơn.'],
            ['Not what we hoped for.', "Still high card — that's okay.", 'Next time will be better.']
          );
          return WG.viOrEN(p[0], p[1])[0];
        case 1:
        case 2:
          p = pokerPick(
            ['Tốt hơn rồi. Bạn vừa có ' + this.playerName + '.', 'Bộ bài đang mạnh dần.', 'Có tiềm năng chiến thắng.'],
            ["Better! You've got " + this.playerName + '.', 'The hand is getting stronger.', "There's a chance to win."]
          );
          return WG.viOrEN(p[0], p[1])[0];
        default:
          p = pokerPick(
            ['Rất đẹp! ' + this.playerName + '!', 'Bộ bài cực mạnh sau khi đổi.', 'Khả năng thắng rất cao.'],
            ['Gorgeous! ' + this.playerName + '!', 'Huge hand after the redraw.', "You're looking like a favorite."]
          );
          return WG.viOrEN(p[0], p[1])[0];
      }
    }

    commentShow(result) {
      this.refreshEval();
      var handLineVI = 'Đây là ' + this.playerName + '.';
      var handLineEN = "That's " + this.playerName + '.';
      var p;
      if (result > 0 && this.playerCat >= 6) {
        p = pokerPick(
          ['Xuất sắc! Bạn đã chiến thắng.', 'Tuyệt vời! Bộ bài thật ấn tượng.', 'Tôi biết bạn sẽ làm được.', 'Chúc mừng! Bạn vừa tạo được ' + this.playerName + '.', 'Bạn thật sự rất may mắn hôm nay.'],
          ['Brilliant! You win.', 'Amazing hand!', 'I knew you could do it.', 'Congrats on that ' + this.playerName + '!', 'Luck is really on your side.']
        );
      } else if (result > 0) {
        p = pokerPick(
          ['Bạn thắng với ' + this.playerName + '.', 'Chúc mừng chiến thắng!', 'Kết quả rất đẹp.', 'Chơi thêm một ván nữa nhé.'],
          ['You win with ' + this.playerName + '.', 'Congratulations!', 'Nice result.', "Let's play another."]
        );
      } else if (result < 0) {
        p = pokerPick(
          ['Không sao. Lần sau chúng ta sẽ thắng.', 'Chỉ thiếu một chút may mắn.', 'Robot thắng với ' + this.botName + '.', 'Tôi tin bạn sẽ có bộ bài đẹp hơn.'],
          ['No worries — next time.', 'Just a bit of luck short.', 'I win with ' + this.botName + '.', "You'll get a better hand."]
        );
      } else {
        p = pokerPick(
          ['Hoà! Cả hai đều có ' + this.playerName + '.', 'Push — cân sức.'],
          ['Push! Both have ' + this.playerName + '.', "It's a tie."]
        );
      }
      return WG.viOrEN(handLineVI + ' ' + p[0], handLineEN + ' ' + p[1])[0];
    }

    snapshot() {
      var botShown = this.botHand.map(function () { return '??'; });
      if (this.status !== 'playing') {
        botShown = this.botHand.slice();
      }
      var phase = 'idle';
      if (this.dealt && this.status === 'playing' && !this.drawn) {
        phase = 'select';
      } else if (this.dealt && this.status === 'playing' && this.drawn) {
        phase = 'ready_show';
      } else if (this.status !== 'playing') {
        phase = 'showdown';
      }
      var hist = this.pokerHistory.map(function (h) {
        return { round: h.round, hand: h.hand, result: h.result };
      });
      var extra = {
        playerHand: this.playerHand.slice(),
        botHand: botShown,
        drawn: this.drawn,
        dealt: this.dealt,
        phase: phase,
        drawCount: this.drawCount,
        playerCat: this.playerCat,
        playerCatKey: this.playerKey,
        playerRank: this.playerName,
        history: hist
      };
      if (this.status !== 'playing') {
        extra.botCat = this.botCat;
        extra.botCatKey = this.botKey;
        extra.botRank = this.botName;
        var score = 100 + this.playerCat * 120 - this.drawCount * 20;
        if (score < 50) score = 50;
        if (this.winner !== 'human') score = 0;
        extra.score = score;
      }
      return this.baseSnap('poker', 'cards', extra);
    }

    summaryText() {
      return WG.sprintf('Poker rút bài 5 lá. Đã chia: %v. Đã đổi bài: %v. Trạng thái: %s.',
        this.dealt, this.drawn, this.status);
    }

    reset() {
      var prevDiff = this.difficulty;
      var prevHist = this.pokerHistory.slice();
      var prevRound = this.roundN;
      var ng = new PokerGame();
      if (prevDiff) ng.difficulty = prevDiff;
      ng.pokerHistory = prevHist;
      ng.roundN = prevRound;
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
      if (!this.dealt || this.status !== 'playing') return ['deal'];
      if (!this.drawn) return ['draw:<indices 0-4>', 'show', 'comment:pick', 'comment:unpick'];
      return ['show'];
    }

    playUCI(uci) {
      var cmd = String(uci).trim().toLowerCase();
      var speakOnly = false;
      var p;

      if (cmd === 'deal') {
        if (this.dealt && this.status === 'playing') {
          throw new Error('ván đang chơi — hãy Show hoặc Ván mới');
        }
        this.deck = pokerNewDeck();
        this.playerHand = this.deck.slice(0, 5);
        this.deck = this.deck.slice(5);
        this.botHand = this.deck.slice(0, 5);
        this.deck = this.deck.slice(5);
        this.drawn = false;
        this.dealt = true;
        this.drawCount = 0;
        this.status = 'playing';
        this.winner = '';
        this.humanTurn = true;
        this.moves++;
        this.lastMove = 'deal';
        this.roundN++;
        this.message = this.commentAfterDeal();
      } else if (cmd.indexOf('comment:') === 0) {
        var kind = trimPrefix(cmd, 'comment:');
        if (kind === 'pick') {
          p = pokerPick(
            ['Bạn muốn đổi lá này à?', 'Lựa chọn thú vị.', 'Tôi cũng đang cân nhắc lá đó.', 'Có thể đây là quyết định đúng.'],
            ['Tossing that one?', 'Interesting choice.', 'I was eyeing that card too.', 'Could be the right call.']
          );
        } else {
          p = pokerPick(
            ['Bạn đổi ý rồi sao?', 'Hợp lý.', 'Giữ lại cũng được.'],
            ['Changed your mind?', 'Fair enough.', 'Keeping it works too.']
          );
        }
        this.message = WG.viOrEN(p[0], p[1])[0];
        speakOnly = true;
      } else if (cmd.indexOf('draw:') === 0) {
        if (!this.dealt || this.playerHand.length !== 5) {
          throw new Error('chưa chia bài');
        }
        if (this.drawn) {
          throw new Error('chỉ được đổi bài 1 lần');
        }
        var idxs = [];
        var seen = {};
        var parts = trimPrefix(cmd, 'draw:').split(',');
        for (var k = 0; k < parts.length; k++) {
          var s = parts[k].trim();
          if (s === '') continue;
          var n = atoi(s);
          if (n === null || n < 0 || n > 4 || seen[n]) {
            throw new Error('chỉ số lá không hợp lệ (0-4)');
          }
          seen[n] = true;
          idxs.push(n);
        }
        var i;
        for (i = 0; i < idxs.length; i++) {
          if (this.deck.length === 0) break;
          this.playerHand[idxs[i]] = this.deck.shift();
        }
        this.drawn = true;
        this.drawCount = idxs.length;
        this.moves++;
        this.lastMove = 'draw';
        var botIdx = pokerBotDrawIndices(this.botHand);
        for (i = 0; i < botIdx.length; i++) {
          if (this.deck.length === 0) break;
          this.botHand[botIdx[i]] = this.deck.shift();
        }
        this.message = this.commentAfterDraw();
      } else if (cmd === 'show') {
        if (!this.dealt || this.playerHand.length !== 5) {
          throw new Error('chưa chia bài');
        }
        this.drawn = true;
        this.moves++;
        this.lastMove = 'show';
        var pe = pokerEvaluate5(this.playerHand);
        var be = pokerEvaluate5(this.botHand);
        var cmp = pokerCompareHands(pe.category, pe.tiebreak, be.category, be.tiebreak);
        this.refreshEval();
        var result;
        if (cmp === 1) {
          this.status = 'win'; this.winner = 'human'; result = 'win';
        } else if (cmp === -1) {
          this.status = 'lose'; this.winner = 'bot'; result = 'lose';
        } else {
          this.status = 'draw'; this.winner = ''; result = 'draw';
        }
        this.humanTurn = false;
        this.message = this.commentShow(cmp);
        this.pokerHistory.push({ round: this.roundN, hand: this.playerName, result: result });
        if (this.pokerHistory.length > 8) {
          this.pokerHistory = this.pokerHistory.slice(this.pokerHistory.length - 8);
        }
      } else {
        throw new Error('lệnh không hợp lệ (deal/draw:i,j/show)');
      }

      var resp = this.snapshot();
      var msg = this.message;
      var status = this.status;
      var winner = this.winner;
      var last = this.lastMove;

      var hint = 'Poker';
      if (last === 'deal') {
        hint = 'Poker. Đã chia bài.';
      } else if (last === 'draw') {
        hint = 'Poker. Đổi bài.';
      } else if (last === 'show' && status === 'win' && winner === 'human') {
        hint = 'Poker. Chúc mừng! Bạn thắng.';
      } else if (speakOnly) {
        hint = 'Poker';
      }
      WG.queueGameSpeak('poker', msg, hint);
      return resp;
    }
  }

  var inst = null;
  function getPoker() {
    if (!inst) inst = new PokerGame();
    return inst;
  }

  WG.registerBoardGame({
    name: 'Poker',
    exitDefault: 'poker',
    summary: function () { return getPoker().summaryText(); },
    snapshot: function () { return getPoker().snapshot(); },
    reset: function () { return getPoker().reset(); },
    setDifficulty: function (s) { return getPoker().setDifficulty(s); },
    getDifficulty: function () { return getPoker().getDifficulty(); },
    playUCI: function (u) { return getPoker().playUCI(u); },
    legalUCIs: function () { return getPoker().legalUCIs(); },
    newGameSpeak: function () {
      var p = pokerPick(
        ['Ván Poker mới. Tôi sẽ chia bài cho bạn.', 'Chúc bạn may mắn ở bàn Poker.', 'Hy vọng hôm nay vận may đứng về phía bạn.'],
        ['New Poker round. I\'ll deal you in.', 'Good luck at the table.', 'Hope luck is on your side today.']
      );
      var say = WG.viOrEN(p[0], p[1])[0];
      return [say, 'Poker. Ván mới.'];
    }
  });
})(window.WG);
