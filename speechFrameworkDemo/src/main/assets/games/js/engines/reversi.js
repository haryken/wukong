// Reversi / Othello 8×8. Human black (X) first, bot white (O).
// Port of wired/mods/reversi_engine.go + reversi.go.
(function (WG) {
  'use strict';

  var revN = 8;
  var caroN = 15;
  var caroEmpty = WG.caroEmpty;
  var caroX = WG.caroX;
  var caroO = WG.caroO;

  var DIRS8 = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]];

  function caroSq(file, rank) {
    return String.fromCharCode(97 + file) + String(rank);
  }

  function parseCaroSq(s) {
    s = String(s == null ? '' : s).trim().toLowerCase();
    if (s.length < 2) return { file: 0, rank: 0, ok: false };
    var file = s.charCodeAt(0) - 97;
    var rank = 0;
    for (var i = 1; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c < 48 || c > 57) return { file: 0, rank: 0, ok: false };
      rank = rank * 10 + (c - 48);
    }
    if (file < 0 || file >= caroN || rank < 0 || rank >= caroN) {
      return { file: 0, rank: 0, ok: false };
    }
    return { file: file, rank: rank, ok: true };
  }

  function RevGame() {
    this.board = new Array(revN);
    for (var r = 0; r < revN; r++) {
      this.board[r] = new Array(revN);
      for (var f = 0; f < revN; f++) this.board[r][f] = caroEmpty;
    }
    this.humanTurn = true;
    this.status = 'playing';
    this.winner = '';
    this.lastMove = '';
    this.message = 'Đến lượt bạn.';
    this.history = [];
    this.difficulty = WG.diffMedium;
    this.botThinking = false;
    this.thinkGen = 0;
    this.passStreak = 0;
    // starting position
    this.board[3][3] = caroO;
    this.board[4][4] = caroO;
    this.board[3][4] = caroX;
    this.board[4][3] = caroX;
  }

  var revInst = null;

  function getReversi() {
    if (revInst === null) revInst = new RevGame();
    return revInst;
  }

  RevGame.prototype.boardRows = function () {
    var rows = new Array(revN);
    for (var r = revN - 1; r >= 0; r--) {
      var s = '';
      for (var f = 0; f < revN; f++) {
        var v = this.board[r][f];
        s += v === caroX ? 'X' : v === caroO ? 'O' : '.';
      }
      rows[revN - 1 - r] = s;
    }
    return rows;
  };

  RevGame.prototype.snapshot = function () {
    return this.snapshotUnlocked();
  };

  RevGame.prototype.snapshotUnlocked = function () {
    var cnt = this.count();
    return {
      board: this.boardRows(), turn: this.humanTurn ? 'human' : 'bot', status: this.status, winner: this.winner,
      lastMove: this.lastMove, history: this.history.slice(), message: this.message,
      youAre: 'X', botIs: 'O', difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking, game: 'reversi', placeMode: true,
      scoreYou: cnt.x, scoreBot: cnt.o, boardSize: revN
    };
  };

  RevGame.prototype.count = function () {
    var x = 0, o = 0;
    for (var r = 0; r < revN; r++) {
      for (var f = 0; f < revN; f++) {
        if (this.board[r][f] === caroX) x++;
        else if (this.board[r][f] === caroO) o++;
      }
    }
    return { x: x, o: o };
  };

  RevGame.prototype.summaryText = function () {
    var cnt = this.count();
    var turn = this.humanTurn ? 'người chơi (đen/X)' : 'bot (trắng/O)';
    var b = 'Reversi/Othello 8×8 trên web Vector. Bạn đen (X), bot trắng (O). ';
    b += 'Lượt: ' + turn + '. Điểm X=' + cnt.x + ' O=' + cnt.o + '. Trạng thái: ' + this.status + '. ';
    if (this.winner !== '') b += 'Thắng: ' + this.winner + '. ';
    if (this.lastMove !== '') b += 'Nước gần nhất: ' + this.lastMove + '. ';
    b += 'Bàn: ';
    var rows = this.boardRows();
    for (var i = 0; i < rows.length; i++) {
      b += 'r' + (revN - 1 - i) + '=' + rows[i] + ' ';
    }
    return b;
  };

  RevGame.prototype.reset = function () {
    var prev = WG.normalizeGameDifficulty(this.difficulty);
    // Cancel any pending bot think on the old instance.
    this.thinkGen++;
    this.botThinking = false;
    var ng = new RevGame();
    ng.difficulty = prev;
    revInst = ng;
    return ng.snapshot();
  };

  RevGame.prototype.setDifficulty = function (level) {
    this.difficulty = WG.normalizeGameDifficulty(level);
    return this.difficulty;
  };

  RevGame.prototype.getDifficulty = function () {
    return WG.normalizeGameDifficulty(this.difficulty);
  };

  function revWouldFlip(board, f, r, side) {
    if (board[r][f] !== caroEmpty) return [];
    var opp = side === caroO ? caroX : caroO;
    var flips = [];
    for (var i = 0; i < DIRS8.length; i++) {
      var d = DIRS8[i];
      var line = [];
      var cf = f + d[0], cr = r + d[1];
      while (cf >= 0 && cf < revN && cr >= 0 && cr < revN && board[cr][cf] === opp) {
        line.push([cf, cr]);
        cf += d[0];
        cr += d[1];
      }
      if (line.length > 0 && cf >= 0 && cf < revN && cr >= 0 && cr < revN && board[cr][cf] === side) {
        for (var j = 0; j < line.length; j++) flips.push(line[j]);
      }
    }
    return flips;
  }

  // Returns null when empty (Go nil slice).
  RevGame.prototype.legalFor = function (side) {
    var out = null;
    for (var r = 0; r < revN; r++) {
      for (var f = 0; f < revN; f++) {
        if (revWouldFlip(this.board, f, r, side).length > 0) {
          if (out === null) out = [];
          out.push(caroSq(f, r)); // ranks 0-7
        }
      }
    }
    return out;
  };

  function lenOf(a) {
    return a === null ? 0 : a.length;
  }

  RevGame.prototype.legalUCIs = function () {
    if (this.status !== 'playing' || this.botThinking) return null;
    var side = this.humanTurn ? caroX : caroO;
    return this.legalFor(side);
  };

  RevGame.prototype.apply = function (f, r, side) {
    var flips = revWouldFlip(this.board, f, r, side);
    this.board[r][f] = side;
    for (var i = 0; i < flips.length; i++) {
      this.board[flips[i][1]][flips[i][0]] = side;
    }
    return flips.length;
  };

  RevGame.prototype.finishIfNeeded = function () {
    var cnt = this.count();
    var hx = cnt.x, ho = cnt.o;
    if (hx + ho === revN * revN || (lenOf(this.legalFor(caroX)) === 0 && lenOf(this.legalFor(caroO)) === 0)) {
      this.status = 'win';
      if (hx > ho) {
        this.winner = 'human';
        this.message = 'Bạn thắng ' + hx + '–' + ho + '!';
      } else if (ho > hx) {
        this.winner = 'bot';
        this.message = 'Bot thắng ' + ho + '–' + hx + '.';
      } else {
        this.status = 'draw';
        this.winner = '';
        this.message = 'Hoà.';
      }
    }
  };

  RevGame.prototype.playUCI = function (uci) {
    if (this.botThinking) throw new Error('bot thinking');
    if (this.status !== 'playing') throw new Error('game over');
    if (!this.humanTurn) throw new Error('not your turn');
    var raw = String(uci == null ? '' : uci);
    // pass support
    if (raw.toLowerCase() === 'pass') {
      if (lenOf(this.legalFor(caroX)) > 0) throw new Error('cannot pass');
      this.history.push('X:pass');
      this.lastMove = 'pass';
      this.humanTurn = false;
      this.message = 'Bot đang suy nghĩ…';
      this.botThinking = true;
      this.thinkGen++;
      var tgPass = this.thinkGen;
      var youMove = 'pass';
      var respPass = this.snapshotUnlocked();
      respPass.youMove = youMove;
      WG.queueGameSpeak('reversi', WG.buildPlaceSpokenHumanOnly('reversi', youMove, 'playing', ''), 'Reversi. Bạn pass.');
      this.runBotThink(tgPass);
      return respPass;
    }
    var p = parseCaroSq(raw);
    if (!p.ok || p.file >= revN || p.rank >= revN) throw new Error('bad move');
    var f = p.file, r = p.rank;
    if (revWouldFlip(this.board, f, r, caroX).length === 0) throw new Error('illegal move');
    var n = this.apply(f, r, caroX);
    var u = caroSq(f, r);
    this.lastMove = u;
    this.history.push('X:' + u + '(+' + n + ')');
    this.finishIfNeeded();
    var needBot = this.status === 'playing';
    var tg = 0;
    if (needBot) {
      this.humanTurn = false;
      this.message = 'Bot đang suy nghĩ…';
      this.botThinking = true;
      this.thinkGen++;
      tg = this.thinkGen;
    }
    var resp = this.snapshotUnlocked();
    resp.youMove = u;
    WG.queueGameSpeak('reversi', WG.buildPlaceSpokenHumanOnly('reversi', u, WG.toStr(resp.status), WG.toStr(resp.winner)), 'Reversi. Bạn: ' + u + '.');
    if (needBot) {
      this.runBotThink(tg);
    }
    return resp;
  };

  RevGame.prototype.runBotThink = async function (gen) {
    await WG.sleep(2000);
    if (gen !== this.thinkGen || !this.botThinking) return;
    var botMove = '';
    if (this.status === 'playing' && !this.humanTurn) {
      botMove = this.botMoveLocked();
    }
    this.botThinking = false;
    if (this.status === 'playing' && this.humanTurn) {
      this.message = 'Đến lượt bạn.';
    }
    var status = this.status, winner = this.winner;
    if (botMove !== '') {
      WG.queueGameSpeak('reversi', WG.buildPlaceSpokenBotOnly('reversi', botMove, status, winner), 'Reversi. Bot: ' + botMove + '.');
    }
  };

  RevGame.prototype.botMoveLocked = function () {
    var moves = this.legalFor(caroO);
    if (lenOf(moves) === 0) {
      // pass
      this.history.push('O:pass');
      this.lastMove = 'pass';
      if (lenOf(this.legalFor(caroX)) === 0) {
        this.finishIfNeeded();
      } else {
        this.humanTurn = true;
      }
      return 'pass';
    }
    // score moves
    var cs = [];
    for (var i = 0; i < moves.length; i++) {
      var u = moves[i];
      var p = parseCaroSq(u);
      var f = p.file, r = p.rank;
      var flips = revWouldFlip(this.board, f, r, caroO);
      var sc = flips.length * 10;
      // corners
      if ((f === 0 || f === 7) && (r === 0 || r === 7)) sc += 200;
      // edges
      if (f === 0 || f === 7 || r === 0 || r === 7) sc += 20;
      cs.push({ f: f, r: r, sc: sc, u: u });
    }
    var best = cs[0];
    for (var j = 0; j < cs.length; j++) {
      if (cs[j].sc > best.sc) best = cs[j];
    }
    if (WG.normalizeGameDifficulty(this.difficulty) === WG.diffEasy && WG.randInt(100) < 40) {
      best = cs[WG.randInt(cs.length)];
    }
    var n = this.apply(best.f, best.r, caroO);
    this.lastMove = best.u;
    this.history.push('O:' + best.u + '(+' + n + ')');
    this.finishIfNeeded();
    if (this.status === 'playing') {
      if (lenOf(this.legalFor(caroX)) === 0) {
        // human must pass — give turn, UI can pass
        this.humanTurn = true;
        this.message = 'Bạn không có nước — bấm ô pass không; hệ thống sẽ cho pass tự khi legal rỗng qua bot? ';
      } else {
        this.humanTurn = true;
      }
    }
    return best.u;
  };

  WG.registerBoardGame({
    name: 'Reversi',
    exitDefault: 'reversi',
    summary: function () { return getReversi().summaryText(); },
    snapshot: function () { return getReversi().snapshot(); },
    reset: function () { return getReversi().reset(); },
    setDifficulty: function (s) { return getReversi().setDifficulty(s); },
    getDifficulty: function () { return getReversi().getDifficulty(); },
    playUCI: function (u) { return getReversi().playUCI(u); },
    legalUCIs: function () { return getReversi().legalUCIs(); },
    newGameSpeak: function () {
      return WG.speakNew(
        'New Reversi. You are black. Your move.',
        'Ván Reversi mới. Bạn cầm đen. Đến lượt bạn.'
      );
    }
  });
})(window.WG);
