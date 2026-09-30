// Caro freestyle + cấm 2 đầu (double open-3 / double open-4 banned for X / first player).
// Port of wired/mods/caro_engine.go + caro.go.
(function (WG) {
  'use strict';

  var caroN = 15;
  var caroEmpty = WG.caroEmpty;
  var caroX = WG.caroX; // human
  var caroO = WG.caroO; // bot

  var DIRS4 = [[1, 0], [0, 1], [1, 1], [1, -1]];

  function absInt(x) {
    return x < 0 ? -x : x;
  }

  function newBoard() {
    var b = new Array(caroN);
    for (var r = 0; r < caroN; r++) {
      b[r] = new Array(caroN);
      for (var f = 0; f < caroN; f++) b[r][f] = caroEmpty;
    }
    return b;
  }

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

  function CaroGame() {
    this.board = newBoard();
    this.humanTurn = true;
    this.status = 'playing'; // playing | win | draw
    this.winner = ''; // human | bot
    this.lastMove = '';
    this.message = 'Đến lượt bạn (X).';
    this.history = [];
    this.difficulty = WG.diffMedium;
    this.botThinking = false;
    this.thinkGen = 0;
    this.moves = 0;
  }

  var caroInst = null;

  function getCaro() {
    if (caroInst === null) caroInst = new CaroGame();
    return caroInst;
  }

  CaroGame.prototype.boardRows = function () {
    var rows = new Array(caroN);
    for (var r = caroN - 1; r >= 0; r--) {
      var s = '';
      for (var f = 0; f < caroN; f++) {
        var v = this.board[r][f];
        s += v === caroX ? 'X' : v === caroO ? 'O' : '.';
      }
      rows[caroN - 1 - r] = s;
    }
    return rows;
  };

  CaroGame.prototype.snapshot = function () {
    return {
      board: this.boardRows(),
      turn: this.humanTurn ? 'human' : 'bot',
      status: this.status,
      winner: this.winner,
      lastMove: this.lastMove,
      history: this.history.slice(),
      message: this.message,
      youAre: 'X',
      botIs: 'O',
      difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking,
      game: 'caro',
      boardSize: caroN,
      placeMode: true,
      rules: 'freestyle+cam_2_dau'
    };
  };

  CaroGame.prototype.snapshotUnlocked = function () {
    return {
      board: this.boardRows(),
      turn: this.humanTurn ? 'human' : 'bot',
      status: this.status,
      winner: this.winner,
      lastMove: this.lastMove,
      history: this.history.slice(),
      message: this.message,
      youAre: 'X',
      botIs: 'O',
      difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking,
      game: 'caro',
      boardSize: caroN,
      placeMode: true
    };
  };

  CaroGame.prototype.summaryText = function () {
    var turn = this.humanTurn ? 'người chơi (X)' : 'bot (O)';
    var b = 'Cờ caro (gomoku) freestyle trên web Vector, luật cấm 2 đầu cho quân X (cấm tạo đôi 3 mở hoặc đôi 4 mở). ';
    b += 'Bàn ' + caroN + 'x' + caroN + '. Lượt: ' + turn + '. Trạng thái: ' + this.status + '. ';
    if (this.winner !== '') b += 'Người thắng: ' + this.winner + '. ';
    if (this.lastMove !== '') b += 'Nước gần nhất: ' + this.lastMove + '. ';
    b += 'Số nước: ' + this.moves + '. Bàn (hàng ' + (caroN - 1) + '→0): ';
    var rows = this.boardRows();
    for (var i = 0; i < rows.length; i++) {
      b += 'r' + (caroN - 1 - i) + '=' + rows[i] + ' ';
    }
    if (this.history.length > 0) b += 'Lịch sử: ' + this.history.join(' ') + '.';
    return b;
  };

  CaroGame.prototype.reset = function () {
    var prev = WG.normalizeGameDifficulty(this.difficulty);
    // Cancel any pending bot think on the old instance.
    this.thinkGen++;
    this.botThinking = false;
    var ng = new CaroGame();
    ng.difficulty = prev;
    caroInst = ng;
    return ng.snapshot();
  };

  CaroGame.prototype.setDifficulty = function (level) {
    this.difficulty = WG.normalizeGameDifficulty(level);
    return this.difficulty;
  };

  CaroGame.prototype.getDifficulty = function () {
    return WG.normalizeGameDifficulty(this.difficulty);
  };

  function caroLine(board, f, r, df, dr, side) {
    var n = 0;
    for (;;) {
      f += df;
      r += dr;
      if (f < 0 || f >= caroN || r < 0 || r >= caroN || board[r][f] !== side) break;
      n++;
    }
    return n;
  }

  function caroWinAt(board, f, r, side) {
    for (var i = 0; i < DIRS4.length; i++) {
      var d = DIRS4[i];
      var n = 1 + caroLine(board, f, r, d[0], d[1], side) + caroLine(board, f, r, -d[0], -d[1], side);
      if (n >= 5) return true; // freestyle: 5+ wins
    }
    return false;
  }

  // open ends helpers for one direction through a placed stone.
  function caroOpenEnds(board, f, r, df, dr, side) {
    var fwd = caroLine(board, f, r, df, dr, side);
    var bwd = caroLine(board, f, r, -df, -dr, side);
    var length = 1 + fwd + bwd;
    var ef = f + df * (fwd + 1), er = r + dr * (fwd + 1);
    var bf = f - df * (bwd + 1), br = r - dr * (bwd + 1);
    var openA = ef >= 0 && ef < caroN && er >= 0 && er < caroN && board[er][ef] === caroEmpty;
    var openB = bf >= 0 && bf < caroN && br >= 0 && br < caroN && board[br][bf] === caroEmpty;
    return { length: length, openA: openA, openB: openB };
  }

  // countOpenThrees/Fours scans whole board for continuous runs.
  function caroCountOpenPatterns(board, side) {
    var open3 = 0, open4 = 0;
    var seen3 = {};
    var seen4 = {};
    for (var r = 0; r < caroN; r++) {
      for (var f = 0; f < caroN; f++) {
        if (board[r][f] !== side) continue;
        for (var i = 0; i < DIRS4.length; i++) {
          var d = DIRS4[i];
          // only start of a run
          var pf = f - d[0], pr = r - d[1];
          if (pf >= 0 && pf < caroN && pr >= 0 && pr < caroN && board[pr][pf] === side) continue;
          var lenRun = 0;
          var cf = f, cr = r;
          while (cf >= 0 && cf < caroN && cr >= 0 && cr < caroN && board[cr][cf] === side) {
            lenRun++;
            cf += d[0];
            cr += d[1];
          }
          // end cells
          var bf = f - d[0], br = r - d[1];
          var ef = cf, er = cr;
          var openB = bf >= 0 && bf < caroN && br >= 0 && br < caroN && board[br][bf] === caroEmpty;
          var openE = ef >= 0 && ef < caroN && er >= 0 && er < caroN && board[er][ef] === caroEmpty;
          var half = Math.floor(lenRun / 2);
          var midF = f + d[0] * half, midR = r + d[1] * half;
          var key = midF + ',' + midR + ',' + (d[0] * 10 + d[1] + 5);
          if (lenRun === 3 && openB && openE) {
            if (!seen3[key]) {
              seen3[key] = true;
              open3++;
            }
          }
          if (lenRun === 4 && (openB || openE)) {
            if (!seen4[key]) {
              seen4[key] = true;
              open4++;
            }
          }
        }
      }
    }
    return { open3: open3, open4: open4 };
  }

  // cấm 2 đầu for X: placing creates ≥2 open threes OR ≥2 open fours.
  function caroForbiddenDouble(board, side) {
    if (side !== caroX) return false;
    var p = caroCountOpenPatterns(board, side);
    return p.open3 >= 2 || p.open4 >= 2;
  }

  CaroGame.prototype.boardFull = function () {
    for (var r = 0; r < caroN; r++) {
      for (var f = 0; f < caroN; f++) {
        if (this.board[r][f] === caroEmpty) return false;
      }
    }
    return true;
  };

  CaroGame.prototype.legalUCIs = function () {
    if (this.status !== 'playing' || this.botThinking) return null;
    var side = this.humanTurn ? caroX : caroO;
    var out = null;
    for (var r = 0; r < caroN; r++) {
      for (var f = 0; f < caroN; f++) {
        if (this.board[r][f] !== caroEmpty) continue;
        this.board[r][f] = side;
        var ok = !caroForbiddenDouble(this.board, side);
        this.board[r][f] = caroEmpty;
        if (ok) {
          if (out === null) out = [];
          out.push(caroSq(f, r));
        }
      }
    }
    return out;
  };

  CaroGame.prototype.playUCI = function (uci) {
    if (this.botThinking) throw new Error('bot thinking');
    if (this.status !== 'playing') throw new Error('game over');
    if (!this.humanTurn) throw new Error('not your turn');
    var p = parseCaroSq(uci);
    if (!p.ok) throw new Error('bad move');
    var f = p.file, r = p.rank;
    if (this.board[r][f] !== caroEmpty) throw new Error('occupied');
    this.board[r][f] = caroX;
    if (caroForbiddenDouble(this.board, caroX)) {
      this.board[r][f] = caroEmpty;
      throw new Error('cấm 2 đầu (đôi 3 mở / đôi 4 mở)');
    }
    var u = caroSq(f, r);
    this.lastMove = u;
    this.history.push('X:' + u);
    this.moves++;
    var youMove = u;
    if (caroWinAt(this.board, f, r, caroX)) {
      this.status = 'win';
      this.winner = 'human';
      this.message = 'Bạn thắng!';
      this.humanTurn = false;
    } else if (this.boardFull()) {
      this.status = 'draw';
      this.message = 'Hoà.';
      this.humanTurn = false;
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
    var resp = this.snapshotUnlocked();
    resp.youMove = youMove;
    resp.botMove = '';

    WG.queueGameSpeak('caro', WG.buildPlaceSpokenHumanOnly('caro', youMove, resp.status, resp.winner), 'Cờ caro. Nước người chơi: ' + youMove + '.');
    if (needBot) {
      this.runBotThink(thinkGen);
    }
    return resp;
  };

  CaroGame.prototype.runBotThink = async function (gen) {
    // Short sleep for UI/"thinking" only — search is cheap. Hard almost immediate.
    var diff = this.getDifficulty();
    switch (diff) {
      case WG.diffEasy:
        await WG.sleep(700);
        break;
      case WG.diffHard:
        await WG.sleep(120);
        break;
      default:
        await WG.sleep(350);
    }
    if (gen !== this.thinkGen || !this.botThinking) return;
    var botMove = '';
    if (this.status === 'playing' && !this.humanTurn) {
      botMove = this.botPlaceLocked();
    }
    this.botThinking = false;
    if (this.status === 'playing' && this.humanTurn) {
      this.message = 'Đến lượt bạn.';
    }
    var status = this.status, winner = this.winner;
    var summary = 'Cờ caro. Trạng thái: ' + status + '. Nước bot: ' + botMove + '.';
    if (botMove !== '') {
      WG.queueGameSpeak('caro', WG.buildPlaceSpokenBotOnly('caro', botMove, status, winner), summary);
    }
  };

  CaroGame.prototype.botPlaceLocked = function () {
    var board = this.board;
    var hard = WG.normalizeGameDifficulty(this.difficulty) === WG.diffHard;
    var cands = [];
    var r, f;
    for (r = 0; r < caroN; r++) {
      for (f = 0; f < caroN; f++) {
        if (board[r][f] !== caroEmpty) continue;
        // near existing stones only after first moves
        if (this.moves > 0 && !caroNear(board, f, r)) continue;
        var sc = caroEvalPlace(board, f, r, caroO);
        if (hard) {
          // 1-ply: after our move, if opp has an instant win square, devalue
          // (except our own win already scored 1M).
          if (sc < 900000) {
            board[r][f] = caroO;
            if (caroOppHasInstantWin(board, caroX)) sc -= 400000;
            board[r][f] = caroEmpty;
          }
        }
        cands.push({ f: f, r: r, sc: sc });
      }
    }
    if (cands.length === 0) {
      // any empty
      for (r = 0; r < caroN; r++) {
        for (f = 0; f < caroN; f++) {
          if (board[r][f] === caroEmpty) cands.push({ f: f, r: r, sc: 0 });
        }
      }
    }
    if (cands.length === 0) {
      this.status = 'draw';
      this.message = 'Hoà.';
      return '';
    }
    // pick by difficulty
    var best = cands[0];
    var i, c;
    for (i = 0; i < cands.length; i++) {
      if (cands[i].sc > best.sc) best = cands[i];
    }
    switch (WG.normalizeGameDifficulty(this.difficulty)) {
      case WG.diffEasy: {
        // random among top pool — can miss blocks (by design)
        var threshold = best.sc - Math.floor(absInt(best.sc) / 3) - 50;
        if (threshold > best.sc - 800) {
          // keep some blunders even on high scores, but still prefer not pure worst
          threshold = best.sc - 800;
        }
        var pool = [];
        for (i = 0; i < cands.length; i++) {
          c = cands[i];
          if (c.sc >= threshold) pool.push(c);
        }
        if (pool.length === 0) pool = cands;
        best = pool[WG.randInt(pool.length)];
        break;
      }
      case WG.diffHard:
        // stick to best (threat-aware)
        break;
      default: {
        // medium: best score, maybe slight noise only among equal-ish
        var top = [];
        for (i = 0; i < cands.length; i++) {
          c = cands[i];
          if (c.sc >= best.sc - 5) top.push(c);
        }
        if (top.length > 0) best = top[WG.randInt(top.length)];
      }
    }
    board[best.r][best.f] = caroO;
    var u = caroSq(best.f, best.r);
    this.lastMove = u;
    this.history.push('O:' + u);
    this.moves++;
    if (caroWinAt(board, best.f, best.r, caroO)) {
      this.status = 'win';
      this.winner = 'bot';
      this.message = 'Bot thắng.';
      this.humanTurn = false;
    } else if (this.boardFull()) {
      this.status = 'draw';
      this.message = 'Hoà.';
      this.humanTurn = false;
    } else {
      this.humanTurn = true;
    }
    return u;
  };

  function caroNear(board, f, r) {
    for (var dr = -2; dr <= 2; dr++) {
      for (var df = -2; df <= 2; df++) {
        var nr = r + dr, nf = f + df;
        if (nr < 0 || nr >= caroN || nf < 0 || nf >= caroN) continue;
        if (board[nr][nf] !== caroEmpty) return true;
      }
    }
    return false;
  }

  // caroOppHasInstantWin: any empty square where side completes 5+.
  function caroOppHasInstantWin(board, side) {
    for (var r = 0; r < caroN; r++) {
      for (var f = 0; f < caroN; f++) {
        if (board[r][f] !== caroEmpty) continue;
        board[r][f] = side;
        var win = caroWinAt(board, f, r, side);
        board[r][f] = caroEmpty;
        if (win) return true;
      }
    }
    return false;
  }

  // caroEvalPlace: offense + defense on the SAME square.
  function caroEvalPlace(board, f, r, side) {
    var opp = side === caroX ? caroO : caroX;

    // --- if opponent placed here ---
    board[r][f] = opp;
    if (caroWinAt(board, f, r, opp)) {
      board[r][f] = caroEmpty;
      return 500000; // must block win next
    }
    var defThreat = caroThreatScore(board, f, r, opp);
    board[r][f] = caroEmpty;

    // --- if we place here ---
    board[r][f] = side;
    if (side === caroX && caroForbiddenDouble(board, side)) {
      board[r][f] = caroEmpty;
      return -1000000;
    }
    if (caroWinAt(board, f, r, side)) {
      board[r][f] = caroEmpty;
      return 1000000;
    }
    var atk = caroThreatScore(board, f, r, side);
    // open patterns after our move (double live-3 for O is strong force)
    var p = caroCountOpenPatterns(board, side);
    board[r][f] = caroEmpty;

    var sc = atk + defThreat; // defense positive = want to sit on threat squares
    if (p.open4 >= 1) sc += 80000;
    if (p.open3 >= 2) {
      sc += 60000; // double open-3 force (O allowed; X forbidden elsewhere)
    } else if (p.open3 === 1) {
      sc += 8000;
    }
    var cx = Math.floor(caroN / 2), cy = Math.floor(caroN / 2);
    sc += 15 - absInt(f - cx) - absInt(r - cy);
    return sc;
  }

  // caroThreatScore assumes board[r][f] already holds `side`.
  // Ranks classic gomoku threats; 0-open runs (blocked both ends) stay near 0.
  function caroThreatScore(board, f, r, side) {
    var sc = 0;
    for (var i = 0; i < DIRS4.length; i++) {
      var d = DIRS4[i];
      var oe = caroOpenEnds(board, f, r, d[0], d[1], side);
      var length = oe.length;
      var opens = (oe.openA ? 1 : 0) + (oe.openB ? 1 : 0);
      if (length >= 5) sc += 100000;
      else if (length === 4 && opens === 2) sc += 90000; // live four — win force
      else if (length === 4 && opens === 1) sc += 45000; // half-open four
      else if (length === 4 && opens === 0) sc += 5; // dead four
      else if (length === 3 && opens === 2) sc += 12000; // live three
      else if (length === 3 && opens === 1) sc += 900;
      else if (length === 3 && opens === 0) sc += 3;
      else if (length === 2 && opens === 2) sc += 280;
      else if (length === 2 && opens === 1) sc += 40;
      else if (length === 2 && opens === 0) sc += 1;
      else if (length === 1 && opens === 2) sc += 8;
    }
    return sc;
  }

  WG.registerBoardGame({
    name: 'Caro',
    exitDefault: 'caro',
    summary: function () { return getCaro().summaryText(); },
    snapshot: function () { return getCaro().snapshot(); },
    reset: function () { return getCaro().reset(); },
    setDifficulty: function (s) { return getCaro().setDifficulty(s); },
    getDifficulty: function () { return getCaro().getDifficulty(); },
    playUCI: function (u) { return getCaro().playUCI(u); },
    legalUCIs: function () { return getCaro().legalUCIs(); },
    newGameSpeak: function () {
      return WG.speakNew(
        'New caro game. You are X. Freestyle with double-open bans. Your move.',
        'Ván caro mới. Bạn cầm X. Freestyle, cấm 2 đầu. Đến lượt bạn.'
      );
    }
  });
})(window.WG);
