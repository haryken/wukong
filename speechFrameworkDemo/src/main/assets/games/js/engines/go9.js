// Micro Go 9×9: capture by liberty, simple Chinese-ish area score at dual pass.
// Human black (X), bot white (O). No superko (positional simple-ko only).
// Port of wired/mods/go9_engine.go + go9.go.
(function (WG) {
  'use strict';

  var goN = 9;
  var caroN = 15;
  var caroEmpty = WG.caroEmpty;
  var caroX = WG.caroX;
  var caroO = WG.caroO;

  var DIRS4 = [[1, 0], [-1, 0], [0, 1], [0, -1]];

  function absInt(x) {
    return x < 0 ? -x : x;
  }

  function caroSq(file, rank) {
    return String.fromCharCode(97 + file) + String(rank);
  }

  function parseCaroSq(s) {
    s = s.toLowerCase().trim();
    if (s.length < 2) {
      return { file: 0, rank: 0, ok: false };
    }
    var file = s.charCodeAt(0) - 97;
    var rank = 0;
    for (var i = 1; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c < 48 || c > 57) {
        return { file: 0, rank: 0, ok: false };
      }
      rank = rank * 10 + (c - 48);
    }
    if (file < 0 || file >= caroN || rank < 0 || rank >= caroN) {
      return { file: 0, rank: 0, ok: false };
    }
    return { file: file, rank: rank, ok: true };
  }

  function newBoard() {
    var b = new Array(goN);
    for (var r = 0; r < goN; r++) {
      b[r] = new Array(goN).fill(caroEmpty);
    }
    return b;
  }

  function copyBoard(src) {
    var b = new Array(goN);
    for (var r = 0; r < goN; r++) {
      b[r] = src[r].slice();
    }
    return b;
  }

  function newSeen() {
    var s = new Array(goN);
    for (var r = 0; r < goN; r++) {
      s[r] = new Array(goN).fill(false);
    }
    return s;
  }

  var goInst = null;

  function getGo9() {
    if (goInst === null) {
      goInst = newGoGame();
    }
    return goInst;
  }

  function newGoGame() {
    return new GoGame();
  }

  function GoGame() {
    this.board = newBoard();
    this.humanTurn = true;
    this.status = 'playing';
    this.winner = '';
    this.lastMove = '';
    this.message = 'Đến lượt bạn (đen).';
    this.history = null;
    this.difficulty = WG.diffMedium;
    this.botThinking = false;
    this.thinkGen = 0;
    this.passStreak = 0;
    this.koF = -1;
    this.koR = -1;
    this.hasKo = false;
  }

  GoGame.prototype.boardRows = function () {
    var rows = new Array(goN);
    for (var r = goN - 1; r >= 0; r--) {
      var b = '';
      for (var f = 0; f < goN; f++) {
        switch (this.board[r][f]) {
          case caroX: b += 'X'; break;
          case caroO: b += 'O'; break;
          default: b += '.';
        }
      }
      rows[goN - 1 - r] = b;
    }
    return rows;
  };

  GoGame.prototype.snapshot = function () {
    return this.snapshotUnlocked();
  };

  GoGame.prototype.snapshotUnlocked = function () {
    var turn = 'bot';
    if (this.humanTurn) {
      turn = 'human';
    }
    return {
      board: this.boardRows(), turn: turn, status: this.status, winner: this.winner,
      lastMove: this.lastMove, history: (this.history || []).slice(), message: this.message,
      youAre: 'X', botIs: 'O', difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking, game: 'go9', boardSize: goN, placeMode: true
    };
  };

  GoGame.prototype.summaryText = function () {
    var turn = 'bot (trắng/O)';
    if (this.humanTurn) {
      turn = 'người chơi (đen/X)';
    }
    var b = '';
    b += 'Cờ vây 9×9 (Go) trên web Vector. Bạn đen, bot trắng. Ăn quân theo khí. Kết thúc khi cả hai pass — chấm điểm vùng đơn giản. ';
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
      b += 'r' + (goN - 1 - i) + '=' + rows[i] + ' ';
    }
    return b;
  };

  GoGame.prototype.reset = function () {
    var prev = WG.normalizeGameDifficulty(this.difficulty);
    this.thinkGen++;
    this.botThinking = false;
    var ng = newGoGame();
    ng.difficulty = prev;
    goInst = ng;
    return ng.snapshot();
  };

  GoGame.prototype.setDifficulty = function (level) {
    this.difficulty = WG.normalizeGameDifficulty(level);
    return this.difficulty;
  };

  GoGame.prototype.getDifficulty = function () {
    return WG.normalizeGameDifficulty(this.difficulty);
  };

  // Returns { stones: [[f,r],...] | null, libs: number of distinct liberties }.
  function goFloodGroup(board, f, r, side, seen) {
    var libs = {};
    var nLibs = 0;
    if (f < 0 || f >= goN || r < 0 || r >= goN || board[r][f] !== side || seen[r][f]) {
      return { stones: null, libs: 0 };
    }
    var stones = [];
    var q = [[f, r]];
    seen[r][f] = true;
    while (q.length > 0) {
      var cur = q.shift();
      stones.push([cur[0], cur[1]]);
      for (var k = 0; k < DIRS4.length; k++) {
        var nf = cur[0] + DIRS4[k][0], nr = cur[1] + DIRS4[k][1];
        if (nf < 0 || nf >= goN || nr < 0 || nr >= goN) {
          continue;
        }
        if (board[nr][nf] === caroEmpty) {
          var key = nr * goN + nf;
          if (!libs[key]) {
            libs[key] = true;
            nLibs++;
          }
        } else if (board[nr][nf] === side && !seen[nr][nf]) {
          seen[nr][nf] = true;
          q.push([nf, nr]);
        }
      }
    }
    return { stones: stones, libs: nLibs };
  }

  // Mutates board in place only on success (like Go *board = b).
  function goTryPlace(board, f, r, side) {
    var fail = { ok: false, capt: 0, koF: -1, koR: -1, hasKo: false };
    if (board[r][f] !== caroEmpty) {
      return fail;
    }
    var b = copyBoard(board);
    b[r][f] = side;
    var opp = caroO;
    if (side === caroO) {
      opp = caroX;
    }
    var seen = newSeen();
    var removed = [];
    var k, i;
    for (k = 0; k < DIRS4.length; k++) {
      var nf = f + DIRS4[k][0], nr = r + DIRS4[k][1];
      if (nf < 0 || nf >= goN || nr < 0 || nr >= goN || b[nr][nf] !== opp) {
        continue;
      }
      if (seen[nr][nf]) {
        continue;
      }
      var grp = goFloodGroup(b, nf, nr, opp, seen);
      if (grp.libs === 0 && grp.stones) {
        for (i = 0; i < grp.stones.length; i++) {
          removed.push(grp.stones[i]);
        }
      }
    }
    for (i = 0; i < removed.length; i++) {
      b[removed[i][1]][removed[i][0]] = caroEmpty;
    }
    var seen2 = newSeen();
    var own = goFloodGroup(b, f, r, side, seen2);
    if (own.libs === 0) {
      return fail;
    }
    var capt = removed.length;
    var koF = 0, koR = 0, hasKo = false;
    if (capt === 1) {
      hasKo = true;
      koF = removed[0][0];
      koR = removed[0][1];
    }
    for (var rr = 0; rr < goN; rr++) {
      board[rr] = b[rr];
    }
    return { ok: true, capt: capt, koF: koF, koR: koR, hasKo: hasKo };
  }

  GoGame.prototype.legalUCIs = function () {
    if (this.status !== 'playing' || this.botThinking) {
      return null;
    }
    var side = caroO;
    if (this.humanTurn) {
      side = caroX;
    }
    var out = ['pass'];
    for (var r = 0; r < goN; r++) {
      for (var f = 0; f < goN; f++) {
        if (this.hasKo && f === this.koF && r === this.koR) {
          continue;
        }
        var b = copyBoard(this.board);
        if (goTryPlace(b, f, r, side).ok) {
          out.push(caroSq(f, r));
        }
      }
    }
    return out;
  };

  GoGame.prototype.scoreArea = function () {
    var black = 0, white = 0;
    var r, f;
    for (r = 0; r < goN; r++) {
      for (f = 0; f < goN; f++) {
        if (this.board[r][f] === caroX) {
          black++;
        } else if (this.board[r][f] === caroO) {
          white++;
        }
      }
    }
    var vis = newSeen();
    for (r = 0; r < goN; r++) {
      for (f = 0; f < goN; f++) {
        if (this.board[r][f] !== caroEmpty || vis[r][f]) {
          continue;
        }
        var q = [[f, r]];
        vis[r][f] = true;
        var region = [[f, r]];
        var touchB = false, touchW = false;
        while (q.length > 0) {
          var cur = q.shift();
          for (var k = 0; k < DIRS4.length; k++) {
            var nf = cur[0] + DIRS4[k][0], nr = cur[1] + DIRS4[k][1];
            if (nf < 0 || nf >= goN || nr < 0 || nr >= goN) {
              continue;
            }
            if (this.board[nr][nf] === caroX) {
              touchB = true;
            } else if (this.board[nr][nf] === caroO) {
              touchW = true;
            } else if (!vis[nr][nf]) {
              vis[nr][nf] = true;
              q.push([nf, nr]);
              region.push([nf, nr]);
            }
          }
        }
        if (touchB && !touchW) {
          black += region.length;
        } else if (touchW && !touchB) {
          white += region.length;
        }
      }
    }
    return [black, white];
  };

  GoGame.prototype.endGame = function () {
    var sc = this.scoreArea();
    var b = sc[0], w = sc[1];
    // komi 6.5 for white approx as 6
    w += 6;
    this.status = 'win';
    if (b > w) {
      this.winner = 'human';
      this.message = 'Bạn thắng ~' + b + '–' + w + ' (ước lượng).';
    } else if (w > b) {
      this.winner = 'bot';
      this.message = 'Bot thắng ~' + w + '–' + b + ' (ước lượng).';
    } else {
      this.status = 'draw'; this.winner = ''; this.message = 'Hoà (ước lượng).';
    }
  };

  GoGame.prototype.playUCI = function (uci) {
    if (this.botThinking) {
      throw new Error('bot thinking');
    }
    if (this.status !== 'playing') {
      throw new Error('game over');
    }
    if (!this.humanTurn) {
      throw new Error('not your turn');
    }
    uci = WG.toStr(uci).toLowerCase().trim();
    var youMove = uci;
    if (uci === 'pass') {
      this.lastMove = 'pass';
      this.history = (this.history || []).concat(['X:pass']);
      this.passStreak++;
      this.hasKo = false;
      if (this.passStreak >= 2) {
        this.endGame();
      } else {
        this.humanTurn = false;
        this.message = 'Bot đang suy nghĩ…';
      }
    } else {
      var p = parseCaroSq(uci);
      var f = p.file, r = p.rank;
      if (!p.ok || f >= goN || r >= goN) {
        throw new Error('bad move');
      }
      if (this.hasKo && f === this.koF && r === this.koR) {
        throw new Error('ko');
      }
      var res = goTryPlace(this.board, f, r, caroX);
      if (!res.ok) {
        throw new Error('illegal (suicide/occupied)');
      }
      var u = caroSq(f, r);
      youMove = u;
      this.lastMove = u;
      if (res.capt > 0) {
        this.history = (this.history || []).concat(['X:' + u + '(+' + res.capt + ')']);
      } else {
        this.history = (this.history || []).concat(['X:' + u]);
      }
      this.passStreak = 0;
      this.hasKo = res.hasKo; this.koF = res.koF; this.koR = res.koR;
      this.humanTurn = false;
      this.message = 'Bot đang suy nghĩ…';
    }
    var needBot = this.status === 'playing';
    var tg = 0;
    if (needBot) {
      this.botThinking = true;
      this.thinkGen++;
      tg = this.thinkGen;
    }
    var resp = this.snapshotUnlocked();
    resp.youMove = youMove;
    WG.queueGameSpeak('go9', WG.buildPlaceSpokenHumanOnly('go9', youMove, WG.toStr(resp.status), WG.toStr(resp.winner)), 'Cờ vây 9×9. Bạn: ' + youMove + '.');
    if (needBot) {
      this.runBotThink(tg);
    }
    return resp;
  };

  GoGame.prototype.runBotThink = async function (gen) {
    await WG.sleep(2000);
    if (gen !== this.thinkGen || !this.botThinking) {
      return;
    }
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
      WG.queueGameSpeak('go9', WG.buildPlaceSpokenBotOnly('go9', botMove, status, winner), 'Cờ vây. Bot: ' + botMove + '.');
    }
  };

  GoGame.prototype.botMoveLocked = function () {
    var cs = [];
    var half = Math.floor(goN / 2);
    for (var r = 0; r < goN; r++) {
      for (var f = 0; f < goN; f++) {
        if (this.hasKo && f === this.koF && r === this.koR) {
          continue;
        }
        var b = copyBoard(this.board);
        var t = goTryPlace(b, f, r, caroO);
        if (!t.ok) {
          continue;
        }
        var sc = t.capt * 100 + 10 - absInt(f - half) - absInt(r - half);
        cs.push({ f: f, r: r, sc: sc, u: caroSq(f, r) });
      }
    }
    if (cs.length === 0 || (WG.normalizeGameDifficulty(this.difficulty) !== WG.diffHard && WG.randInt(100) < 8)) {
      this.lastMove = 'pass';
      this.history = (this.history || []).concat(['O:pass']);
      this.passStreak++;
      this.hasKo = false;
      if (this.passStreak >= 2) {
        this.endGame();
      } else {
        this.humanTurn = true;
      }
      return 'pass';
    }
    var best = cs[0];
    for (var i = 0; i < cs.length; i++) {
      if (cs[i].sc > best.sc) {
        best = cs[i];
      }
    }
    if (WG.normalizeGameDifficulty(this.difficulty) === WG.diffEasy && WG.randInt(100) < 45) {
      best = cs[WG.randInt(cs.length)];
    }
    var res = goTryPlace(this.board, best.f, best.r, caroO);
    this.lastMove = best.u;
    if (res.capt > 0) {
      this.history = (this.history || []).concat(['O:' + best.u + '(+' + res.capt + ')']);
    } else {
      this.history = (this.history || []).concat(['O:' + best.u]);
    }
    this.passStreak = 0;
    this.hasKo = res.hasKo; this.koF = res.koF; this.koR = res.koR;
    this.humanTurn = true;
    return best.u;
  };

  getGo9();

  WG.registerBoardGame({
    name: 'Go9',
    exitDefault: 'go9',
    summary: function () { return getGo9().summaryText(); },
    snapshot: function () { return getGo9().snapshot(); },
    reset: function () { return getGo9().reset(); },
    setDifficulty: function (s) { return getGo9().setDifficulty(s); },
    getDifficulty: function () { return getGo9().getDifficulty(); },
    playUCI: function (u) { return getGo9().playUCI(u); },
    legalUCIs: function () { return getGo9().legalUCIs(); },
    newGameSpeak: function () {
      return WG.speakNew(
        'New 9 by 9 go game. You are black. Your move.',
        'Ván cờ vây 9×9 mới. Bạn cầm đen. Đến lượt bạn.'
      );
    }
  });
})(window.WG);
