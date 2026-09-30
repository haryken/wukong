// Port of mods/mg_uno.go — Simplified 2-player Uno. Cards are "<color><rank>": colors R,G,B,Y with
// ranks 0-9, S(skip), D(draw2), V(reverse); wild cards are "W" and "W4"
// (no color until chosen). 7 cards dealt to each side.
(function (WG) {
  'use strict';

  function trimPrefix(s, p) {
    return s.indexOf(p) === 0 ? s.slice(p.length) : s;
  }

  function hasPrefix(s, p) {
    return s.indexOf(p) === 0;
  }

  function hasSuffix(s, p) {
    return s.length >= p.length && s.slice(s.length - p.length) === p;
  }

  function shuffleInPlace(arr) {
    var r = WG.shuffle(arr);
    return Array.isArray(r) ? r : arr;
  }

  // Vietnamese and English lines are picked with independent random indices.
  function unoPick(vi, en) {
    if (vi.length === 0) return ['', ''];
    var i = WG.randInt(vi.length);
    var j = i;
    if (en.length > 0) j = WG.randInt(en.length);
    var e = '';
    if (en.length > 0) e = en[j];
    return [vi[i], e];
  }

  function unoColorVI(col) {
    switch (String(col).toUpperCase()) {
      case 'R': return 'đỏ';
      case 'G': return 'xanh lá';
      case 'B': return 'xanh dương';
      case 'Y': return 'vàng';
      default: return col;
    }
  }

  function unoColorEN(col) {
    switch (String(col).toUpperCase()) {
      case 'R': return 'red';
      case 'G': return 'green';
      case 'B': return 'blue';
      case 'Y': return 'yellow';
      default: return col;
    }
  }

  // unoSpeakCard returns a natural spoken description of a card code.
  // For wilds, pass chosenColor (R/G/B/Y) after the player/bot picks a color.
  function unoSpeakCard(card, chosenColor) {
    var c = String(card).trim().toUpperCase();
    var vi, en;
    if (c === 'W') {
      vi = 'đổi màu'; en = 'wild';
      if (chosenColor !== '') {
        vi += ', chọn ' + unoColorVI(chosenColor);
        en += ', chose ' + unoColorEN(chosenColor);
      }
      return [vi, en];
    }
    if (c === 'W4') {
      vi = 'cộng bốn'; en = 'draw four';
      if (chosenColor !== '') {
        vi += ', chọn ' + unoColorVI(chosenColor);
        en += ', chose ' + unoColorEN(chosenColor);
      }
      return [vi, en];
    }
    if (c.length < 2) return [c, c];
    var col = c.slice(0, 1), rank = c.slice(1);
    var cv = unoColorVI(col), ce = unoColorEN(col);
    switch (rank) {
      case 'S': return ['bỏ lượt ' + cv, 'skip ' + ce];
      case 'D': return ['cộng hai ' + cv, 'draw two ' + ce];
      case 'V': return ['đảo chiều ' + cv, 'reverse ' + ce];
      default: return [rank + ' ' + cv, rank + ' ' + ce];
    }
  }

  function unoSpeakCardMode(card, chosenColor) {
    var lang = WG.gameSpeakLang();
    if (lang === 'en') return unoSpeakCard(card, chosenColor)[1];
    if (lang === 'vi') return unoSpeakCard(card, chosenColor)[0];
    return WG.unoSpeakCardLang(card, chosenColor);
  }

  function unoNewDeck() {
    var colors = ['R', 'G', 'B', 'Y'];
    var deck = [];
    var i;
    for (var ci = 0; ci < colors.length; ci++) {
      var c = colors[ci];
      deck.push(c + '0');
      for (var n = 1; n <= 9; n++) {
        deck.push(c + String(n));
        deck.push(c + String(n));
      }
      for (i = 0; i < 2; i++) {
        deck.push(c + 'S'); // skip
        deck.push(c + 'D'); // draw 2
        deck.push(c + 'V'); // reverse (2-player ≈ skip)
      }
    }
    for (i = 0; i < 4; i++) {
      deck.push('W');
      deck.push('W4');
    }
    return shuffleInPlace(deck);
  }

  // Removes and returns the first plain number card found in deck,
  // so the opening discard is never an action/wild card.
  function unoDrawFirstNumberCard(deck) {
    for (var i = 0; i < deck.length; i++) {
      var c = deck[i];
      if (!hasPrefix(c, 'W') && c.length === 2 && c[1] >= '0' && c[1] <= '9') {
        deck.splice(i, 1);
        return c;
      }
    }
    if (deck.length > 0) return deck.shift();
    return 'R0';
  }

  function unoWinMessage(humanWins) {
    var p;
    if (humanWins) {
      p = unoPick(
        ['Xuất sắc! Bạn thắng rồi.', 'Tuyệt vời — tôi sẽ phục thù.', 'Bạn thắng Uno!', 'Chúc mừng chiến thắng!', 'Bạn chơi quá hay.'],
        ['Brilliant! You win.', "Wow — I'll get revenge.", 'You win Uno!', 'Congratulations!', 'You played great.']
      );
      return WG.viOrEN(p[0], p[1])[0];
    }
    p = unoPick(
      ['Tôi thắng rồi!', 'Ván sau nhé.', 'Cố lên lần tới.', 'Robot hết bài — tôi thắng.', 'May mắn chưa về phía bạn.'],
      ['I win!', 'Next game.', 'Better luck next time.', "I'm out — robot wins.", "Luck wasn't with you."]
    );
    return WG.viOrEN(p[0], p[1])[0];
  }

  // Explains the whole bot combo (e.g. +2 then another card)
  // so the UI/speak never hide why the human suddenly got cards.
  function unoBotTurnMessage(played, forcedDraw, forceCard, botLeft) {
    var p;
    if (!played || played.length === 0) {
      p = unoPick(
        ['Ôi không — tôi phải rút bài.', 'Tôi không đánh được.', 'Bạn chơi khá đấy, tôi rút.', 'Hmm, lượt của bạn.', 'May cho bạn, tôi bí bài.'],
        ['Oh no — I have to draw.', "I can't play.", 'Nice play — I draw.', 'Hmm, your turn.', "Lucky you, I'm stuck."]
      );
      return WG.viOrEN(p[0], p[1])[0];
    }
    var partsVI = [];
    var partsEN = [];
    for (var i = 0; i < played.length; i++) {
      var s = unoSpeakCard(played[i], '');
      partsVI.push(s[0]);
      partsEN.push(s[1]);
    }
    var seqVI = partsVI.join(' → ');
    var seqEN = partsEN.join(' → ');

    // Near-win pressure
    if (botLeft === 1) {
      p = unoPick(
        ['Tôi chỉ còn một lá! Cẩn thận nhé.', 'Uno… gần thắng rồi đấy!', 'Còn 1 lá thôi — lo đi!', 'Bạn sắp thua nếu không chặn tôi.', 'Tôi gần về đích rồi.'],
        ['I have one card left! Careful.', 'Uno… almost there!', 'Just one — worry!', "You're in trouble.", "I'm nearly out."]
      );
      return WG.viOrEN(p[0] + ' (' + seqVI + ')', p[1] + ' (' + seqEN + ')')[0];
    }

    if (forcedDraw > 0 && forceCard !== '') {
      var f = unoSpeakCard(forceCard, '');
      var forceVI = f[0], forceEN = f[1];
      var fd = String(forcedDraw);
      p = unoPick(
        [
          'Nhận lấy ' + fd + ' lá nhé! Tôi đánh ' + seqVI + '.',
          'Kế hoạch khá hay: ' + forceVI + '. Bạn rút ' + fd + '.',
          'Đây mới là bất ngờ — ' + forceVI + '!',
          'Tôi đánh ' + seqVI + '. Rút bài đi!',
          'Bạn thật xui: ' + forceVI + '.'
        ],
        [
          'Take ' + fd + ' cards! I played ' + seqEN + '.',
          'Nice plan: ' + forceEN + '. Draw ' + fd + '.',
          'Surprise — ' + forceEN + '!',
          'I played ' + seqEN + '. Draw!',
          'Unlucky: ' + forceEN + '.'
        ]
      );
      return WG.viOrEN(p[0], p[1])[0];
    }
    if (played.length > 1) {
      p = unoPick(
        ['Tôi đánh chuỗi: ' + seqVI + '. Đến lượt bạn.', 'Combo: ' + seqVI + '!', 'Tôi nghĩ đây là nước đi tốt: ' + seqVI + '.', 'Xong chuỗi. Lượt bạn.'],
        ['I played a combo: ' + seqEN + '. Your turn.', 'Combo: ' + seqEN + '!', 'Solid line: ' + seqEN + '.', 'Done. Your turn.']
      );
      return WG.viOrEN(p[0], p[1])[0];
    }
    var last = played[0];
    var nm = unoSpeakCard(last, '');
    var viName = nm[0], enName = nm[1];
    if (hasPrefix(last, 'W')) {
      p = unoPick(
        ['Tôi đổi màu. ' + viName + '.', 'Màu mới đây — tính lại nhé.', 'Wild! Lượt bạn.', 'Tôi chọn màu này.'],
        ['Color change. ' + enName + '.', 'New color — rethink.', 'Wild! Your turn.', 'I pick this color.']
      );
    } else if (last.length > 1 && last.slice(1) === 'S') {
      p = unoPick(
        ['Lượt của bạn bị bỏ! ' + viName + '.', 'Bạn vừa bị chặn.', 'Skip — tôi đánh tiếp…', 'Không đến lượt bạn đâu.'],
        ["You're skipped! " + enName + '.', 'Blocked.', 'Skip — I continue…', 'Not your turn.']
      );
    } else if (last.length > 1 && last.slice(1) === 'V') {
      p = unoPick(
        ['Đổi chiều! ' + viName + '.', 'Mọi thứ đã thay đổi.', 'Reverse — vòng xoay.', 'Chiều chơi đảo lại.'],
        ['Reverse! ' + enName + '.', 'Everything flips.', 'Direction changed.', 'Spinning around.']
      );
    } else {
      p = unoPick(
        ['Tôi chọn lá này: ' + viName + '.', 'Đến lượt bạn.', 'Tôi nghĩ đây là nước đi tốt.', 'Đánh ' + viName + '.', 'Lượt bạn nhé.'],
        ['I pick this: ' + enName + '.', 'Your turn.', 'Solid move.', 'Played ' + enName + '.', 'Go ahead.']
      );
    }
    return WG.viOrEN(p[0], p[1])[0];
  }

  class UnoGame extends WG.MiniCommon {
    constructor() {
      super();
      var deck = unoNewDeck();
      var hand = deck.slice(0, 7);
      deck = deck.slice(7);
      var bot = deck.slice(0, 7);
      deck = deck.slice(7);
      var top = unoDrawFirstNumberCard(deck);

      this.deck = deck;
      this.discard = [top];
      this.hand = hand;
      this.botHand = bot;
      this.top = top;
      this.color = top.slice(0, 1);
      this.direction = 1;
      this.pendingColor = false;
      this.pendingDraw4 = false;
      this.humanLastCard = '';
      this.botLastCard = '';
      this.botTurnCards = [];
      this.botForcedDraw = 0;
      this.botForceCard = '';
      this.drawAnimTo = '';
      this.drawAnimN = 0;
      this.botMustCont = false;
      this.saidUno = false;
      this.justDrew = false;

      this.status = 'playing';
      this.winner = '';
      this.lastMove = '';
      this.history = [];
      this.humanTurn = true;
      this.botThinking = false;
      this.thinkGen = 0;
      this.moves = 0;
      this.difficulty = WG.diffMedium;
      var p = unoPick(
        [
          'Hãy bắt đầu nào! Bạn có 7 lá.',
          'Tôi sẽ cố gắng thắng. Đến lượt bạn.',
          'Tôi chia bài nhé — chúc bạn may mắn!',
          'Uno mới! Cẩn thận với +4 của tôi.',
          'Bắt đầu thôi. Đánh theo màu hoặc số.',
          'Ván mới — hy vọng hôm nay bạn may mắn.',
          'Tôi sẵn sàng. Lượt bạn đi trước.',
          'Chia xong 7 lá mỗi bên. Bắt đầu!'
        ],
        [
          "Let's go! You have 7 cards.",
          "I'll try to win. Your turn.",
          'I dealt the cards — good luck!',
          'New Uno! Watch out for my +4.',
          'Start by matching color or number.',
          'Fresh hand — hope luck is with you.',
          "I'm ready. You go first.",
          "Seven each. Let's play!"
        ]
      );
      this.message = WG.viOrEN(p[0], p[1])[0];
    }

    canPlay(card) {
      if (hasPrefix(card, 'W')) return true;
      if (card.length < 2) return false;
      var color = card.slice(0, 1);
      var rank = card.slice(1);
      var topRank = '';
      if (this.top.length > 1 && !hasPrefix(this.top, 'W')) {
        topRank = this.top.slice(1);
      }
      return color === this.color || (topRank !== '' && rank === topRank);
    }

    // Pops one card from the deck, reshuffling the discard pile
    // (minus the current top) back into the deck if it runs out.
    drawCard() {
      if (this.deck.length === 0) {
        if (this.discard.length <= 1) return '';
        var top = this.discard[this.discard.length - 1];
        var reshuffled = shuffleInPlace(this.discard.slice(0, this.discard.length - 1));
        this.deck = reshuffled;
        this.discard = [top];
      }
      if (this.deck.length === 0) return '';
      return this.deck.pop();
    }

    drawN(target, n) {
      for (var i = 0; i < n; i++) {
        var c = this.drawCard();
        if (c !== '') target.push(c);
      }
    }

    botPickColor() {
      var counts = { R: 0, G: 0, B: 0, Y: 0 };
      for (var i = 0; i < this.botHand.length; i++) {
        var c = this.botHand[i];
        if (!hasPrefix(c, 'W')) {
          var k = c.slice(0, 1);
          counts[k] = (counts[k] || 0) + 1;
        }
      }
      var best = 'R', bestN = -1;
      var cols = ['R', 'G', 'B', 'Y'];
      for (var j = 0; j < cols.length; j++) {
        if (counts[cols[j]] > bestN) {
          best = cols[j];
          bestN = counts[cols[j]];
        }
      }
      return best;
    }

    botWinNow() {
      this.status = 'win';
      this.winner = 'bot';
      this.message = unoWinMessage(false);
      this.humanTurn = false;
      this.botMustCont = false;
      return 'done';
    }

    // Plays a single bot action. Returns:
    //   "draw_pause" — played +2/+4; top card is on the pile; wait for draw anim then continue
    //   "again"      — skip/reverse; brief pause then continue
    //   "done"       — turn handed to human (or game over / bot could not play)
    botPlayOneStep() {
      this.drawAnimTo = '';
      this.drawAnimN = 0;

      var idx = -1;
      var i;
      for (i = 0; i < this.botHand.length; i++) {
        if (this.canPlay(this.botHand[i])) {
          idx = i;
          break;
        }
      }
      if (idx === -1) {
        var dc = this.drawCard();
        if (dc !== '') {
          this.botHand.push(dc);
          if (this.canPlay(dc)) idx = this.botHand.length - 1;
        }
      }
      if (idx === -1) {
        if (this.botTurnCards.length === 0) {
          this.message = unoBotTurnMessage(null, 0, '', this.botHand.length);
        } else {
          this.message = unoBotTurnMessage(this.botTurnCards, this.botForcedDraw, this.botForceCard, this.botHand.length);
        }
        this.humanTurn = true;
        this.botMustCont = false;
        return 'done';
      }

      var card = this.botHand[idx];
      this.botHand.splice(idx, 1);
      this.top = card;
      this.discard.push(card);
      this.lastMove = 'bot:' + card;
      this.botLastCard = card;
      this.botTurnCards.push(card);

      var p;
      if (hasPrefix(card, 'W')) {
        this.color = this.botPickColor();
        if (card === 'W4') {
          this.drawN(this.hand, 4);
          this.botForcedDraw += 4;
          this.botForceCard = card;
          this.drawAnimTo = 'human';
          this.drawAnimN = 4;
          this.botMustCont = true;
          p = unoPick(
            ['Đây mới là bất ngờ — cộng bốn!', 'Bạn phải rút bốn lá.', 'Wild +4 — xin lỗi nhé.', 'Kế hoạch khá hay… với tôi.', 'Rút bốn đi! Tôi chọn màu.'],
            ['Surprise — draw four!', 'You draw four cards.', 'Wild +4 — sorry.', 'Nice plan… for me.', 'Draw four! Color set.']
          );
          this.message = WG.viOrEN(p[0], p[1])[0];
          if (this.botHand.length === 0) return this.botWinNow();
          return 'draw_pause';
        }
      } else {
        this.color = card.slice(0, 1);
        var rank = card.slice(1);
        if (rank === 'S' || rank === 'V') {
          if (rank === 'V') {
            this.direction = -this.direction;
            if (this.direction === 0) this.direction = 1;
          }
          this.botMustCont = true;
          p = unoPick(
            ['Lượt của bạn bị bỏ!', 'Bạn vừa bị chặn.', 'Skip! Tôi đánh tiếp…', 'Đổi chiều — tôi giữ lượt.', 'Không đến lượt bạn đâu.'],
            ["You're skipped!", 'Blocked.', 'Skip — I continue…', 'Reverse — I keep going.', 'Not your turn.']
          );
          this.message = WG.viOrEN(p[0], p[1])[0];
          if (this.botHand.length === 0) return this.botWinNow();
          return 'again';
        } else if (rank === 'D') {
          this.drawN(this.hand, 2);
          this.botForcedDraw += 2;
          this.botForceCard = card;
          this.drawAnimTo = 'human';
          this.drawAnimN = 2;
          this.botMustCont = true;
          p = unoPick(
            ['Nhận lấy hai lá nhé!', 'Cộng hai — rút đi.', 'Kế hoạch khá hay.', '+2 cho bạn.', 'Bạn đang rút hai lá…'],
            ['Take two cards!', 'Draw two.', 'Nice plan.', '+2 for you.', 'Drawing two…']
          );
          this.message = WG.viOrEN(p[0], p[1])[0];
          if (this.botHand.length === 0) return this.botWinNow();
          return 'draw_pause';
        }
      }

      if (this.botHand.length === 0) return this.botWinNow();

      this.botMustCont = false;
      this.humanTurn = true;
      this.message = unoBotTurnMessage(this.botTurnCards, this.botForcedDraw, this.botForceCard, this.botHand.length);
      return 'done';
    }

    snapshot() {
      var dir = this.direction < 0 ? 'ccw' : 'cw';
      var lm = this.lastMove;
      var extra = {
        hand: this.hand.slice(),
        top: this.top,
        botCount: this.botHand.length,
        color: this.color,
        direction: dir,
        deckCount: this.deck.length,
        pendingColor: this.pendingColor,
        pendingDraw4: this.pendingDraw4,
        humanLastCard: this.humanLastCard,
        botLastCard: this.botLastCard,
        botTurnCards: this.botTurnCards.slice(),
        botForcedDraw: this.botForcedDraw,
        botForceCard: this.botForceCard,
        drawAnimTo: this.drawAnimTo,
        drawAnimN: this.drawAnimN,
        botMustCont: this.botMustCont,
        saidUno: this.saidUno,
        justDrew: this.justDrew,
        botJustPlayed: hasPrefix(lm, 'bot:'),
        humanJustPlayed: hasPrefix(lm, 'human:') && !hasSuffix(lm, ':draw') && !hasSuffix(lm, ':pass') && !hasSuffix(lm, ':uno')
      };
      return this.baseSnap('uno', 'uno', extra);
    }

    summaryText() {
      var topVI = unoSpeakCard(this.top, '')[0];
      var colVI = unoColorVI(this.color);
      return WG.sprintf('Uno. Bãi bài: %s. Màu đang chơi: %s. Bạn %d lá, robot %d lá. Trạng thái: %s.',
        topVI, colVI, this.hand.length, this.botHand.length, this.status);
    }

    reset() {
      var prevDiff = this.difficulty;
      this.thinkGen++;
      this.botThinking = false;
      var ng = new UnoGame();
      if (prevDiff) ng.difficulty = prevDiff;
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
      if (this.status !== 'playing' || !this.humanTurn || this.botThinking) return null;
      if (this.pendingColor) return ['color:R', 'color:G', 'color:B', 'color:Y'];
      var out = [];
      for (var i = 0; i < this.hand.length; i++) {
        if (this.canPlay(this.hand[i])) out.push('play:' + this.hand[i]);
      }
      out.push('draw', 'pass');
      if (this.hand.length === 1 && !this.saidUno) out.push('uno');
      return out;
    }

    playUCI(uci) {
      if (this.status !== 'playing') {
        throw new Error('game over');
      }
      if (this.botThinking) {
        throw new Error('robot đang đánh, vui lòng chờ');
      }
      var cmd = String(uci).trim().toLowerCase();
      var needBot = false;
      var thinkGen = 0;
      var p;

      if (hasPrefix(cmd, 'play:')) {
        if (!this.humanTurn) {
          throw new Error('chưa đến lượt bạn');
        }
        if (this.pendingColor) {
          throw new Error('cần chọn màu trước (color:R/G/B/Y)');
        }
        var card = trimPrefix(cmd, 'play:').toUpperCase();
        var idx = this.hand.indexOf(card);
        if (idx === -1) {
          throw new Error(WG.sprintf('bạn không có lá %s', card));
        }
        if (!this.canPlay(card)) {
          throw new Error(WG.sprintf('lá %s không hợp lệ trên lá %s', card, this.top));
        }
        this.hand.splice(idx, 1);
        this.top = card;
        this.discard.push(card);
        this.lastMove = 'human:' + card;
        this.humanLastCard = card;
        this.botForcedDraw = 0;
        this.botForceCard = '';
        this.botTurnCards = [];
        this.drawAnimTo = '';
        this.drawAnimN = 0;
        this.botMustCont = false;
        this.justDrew = false;
        this.moves++;
        this.history.push('H:' + card);

        if (hasPrefix(card, 'W')) {
          this.pendingColor = true;
          this.pendingDraw4 = card === 'W4';
          p = unoPick(
            ['Bạn đánh wild — chọn màu đi!', 'Đổi màu nào?', 'Wild! Màu đỏ? Xanh? Vàng?', 'Chọn màu ở giữa màn hình.', 'Tôi sẽ phải tính toán lại sau khi bạn chọn.'],
            ['Wild — pick a color!', 'Change color?', 'Wild! Red? Green? Blue?', 'Pick a color in the center.', "I'll rethink after your color."]
          );
          this.message = WG.viOrEN(p[0], p[1])[0];
        } else {
          this.color = card.slice(0, 1);
          switch (card.slice(1)) {
            case 'S':
              p = unoPick(
                ['Lượt của tôi bị bỏ!', 'Bạn vừa chặn tôi.', 'Skip — mạnh!', 'Tôi mất lượt rồi.', 'Nước đi hay.'],
                ["I'm skipped!", 'You blocked me.', 'Skip — strong!', 'I lose a turn.', 'Nice move.']
              );
              this.message = WG.viOrEN(p[0], p[1])[0];
              break;
            case 'V':
              this.direction = -this.direction;
              if (this.direction === 0) this.direction = 1;
              p = unoPick(
                ['Đổi chiều!', 'Mọi thứ đã thay đổi.', 'Reverse — vòng xoay.', 'Bạn vừa đảo chiều.', 'Nước đi hay.'],
                ['Reverse!', 'Everything flipped.', 'Direction changed.', 'You reversed it.', 'Nice move.']
              );
              this.message = WG.viOrEN(p[0], p[1])[0];
              break;
            case 'D':
              this.drawN(this.botHand, 2);
              this.drawAnimTo = 'bot';
              this.drawAnimN = 2;
              this.botForceCard = card;
              this.botForcedDraw = 0;
              p = unoPick(
                ['Nhận lấy hai lá nhé… với tôi.', 'Cộng hai! Tôi đang rút.', 'Kế hoạch khá hay.', 'Bạn tàn nhẫn đấy.', 'Tôi phải rút hai lá.'],
                ['Take two… from me.', "Draw two! I'm drawing.", 'Nice plan.', 'Ruthless.', 'I draw two.']
              );
              this.message = WG.viOrEN(p[0], p[1])[0];
              break;
            default:
              this.humanTurn = false;
              p = unoPick(
                ['Nước đi hay.', 'Tôi đã đoán trước.', 'Bạn đang chiếm ưu thế.', 'Ổn. Đến lượt tôi.', 'Được đấy.'],
                ['Nice move.', 'I saw that coming.', "You're ahead.", 'Okay. My turn.', 'Solid.']
              );
              this.message = WG.viOrEN(p[0], p[1])[0];
          }
        }

        if (this.hand.length === 1 && !this.saidUno) {
          var extra = unoPick(
            [' Đừng quên hô UNO!', ' Bạn còn một lá — hô UNO đi!', ' Gần thắng rồi, nhớ UNO!'],
            [" Don't forget UNO!", ' One card left — call UNO!', ' Almost — shout UNO!']
          );
          this.message += extra[0];
        }

        if (this.hand.length === 0) {
          this.status = 'win';
          this.winner = 'human';
          this.pendingColor = false;
          this.pendingDraw4 = false;
          this.message = unoWinMessage(true);
          this.humanTurn = false;
        } else if (!this.humanTurn && !this.pendingColor) {
          needBot = true;
        }
      } else if (cmd === 'draw') {
        if (!this.humanTurn) {
          throw new Error('chưa đến lượt bạn');
        }
        if (this.pendingColor) {
          throw new Error('cần chọn màu trước');
        }
        var c = this.drawCard();
        if (c !== '') {
          this.hand.push(c);
          this.lastMove = 'human:draw';
          this.drawAnimTo = 'human';
          this.drawAnimN = 1;
          this.justDrew = true;
          this.saidUno = false; // drew more cards
          p = unoPick(
            ['Bạn không còn lựa chọn — rút đi.', 'Có vẻ hơi xui.', 'Rút một lá. Đánh được không?', 'Bộ bài thêm một lá.', 'Xui một chút nhé.'],
            ['No choice — draw.', 'Unlucky.', 'Drew one. Can you play?', 'One more card.', 'A bit unlucky.']
          );
          this.message = WG.viOrEN(p[0], p[1])[0];
        } else {
          this.message = WG.viOrEN('Bộ bài đã hết.', 'The deck is empty.')[0];
        }
      } else if (cmd === 'pass') {
        if (!this.humanTurn) {
          throw new Error('chưa đến lượt bạn');
        }
        if (this.pendingColor) {
          throw new Error('cần chọn màu trước');
        }
        this.humanTurn = false;
        this.justDrew = false;
        this.drawAnimTo = '';
        this.drawAnimN = 0;
        this.lastMove = 'human:pass';
        p = unoPick(
          ['Bạn bỏ lượt. Tới tôi.', 'Pass — tôi đánh nhé.', 'Không đánh được à? Được rồi.'],
          ['You pass. My turn.', 'Pass — my go.', "Can't play? Okay."]
        );
        this.message = WG.viOrEN(p[0], p[1])[0];
        needBot = true;
      } else if (cmd === 'uno') {
        if (!this.humanTurn || this.hand.length !== 1) {
          throw new Error('chỉ hô UNO khi còn 1 lá');
        }
        this.saidUno = true;
        this.lastMove = 'human:uno';
        p = unoPick(
          ['UNO! Tôi nghe rồi.', 'Bạn sắp thắng rồi!', 'Đừng quên… bạn đã hô UNO.', 'UNO — nguy hiểm quá!', 'Tốt, bạn đã hô UNO.'],
          ['UNO! Heard you.', "You're about to win!", 'Good call — UNO.', 'UNO — dangerous!', 'Nice UNO shout.']
        );
        this.message = WG.viOrEN(p[0], p[1])[0];
      } else if (hasPrefix(cmd, 'color:')) {
        if (!this.pendingColor) {
          throw new Error('không có lá wild nào đang chờ chọn màu');
        }
        var col = trimPrefix(cmd, 'color:').toUpperCase();
        if (col !== 'R' && col !== 'G' && col !== 'B' && col !== 'Y') {
          throw new Error('màu không hợp lệ (R/G/B/Y)');
        }
        this.color = col;
        this.pendingColor = false;
        var colVI = unoColorVI(col), colEN = unoColorEN(col);
        if (this.pendingDraw4) {
          this.drawN(this.botHand, 4);
          this.pendingDraw4 = false;
          this.drawAnimTo = 'bot';
          this.drawAnimN = 4;
          this.botForceCard = this.humanLastCard;
          this.botForcedDraw = 0;
          p = unoPick(
            ['Bạn đổi sang màu ' + colVI + '. Tôi rút bốn lá…', 'Màu ' + colVI + ' và +4 — tàn nhẫn!', 'Đây mới là bất ngờ. Tôi rút 4.', 'Tôi phải rút bốn lá. Màu ' + colVI + '.'],
            ['You chose ' + colEN + '. I draw four…', colEN + ' and +4 — ruthless!', 'Surprise. Drawing four.', 'Four cards. Color ' + colEN + '.']
          );
          this.message = WG.viOrEN(p[0], p[1])[0];
        } else {
          this.humanTurn = false;
          p = unoPick(
            ['Bạn đổi sang màu ' + colVI + '.', 'Màu ' + colVI + ' sao? Tôi tính lại.', 'Màu mới: ' + colVI + '. Lượt tôi.', 'Tôi sẽ phải tính toán lại.'],
            ['You chose ' + colEN + '.', colEN + "? I'll recalculate.", 'New color: ' + colEN + '. My turn.', 'I need to rethink.']
          );
          this.message = WG.viOrEN(p[0], p[1])[0];
          needBot = true;
        }
      } else {
        throw new Error('lệnh không hợp lệ (play:<card>/draw/pass/uno/color:<X>)');
      }

      if (needBot && this.status === 'playing') {
        this.botThinking = true;
        this.thinkGen++;
        thinkGen = this.thinkGen;
      }

      var resp = this.snapshot();
      var msg = this.message;
      var winner = this.winner;
      var status = this.status;

      var speakHint = 'Uno';
      if (status === 'win' && winner === 'human') {
        speakHint = 'Uno. Chúc mừng! Người chơi thắng.';
      } else if (status === 'win' && winner === 'bot') {
        speakHint = 'Uno. Robot thắng.';
      }
      WG.queueGameSpeak('uno', msg, speakHint);
      if (needBot) {
        this.runBotThink(thinkGen);
      }
      return resp;
    }

    async runBotThink(gen) {
      // Give the human play animation ~2s before the robot responds.
      await WG.sleep(2000);

      // Clear combo trackers at the start of this think.
      if (gen !== this.thinkGen || !this.botThinking) return;
      this.botForcedDraw = 0;
      this.botForceCard = '';
      this.botTurnCards = [];
      this.botMustCont = false;

      var msg;
      for (var step = 0; step < 20; step++) {
        if (gen !== this.thinkGen || !this.botThinking) return;
        if (this.status !== 'playing' || this.humanTurn) {
          this.botThinking = false;
          msg = this.message;
          if (msg !== '') WG.queueGameSpeak('uno', msg, 'Uno');
          return;
        }

        var result = this.botPlayOneStep();
        msg = this.message;
        var card = this.botLastCard;
        var col = this.color;
        var winner = this.winner;
        var status = this.status;
        var drawN = this.drawAnimN;

        if (msg !== '') {
          var hint = 'Uno. Robot: ' + unoSpeakCardMode(card, '');
          if (hasPrefix(card, 'W')) {
            hint = 'Uno. Robot: ' + unoSpeakCardMode(card, col);
          }
          if (status === 'win' && winner === 'bot') {
            hint = 'Uno. Robot thắng.';
          }
          if (drawN > 0) {
            hint = msg;
          }
          WG.queueGameSpeak('uno', msg, hint);
        }

        if (result === 'done') {
          this.botThinking = false;
          this.drawAnimTo = '';
          this.drawAnimN = 0;
          this.botMustCont = false;
          return;
        }

        // Keep +2/+4 (or skip) on the table while draw/skip anim plays, then continue.
        if (result === 'draw_pause') {
          await WG.sleep(2200);
        } else {
          await WG.sleep(900);
        }
      }

      this.botThinking = false;
      this.humanTurn = true;
    }
  }

  var inst = null;
  function getUno() {
    if (!inst) inst = new UnoGame();
    return inst;
  }

  WG.registerBoardGame({
    name: 'Uno',
    exitDefault: 'uno',
    summary: function () { return getUno().summaryText(); },
    snapshot: function () { return getUno().snapshot(); },
    reset: function () { return getUno().reset(); },
    setDifficulty: function (s) { return getUno().setDifficulty(s); },
    getDifficulty: function () { return getUno().getDifficulty(); },
    playUCI: function (u) { return getUno().playUCI(u); },
    legalUCIs: function () { return getUno().legalUCIs(); },
    newGameSpeak: function () {
      var p = unoPick(
        ['Hãy bắt đầu nào!', 'Tôi sẽ cố gắng thắng.', 'Tôi chia bài nhé.', 'Uno mới — chúc may mắn!', 'Sẵn sàng chưa? Bắt đầu!'],
        ["Let's begin!", "I'll try hard to win.", 'Dealing the cards.', 'New Uno — good luck!', "Ready? Let's start!"]
      );
      var say = WG.viOrEN(p[0], p[1])[0];
      return [say, 'Uno. Ván mới.'];
    }
  });
})(window.WG);
