// English draughts / checkers 8×8. Human white (bottom ranks 1–3), bot black.
// Port of wired/mods/checkers_engine.go + checkers.go.
(function (WG) {
  'use strict';

  var ckEmpty = 0;
  var ckWM = 1;
  var ckWK = 2;
  var ckBM = 3;
  var ckBK = 4;

  var DIRS = [[1, 1], [1, -1], [-1, 1], [-1, -1]];

  function ckIsWhite(p) { return p === ckWM || p === ckWK; }
  function ckIsBlack(p) { return p === ckBM || p === ckBK; }

  function ckMove(from, to, caps, promo) {
    return { from: from, to: to, caps: caps || null, promo: !!promo };
  }

  function capsLen(m) { return m.caps ? m.caps.length : 0; }

  var ckInst = null;

  function getCheckers() {
    if (ckInst === null) {
      ckInst = newCkGame();
    }
    return ckInst;
  }

  function newCkGame() {
    var g = new CkGame();
    for (var i = 0; i < 64; i++) {
      var r = Math.floor(i / 8), f = i % 8;
      if ((r + f) % 2 === 0) {
        continue;
      }
      if (r <= 2) {
        g.board[i] = ckWM;
      } else if (r >= 5) {
        g.board[i] = ckBM;
      }
    }
    return g;
  }

  function ckName(i) {
    return String.fromCharCode(97 + i % 8) + String(Math.floor(i / 8) + 1);
  }

  function ckParse(s) {
    s = s.toLowerCase().trim();
    if (s.length !== 2) {
      return [0, false];
    }
    var f = s.charCodeAt(0) - 97;
    var r = s.charCodeAt(1) - 49;
    if (f < 0 || f > 7 || r < 0 || r > 7) {
      return [0, false];
    }
    return [r * 8 + f, true];
  }

  function ckWouldPromo(to, p) {
    var r = Math.floor(to / 8);
    return (p === ckWM && r === 7) || (p === ckBM && r === 0);
  }

  function CkGame() {
    this.board = new Array(64).fill(ckEmpty);
    this.whiteTurn = true;
    this.status = 'playing';
    this.winner = '';
    this.lastMove = '';
    this.message = 'Đến lượt bạn.';
    this.history = null;
    this.difficulty = WG.diffMedium;
    this.botThinking = false;
    this.thinkGen = 0;
  }

  CkGame.prototype.boardRows = function () {
    var rows = new Array(8);
    for (var r = 7; r >= 0; r--) {
      var b = '';
      for (var f = 0; f < 8; f++) {
        switch (this.board[r * 8 + f]) {
          case ckWM: b += 'w'; break;
          case ckWK: b += 'W'; break;
          case ckBM: b += 'b'; break;
          case ckBK: b += 'B'; break;
          default: b += '.';
        }
      }
      rows[7 - r] = b;
    }
    return rows;
  };

  CkGame.prototype.snapshot = function () {
    return this.snapshotUnlocked();
  };

  CkGame.prototype.snapshotUnlocked = function () {
    var turn = 'bot';
    if (this.whiteTurn) {
      turn = 'human';
    }
    return {
      board: this.boardRows(), turn: turn, status: this.status, winner: this.winner,
      lastMove: this.lastMove, history: (this.history || []).slice(), message: this.message,
      youAre: 'white', botIs: 'black', difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking, game: 'checkers', placeMode: false, moveMode: true
    };
  };

  CkGame.prototype.summaryText = function () {
    var turn = 'bot (đen)';
    if (this.whiteTurn) {
      turn = 'người chơi (trắng)';
    }
    var b = '';
    b += 'Cờ đam (English draughts/checkers) 8×8 trên web Vector. Bạn trắng, bot đen. Ăn bắt buộc. ';
    b += 'Lượt: ' + turn + '. Trạng thái: ' + this.status + '. ';
    if (this.winner !== '') {
      b += 'Thắng: ' + this.winner + '. ';
    }
    if (this.lastMove !== '') {
      b += 'Nước gần nhất: ' + this.lastMove + '. ';
    }
    b += 'Bàn: ';
    var rows = this.boardRows();
    for (var i = 0; i < rows.length; i++) {
      b += 'r' + (8 - i) + '=' + rows[i] + ' ';
    }
    return b;
  };

  CkGame.prototype.reset = function () {
    var prev = WG.normalizeGameDifficulty(this.difficulty);
    this.thinkGen++;
    this.botThinking = false;
    var ng = newCkGame();
    ng.difficulty = prev;
    ckInst = ng;
    return ng.snapshot();
  };

  CkGame.prototype.setDifficulty = function (level) {
    this.difficulty = WG.normalizeGameDifficulty(level);
    return this.difficulty;
  };

  CkGame.prototype.getDifficulty = function () {
    return WG.normalizeGameDifficulty(this.difficulty);
  };

  CkGame.prototype.legalMovesSimple = function () {
    var white = this.whiteTurn;
    var caps = [];
    var boardCopy = this.board.slice();
    var i, p;
    for (i = 0; i < 64; i++) {
      p = boardCopy[i];
      if (p === ckEmpty) {
        continue;
      }
      if (white && !ckIsWhite(p)) {
        continue;
      }
      if (!white && !ckIsBlack(p)) {
        continue;
      }
      this.collectCaps(boardCopy, i, i, p, null, caps);
    }
    if (caps.length > 0) {
      return caps;
    }
    var quiets = [];
    for (i = 0; i < 64; i++) {
      p = this.board[i];
      if (p === ckEmpty) {
        continue;
      }
      if (white && !ckIsWhite(p)) {
        continue;
      }
      if (!white && !ckIsBlack(p)) {
        continue;
      }
      for (var k = 0; k < DIRS.length; k++) {
        var dr = DIRS[k][0], df = DIRS[k][1];
        if (p === ckWM && dr < 0) {
          continue;
        }
        if (p === ckBM && dr > 0) {
          continue;
        }
        var r = Math.floor(i / 8), f = i % 8;
        var tr = r + dr, tf = f + df;
        if (tr < 0 || tr > 7 || tf < 0 || tf > 7) {
          continue;
        }
        var to = tr * 8 + tf;
        if (this.board[to] !== ckEmpty) {
          continue;
        }
        quiets.push(ckMove(i, to, null, ckWouldPromo(to, p)));
      }
    }
    return quiets;
  };

  CkGame.prototype.collectCaps = function (board, root, cur, piece, caps, out) {
    var r0 = Math.floor(cur / 8), f0 = cur % 8;
    for (var k = 0; k < DIRS.length; k++) {
      var dr = DIRS[k][0], df = DIRS[k][1];
      if (piece === ckWM && dr < 0) {
        continue;
      }
      if (piece === ckBM && dr > 0) {
        continue;
      }
      var mr = r0 + dr, mf = f0 + df;
      var tr = r0 + 2 * dr, tf = f0 + 2 * df;
      if (mr < 0 || mr > 7 || mf < 0 || mf > 7 || tr < 0 || tr > 7 || tf < 0 || tf > 7) {
        continue;
      }
      var mid = mr * 8 + mf, to = tr * 8 + tf;
      var victim = board[mid];
      if (board[to] !== ckEmpty) {
        continue;
      }
      if (ckIsWhite(piece) && !ckIsBlack(victim)) {
        continue;
      }
      if (ckIsBlack(piece) && !ckIsWhite(victim)) {
        continue;
      }
      if (caps && caps.indexOf(mid) >= 0) {
        continue;
      }
      var nb = board.slice();
      nb[cur] = ckEmpty;
      nb[mid] = ckEmpty;
      nb[to] = piece;
      var newCaps = (caps || []).concat([mid]);
      var before = out.length;
      this.collectCaps(nb, root, to, piece, newCaps, out);
      if (out.length === before) {
        out.push(ckMove(root, to, newCaps, ckWouldPromo(to, piece)));
      }
    }
  };

  CkGame.prototype.applyMove = function (m) {
    var p = this.board[m.from];
    this.board[m.from] = ckEmpty;
    if (m.caps) {
      for (var i = 0; i < m.caps.length; i++) {
        this.board[m.caps[i]] = ckEmpty;
      }
    }
    if (m.promo || ckWouldPromo(m.to, p)) {
      if (ckIsWhite(p)) {
        p = ckWK;
      } else {
        p = ckBK;
      }
    }
    this.board[m.to] = p;
  };

  CkGame.prototype.countSide = function (white) {
    var n = 0;
    for (var i = 0; i < 64; i++) {
      var p = this.board[i];
      if (white && ckIsWhite(p)) {
        n++;
      }
      if (!white && ckIsBlack(p)) {
        n++;
      }
    }
    return n;
  };

  CkGame.prototype.legalUCIs = function () {
    if (this.status !== 'playing' || this.botThinking) {
      return null;
    }
    var ms = this.legalMovesSimple();
    var out = [];
    for (var i = 0; i < ms.length; i++) {
      out.push(ckName(ms[i].from) + ckName(ms[i].to));
    }
    return out;
  };

  CkGame.prototype.playUCI = function (uci) {
    if (this.botThinking) {
      throw new Error('bot thinking');
    }
    if (this.status !== 'playing') {
      throw new Error('game over');
    }
    if (!this.whiteTurn) {
      throw new Error('not your turn');
    }
    uci = WG.toStr(uci).toLowerCase().trim();
    if (uci.length < 4) {
      throw new Error('bad move');
    }
    var pf = ckParse(uci.substring(0, 2));
    var pt = ckParse(uci.substring(2, 4));
    if (!pf[1] || !pt[1]) {
      throw new Error('bad squares');
    }
    var from = pf[0], to = pt[0];
    var chosen = null;
    var legal = this.legalMovesSimple();
    for (var i = 0; i < legal.length; i++) {
      if (legal[i].from === from && legal[i].to === to) {
        chosen = legal[i];
        break;
      }
    }
    if (chosen === null) {
      throw new Error('illegal move');
    }
    this.applyMove(chosen);
    var u = ckName(chosen.from) + ckName(chosen.to);
    this.lastMove = u;
    this.history = (this.history || []).concat([u]);
    this.whiteTurn = false;
    var needBot = true;
    if (this.countSide(false) === 0 || this.legalMovesSimple().length === 0) {
      this.status = 'win'; this.winner = 'human'; this.message = 'Bạn thắng!';
      needBot = false;
      this.whiteTurn = true;
    } else {
      this.message = 'Bot đang suy nghĩ…';
    }
    var tg = 0;
    if (needBot) {
      this.botThinking = true;
      this.thinkGen++;
      tg = this.thinkGen;
    }
    var resp = this.snapshotUnlocked();
    resp.youMove = u;
    WG.queueGameSpeak('checkers', WG.buildPlaceSpokenHumanOnly('checkers', u, WG.toStr(resp.status), WG.toStr(resp.winner)), 'Cờ đam. Bạn: ' + u + '.');
    if (needBot) {
      this.runBotThink(tg);
    }
    return resp;
  };

  CkGame.prototype.runBotThink = async function (gen) {
    await WG.sleep(2000);
    if (gen !== this.thinkGen || !this.botThinking) {
      return;
    }
    var botMove = '';
    if (this.status === 'playing' && !this.whiteTurn) {
      botMove = this.botMoveLocked();
    }
    this.botThinking = false;
    if (this.status === 'playing' && this.whiteTurn) {
      this.message = 'Đến lượt bạn.';
    }
    var status = this.status, winner = this.winner;
    if (botMove !== '') {
      WG.queueGameSpeak('checkers', WG.buildPlaceSpokenBotOnly('checkers', botMove, status, winner), 'Cờ đam. Bot: ' + botMove + '.');
    }
  };

  CkGame.prototype.botMoveLocked = function () {
    var ms = this.legalMovesSimple();
    if (ms.length === 0) {
      this.status = 'win'; this.winner = 'human'; this.message = 'Bạn thắng!';
      return '';
    }
    var best = ms[0];
    for (var i = 0; i < ms.length; i++) {
      var m = ms[i];
      if (capsLen(m) > capsLen(best)) {
        best = m;
      } else if (capsLen(m) === capsLen(best) && m.promo && !best.promo) {
        best = m;
      }
    }
    if (WG.normalizeGameDifficulty(this.difficulty) === WG.diffEasy && WG.randInt(100) < 40) {
      best = ms[WG.randInt(ms.length)];
    }
    this.applyMove(best);
    var u = ckName(best.from) + ckName(best.to);
    this.lastMove = u;
    this.history = (this.history || []).concat([u]);
    this.whiteTurn = true;
    if (this.countSide(true) === 0 || this.legalMovesSimple().length === 0) {
      this.status = 'win'; this.winner = 'bot'; this.message = 'Bot thắng.';
    }
    return u;
  };

  getCheckers();

  WG.registerBoardGame({
    name: 'Checkers',
    exitDefault: 'checkers',
    summary: function () { return getCheckers().summaryText(); },
    snapshot: function () { return getCheckers().snapshot(); },
    reset: function () { return getCheckers().reset(); },
    setDifficulty: function (s) { return getCheckers().setDifficulty(s); },
    getDifficulty: function () { return getCheckers().getDifficulty(); },
    playUCI: function (u) { return getCheckers().playUCI(u); },
    legalUCIs: function () { return getCheckers().legalUCIs(); },
    newGameSpeak: function () {
      return WG.speakNew(
        'New checkers game. You are white. Your move.',
        'Ván cờ đam mới. Bạn cầm trắng. Đến lượt bạn.'
      );
    }
  });
})(window.WG);
