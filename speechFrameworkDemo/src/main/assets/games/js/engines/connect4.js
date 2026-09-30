// Connect Four: 7 columns × 6 rows. Human = X drops first, bot = O.
// Port of wired/mods/connect4_engine.go + connect4.go.
(function (WG) {
  'use strict';

  var c4W = 7;
  var c4H = 6;
  var caroEmpty = WG.caroEmpty;
  var caroX = WG.caroX;
  var caroO = WG.caroO;

  var DIRS4 = [[1, 0], [0, 1], [1, 1], [1, -1]];

  function absInt(x) {
    return x < 0 ? -x : x;
  }

  function newBoard() {
    var b = new Array(c4H);
    for (var r = 0; r < c4H; r++) {
      b[r] = new Array(c4W);
      for (var c = 0; c < c4W; c++) b[r][c] = caroEmpty;
    }
    return b;
  }

  function C4Game() {
    this.board = newBoard();
    this.humanTurn = true;
    this.status = 'playing';
    this.winner = '';
    this.lastMove = '';
    this.message = 'Đến lượt bạn.';
    this.history = [];
    this.difficulty = WG.diffMedium;
    this.botThinking = false;
    this.thinkGen = 0;
  }

  var c4Inst = null;

  function getConnect4() {
    if (c4Inst === null) c4Inst = new C4Game();
    return c4Inst;
  }

  C4Game.prototype.boardRows = function () {
    var rows = new Array(c4H);
    for (var r = c4H - 1; r >= 0; r--) {
      var s = '';
      for (var c = 0; c < c4W; c++) {
        var v = this.board[r][c];
        s += v === caroX ? 'X' : v === caroO ? 'O' : '.';
      }
      rows[c4H - 1 - r] = s;
    }
    return rows;
  };

  C4Game.prototype.snapshot = function () {
    return this.snapshotUnlocked();
  };

  C4Game.prototype.snapshotUnlocked = function () {
    return {
      board: this.boardRows(), turn: this.humanTurn ? 'human' : 'bot', status: this.status, winner: this.winner,
      lastMove: this.lastMove, history: this.history.slice(), message: this.message,
      youAre: 'X', botIs: 'O', difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking, game: 'connect4', boardW: c4W, boardH: c4H,
      placeMode: true, dropMode: true
    };
  };

  C4Game.prototype.summaryText = function () {
    var turn = this.humanTurn ? 'người chơi (X)' : 'bot (O)';
    var b = 'Connect Four (cờ thả cột) 7×6 trên web Vector. Bạn X, bot O. Thắng 4 liên tiếp. ';
    b += 'Lượt: ' + turn + '. Trạng thái: ' + this.status + '. ';
    if (this.winner !== '') b += 'Thắng: ' + this.winner + '. ';
    if (this.lastMove !== '') b += 'Nước gần nhất: ' + this.lastMove + '. ';
    b += 'Bàn (trên→dưới): ';
    var rows = this.boardRows();
    for (var i = 0; i < rows.length; i++) {
      b += 'r' + (c4H - 1 - i) + '=' + rows[i] + ' ';
    }
    if (this.history.length > 0) b += 'Lịch sử: ' + this.history.join(' ') + '.';
    return b;
  };

  C4Game.prototype.reset = function () {
    var prev = WG.normalizeGameDifficulty(this.difficulty);
    // Cancel any pending bot think on the old instance.
    this.thinkGen++;
    this.botThinking = false;
    var ng = new C4Game();
    ng.difficulty = prev;
    c4Inst = ng;
    return ng.snapshot();
  };

  C4Game.prototype.setDifficulty = function (level) {
    this.difficulty = WG.normalizeGameDifficulty(level);
    return this.difficulty;
  };

  C4Game.prototype.getDifficulty = function () {
    return WG.normalizeGameDifficulty(this.difficulty);
  };

  C4Game.prototype.dropRow = function (col) {
    for (var r = 0; r < c4H; r++) {
      if (this.board[r][col] === caroEmpty) return r;
    }
    return -1;
  };

  C4Game.prototype.legalUCIs = function () {
    if (this.status !== 'playing' || this.botThinking) return null;
    var out = null;
    for (var c = 0; c < c4W; c++) {
      if (this.dropRow(c) >= 0) {
        if (out === null) out = [];
        out.push(String.fromCharCode(97 + c));
      }
    }
    return out;
  };

  function c4Win(board, r, c, side) {
    for (var i = 0; i < DIRS4.length; i++) {
      var d = DIRS4[i];
      var n = 1;
      var k, nr, nc;
      for (k = 1; k < 4; k++) {
        nr = r + d[1] * k;
        nc = c + d[0] * k;
        if (nr < 0 || nr >= c4H || nc < 0 || nc >= c4W || board[nr][nc] !== side) break;
        n++;
      }
      for (k = 1; k < 4; k++) {
        nr = r - d[1] * k;
        nc = c - d[0] * k;
        if (nr < 0 || nr >= c4H || nc < 0 || nc >= c4W || board[nr][nc] !== side) break;
        n++;
      }
      if (n >= 4) return true;
    }
    return false;
  }

  C4Game.prototype.playUCI = function (uci) {
    if (this.botThinking) throw new Error('bot thinking');
    if (this.status !== 'playing') throw new Error('game over');
    if (!this.humanTurn) throw new Error('not your turn');
    uci = String(uci == null ? '' : uci).trim().toLowerCase();
    if (uci.length < 1) throw new Error('bad move');
    var col = uci.charCodeAt(0) - 97;
    if (col < 0 || col >= c4W) throw new Error('bad column');
    var row = this.dropRow(col);
    if (row < 0) throw new Error('column full');
    this.board[row][col] = caroX;
    var u = String.fromCharCode(97 + col);
    this.lastMove = u;
    this.history.push('X:' + u);
    if (c4Win(this.board, row, col, caroX)) {
      this.status = 'win';
      this.winner = 'human';
      this.message = 'Bạn thắng!';
    } else if (this.full()) {
      this.status = 'draw';
      this.message = 'Hoà.';
    } else {
      this.humanTurn = false;
      this.message = 'Bot đang suy nghĩ…';
    }
    var needBot = this.status === 'playing';
    var thinkGen = 0;
    if (needBot) {
      this.botThinking = true;
      this.thinkGen++;
      thinkGen = this.thinkGen;
    }
    var youMove = u;
    var resp = this.snapshotUnlocked();
    resp.youMove = youMove;
    WG.queueGameSpeak('connect4', WG.buildPlaceSpokenHumanOnly('connect4', youMove, resp.status, WG.toStr(resp.winner)), 'Connect Four. Nước người chơi cột ' + youMove + '.');
    if (needBot) {
      this.runBotThink(thinkGen);
    }
    return resp;
  };

  C4Game.prototype.full = function () {
    for (var c = 0; c < c4W; c++) {
      if (this.dropRow(c) >= 0) return false;
    }
    return true;
  };

  C4Game.prototype.runBotThink = async function (gen) {
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
      WG.queueGameSpeak('connect4', WG.buildPlaceSpokenBotOnly('connect4', botMove, status, winner), 'Connect Four. Bot: ' + botMove + '.');
    }
  };

  C4Game.prototype.botMoveLocked = function () {
    var bestCol = -1, bestSc = -(1 << 30);
    var diff = WG.normalizeGameDifficulty(this.difficulty);
    var depth = 3;
    if (diff === WG.diffEasy) {
      depth = 2; // old medium-hard: still weak vs human
    } else if (diff === WG.diffHard) {
      depth = 6;
    }
    var c, r;
    for (c = 0; c < c4W; c++) {
      r = this.dropRow(c);
      if (r < 0) continue;
      this.board[r][c] = caroO;
      var sc;
      if (c4Win(this.board, r, c, caroO)) {
        sc = 1000000;
      } else {
        sc = -c4Negamax(this, depth - 1, false, -(1 << 20), 1 << 20);
        // prefer center
        sc += (3 - absInt(c - 3)) * 5;
      }
      this.board[r][c] = caroEmpty;
      if (sc > bestSc) {
        bestSc = sc;
        bestCol = c;
      }
    }
    if (bestCol < 0) {
      this.status = 'draw';
      this.message = 'Hoà.';
      return '';
    }
    if (diff === WG.diffEasy && WG.randInt(100) < 35) {
      // sometimes random legal
      var cols = [];
      for (c = 0; c < c4W; c++) {
        if (this.dropRow(c) >= 0) cols.push(c);
      }
      if (cols.length > 0) bestCol = cols[WG.randInt(cols.length)];
    }
    r = this.dropRow(bestCol);
    this.board[r][bestCol] = caroO;
    var u = String.fromCharCode(97 + bestCol);
    this.lastMove = u;
    this.history.push('O:' + u);
    if (c4Win(this.board, r, bestCol, caroO)) {
      this.status = 'win';
      this.winner = 'bot';
      this.message = 'Bot thắng.';
    } else if (this.full()) {
      this.status = 'draw';
      this.message = 'Hoà.';
    } else {
      this.humanTurn = true;
    }
    return u;
  };

  function c4Negamax(g, depth, human, alpha, beta) {
    if (depth === 0) return c4Eval(g.board);
    var side = human ? caroX : caroO;
    var any = false;
    var best = -(1 << 30);
    for (var c = 0; c < c4W; c++) {
      var r = g.dropRow(c);
      if (r < 0) continue;
      any = true;
      g.board[r][c] = side;
      var sc;
      if (c4Win(g.board, r, c, side)) {
        sc = 100000 + depth;
      } else {
        sc = -c4Negamax(g, depth - 1, !human, -beta, -alpha);
      }
      g.board[r][c] = caroEmpty;
      if (sc > best) best = sc;
      if (sc > alpha) alpha = sc;
      if (alpha >= beta) break;
    }
    if (!any) return 0;
    return best;
  }

  function c4Eval(board) {
    // simple: count connectivity favor O (bot)
    var sc = 0;
    for (var r = 0; r < c4H; r++) {
      for (var c = 0; c < c4W; c++) {
        if (board[r][c] === caroO) {
          sc += 3 + (3 - absInt(c - 3));
        } else if (board[r][c] === caroX) {
          sc -= 3 + (3 - absInt(c - 3));
        }
      }
    }
    return sc;
  }

  WG.registerBoardGame({
    name: 'Connect4',
    exitDefault: 'connect4',
    summary: function () { return getConnect4().summaryText(); },
    snapshot: function () { return getConnect4().snapshot(); },
    reset: function () { return getConnect4().reset(); },
    setDifficulty: function (s) { return getConnect4().setDifficulty(s); },
    getDifficulty: function () { return getConnect4().getDifficulty(); },
    playUCI: function (u) { return getConnect4().playUCI(u); },
    legalUCIs: function () { return getConnect4().legalUCIs(); },
    newGameSpeak: function () {
      return WG.speakNew(
        'New Connect Four. You drop first. Your move.',
        'Ván Connect Four mới. Bạn thả trước. Đến lượt bạn.'
      );
    }
  });
})(window.WG);
