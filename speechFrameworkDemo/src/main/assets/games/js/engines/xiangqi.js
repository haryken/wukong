// Port of wired/mods/xiangqi_engine.go + xiangqi.go (human Red vs bot Black).
// Board is 9 files (a-i) x 10 ranks (0-9). sq = rank*9+file.
(function (WG) {
  'use strict';

  var EMPTY = 0;
  function c(s) { return s.charCodeAt(0); }
  var cK = c('K');
  var lk = c('k'), la = c('a'), le = c('e'), lh = c('h'), lr = c('r'), lc = c('c'), lp = c('p');

  var ORTH_D = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  var DIAG_D = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
  var ELEPHANT_D = [[2, 2], [2, -2], [-2, 2], [-2, -2]];
  var HORSE_D = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];

  function isRedXQ(p) { return p >= 65 && p <= 90; }
  function isBlackXQ(p) { return p >= 97 && p <= 122; }
  function isEnemyXQ(cap, side) {
    if (cap === EMPTY) return false;
    return isRedXQ(cap) !== isRedXQ(side);
  }
  function isFriendXQ(cap, side) {
    if (cap === EMPTY) return false;
    return isRedXQ(cap) === isRedXQ(side);
  }
  function xqToLower(p) {
    if (p >= 65 && p <= 90) return p + 32;
    return p;
  }
  function idiv(a, b) { return (a / b) | 0; }
  function abs(x) { return x < 0 ? -x : x; }
  function pieceStr(p) { return String.fromCharCode(p); }

  function mkMove(from, to, capture) {
    return { from: from, to: to, capture: capture };
  }

  function sqNameXQ(i) {
    var r = (i / 9) | 0, f = i % 9;
    return String.fromCharCode(97 + f) + String.fromCharCode(48 + r);
  }

  function parseSqXQ(s) {
    if (s.length !== 2) return { sq: 0, ok: false };
    var f = s.charCodeAt(0) - 97;
    var r = s.charCodeAt(1) - 48;
    if (f < 0 || f > 8 || r < 0 || r > 9) return { sq: 0, ok: false };
    return { sq: r * 9 + f, ok: true };
  }

  function onBoardXQ(r, f) { return r >= 0 && r < 10 && f >= 0 && f < 9; }

  function inPalace(red, r, f) {
    if (f < 3 || f > 5) return false;
    if (red) return r >= 0 && r <= 2;
    return r >= 7 && r <= 9;
  }

  function XiangqiGame() {
    this.board = new Uint8Array(90);
    this.red = true;
    this.history = [];
    this.lastUCI = '';
    this.status = 'playing';
    this.winner = '';
    this.message = 'Ván mới — bạn đi Đỏ. (New game — you are Red.)';
    this.difficulty = WG.diffMedium;
    this.botThinking = false;
    this.thinkGen = 0;
    this.setFENBoard('rheakaehr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RHEAKAEHR');
  }

  var xqInst = null;

  function getXiangqi() {
    if (xqInst === null) xqInst = new XiangqiGame();
    return xqInst;
  }

  XiangqiGame.prototype.setFENBoard = function (fenBoard) {
    var i;
    for (i = 0; i < 90; i++) this.board[i] = EMPTY;
    var rank = 9, file = 0;
    for (i = 0; i < fenBoard.length; i++) {
      var ch = fenBoard.charAt(i);
      if (ch === '/') {
        rank--;
        file = 0;
      } else if (ch >= '1' && ch <= '9') {
        file += ch.charCodeAt(0) - 48;
      } else {
        this.board[rank * 9 + file] = ch.charCodeAt(0);
        file++;
      }
    }
  };

  XiangqiGame.prototype.boardRowsXQ = function () {
    var rows = new Array(10);
    for (var rank = 9; rank >= 0; rank--) {
      var s = '';
      for (var file = 0; file < 9; file++) {
        var p = this.board[rank * 9 + file];
        s += p === EMPTY ? '.' : pieceStr(p);
      }
      rows[9 - rank] = s;
    }
    return rows;
  };

  XiangqiGame.prototype.snapshot = function () {
    return {
      board: this.boardRowsXQ(),
      turn: this.red ? 'red' : 'black',
      status: this.status,
      winner: this.winner,
      lastMove: this.lastUCI,
      history: this.history.slice(),
      message: this.message,
      youAre: 'red',
      botIs: 'black',
      difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking
    };
  };

  XiangqiGame.prototype.summaryText = function () {
    var turn = this.red ? 'đỏ (red / người chơi)' : 'đen (black / bot)';
    var b = 'Cờ tướng trên web Vector. Người chơi cầm Đỏ; bot cầm Đen. ';
    b += 'Lượt hiện tại: ' + turn + '. Trạng thái: ' + this.status + '. ';
    if (this.winner !== '') b += 'Người thắng: ' + this.winner + '. ';
    if (this.lastUCI !== '') b += 'Nước gần nhất: ' + this.lastUCI + '. ';
    b += 'Bàn (hàng 9→0, a→i): ';
    var rows = this.boardRowsXQ();
    for (var i = 0; i < rows.length; i++) b += 'r' + (9 - i) + '=' + rows[i] + ' ';
    if (this.history.length > 0) b += 'Lịch sử nước đi: ' + this.history.join(' ') + '.';
    return b;
  };

  XiangqiGame.prototype.reset = function () {
    var prev = WG.normalizeGameDifficulty(this.difficulty);
    this.thinkGen++;
    this.botThinking = false;
    var ng = new XiangqiGame();
    ng.difficulty = prev;
    xqInst = ng;
    return ng.snapshot();
  };

  XiangqiGame.prototype.setDifficulty = function (level) {
    this.difficulty = WG.normalizeGameDifficulty(level);
    return this.difficulty;
  };

  XiangqiGame.prototype.getDifficulty = function () {
    return WG.normalizeGameDifficulty(this.difficulty);
  };

  XiangqiGame.prototype.kingSqXQ = function (red) {
    var target = red ? cK : lk;
    for (var i = 0; i < 90; i++) {
      if (this.board[i] === target) return i;
    }
    return -1;
  };

  XiangqiGame.prototype.addStep = function (moves, from, to, side) {
    var cap = this.board[to];
    if (isFriendXQ(cap, side)) return;
    moves.push(mkMove(from, to, cap));
  };

  XiangqiGame.prototype.genPseudoAt = function (from) {
    var board = this.board;
    var p = board[from];
    if (p === EMPTY) return [];
    var red = isRedXQ(p);
    var moves = [];
    var r = (from / 9) | 0, f = from % 9;
    var kind = xqToLower(p);
    var i, d, nr, nf, to, cap;

    switch (kind) {
      case lk:
        for (i = 0; i < ORTH_D.length; i++) {
          d = ORTH_D[i];
          nr = r + d[0]; nf = f + d[1];
          if (!inPalace(red, nr, nf)) continue;
          this.addStep(moves, from, nr * 9 + nf, p);
        }
        break;
      case la:
        for (i = 0; i < DIAG_D.length; i++) {
          d = DIAG_D[i];
          nr = r + d[0]; nf = f + d[1];
          if (!inPalace(red, nr, nf)) continue;
          this.addStep(moves, from, nr * 9 + nf, p);
        }
        break;
      case le:
        for (i = 0; i < ELEPHANT_D.length; i++) {
          d = ELEPHANT_D[i];
          nr = r + d[0]; nf = f + d[1];
          if (!onBoardXQ(nr, nf)) continue;
          var eyeR = r + idiv(d[0], 2), eyeF = f + idiv(d[1], 2);
          if (board[eyeR * 9 + eyeF] !== EMPTY) continue;
          if (red && nr > 4) continue;
          if (!red && nr < 5) continue;
          this.addStep(moves, from, nr * 9 + nf, p);
        }
        break;
      case lh:
        for (i = 0; i < HORSE_D.length; i++) {
          d = HORSE_D[i];
          nr = r + d[0]; nf = f + d[1];
          if (!onBoardXQ(nr, nf)) continue;
          var legR, legF;
          if (abs(d[0]) === 2) {
            legR = r + idiv(d[0], 2); legF = f;
          } else {
            legR = r; legF = f + idiv(d[1], 2);
          }
          if (board[legR * 9 + legF] !== EMPTY) continue;
          this.addStep(moves, from, nr * 9 + nf, p);
        }
        break;
      case lr:
        for (i = 0; i < ORTH_D.length; i++) {
          d = ORTH_D[i];
          nr = r + d[0]; nf = f + d[1];
          while (onBoardXQ(nr, nf)) {
            to = nr * 9 + nf;
            cap = board[to];
            if (isFriendXQ(cap, p)) break;
            moves.push(mkMove(from, to, cap));
            if (cap !== EMPTY) break;
            nr += d[0];
            nf += d[1];
          }
        }
        break;
      case lc:
        for (i = 0; i < ORTH_D.length; i++) {
          d = ORTH_D[i];
          nr = r + d[0]; nf = f + d[1];
          var screened = false;
          while (onBoardXQ(nr, nf)) {
            to = nr * 9 + nf;
            cap = board[to];
            if (!screened) {
              if (cap === EMPTY) moves.push(mkMove(from, to, EMPTY));
              else screened = true;
            } else if (cap !== EMPTY) {
              if (isEnemyXQ(cap, p)) moves.push(mkMove(from, to, cap));
              break;
            }
            nr += d[0];
            nf += d[1];
          }
        }
        break;
      case lp:
        var dir = 1;
        var crossed = r >= 5;
        if (!red) {
          dir = -1;
          crossed = r <= 4;
        }
        if (onBoardXQ(r + dir, f)) this.addStep(moves, from, (r + dir) * 9 + f, p);
        if (crossed) {
          for (var df = -1; df <= 1; df += 2) {
            nf = f + df;
            if (onBoardXQ(r, nf)) this.addStep(moves, from, r * 9 + nf, p);
          }
        }
        break;
    }
    return moves;
  };

  XiangqiGame.prototype.genPseudo = function (from) {
    var p = this.board[from];
    if (p === EMPTY) return [];
    if (isRedXQ(p) !== this.red) return [];
    return this.genPseudoAt(from);
  };

  XiangqiGame.prototype.attackedXQ = function (sq, byRed) {
    for (var i = 0; i < 90; i++) {
      var p = this.board[i];
      if (p === EMPTY || isRedXQ(p) !== byRed) continue;
      var ms = this.genPseudoAt(i);
      for (var j = 0; j < ms.length; j++) {
        if (ms[j].to === sq) return true;
      }
    }
    return false;
  };

  XiangqiGame.prototype.kingsFacingXQ = function () {
    var rk = this.kingSqXQ(true);
    var bk = this.kingSqXQ(false);
    if (rk < 0 || bk < 0) return false;
    var rf = rk % 9, bf = bk % 9;
    if (rf !== bf) return false;
    var rr = (rk / 9) | 0, br = (bk / 9) | 0;
    var lo = rr, hi = br;
    if (lo > hi) { lo = br; hi = rr; }
    for (var r = lo + 1; r < hi; r++) {
      if (this.board[r * 9 + rf] !== EMPTY) return false;
    }
    return true;
  };

  XiangqiGame.prototype.inCheckXQ = function (red) {
    var ks = this.kingSqXQ(red);
    if (ks < 0) return true;
    if (this.attackedXQ(ks, !red)) return true;
    return this.kingsFacingXQ();
  };

  XiangqiGame.prototype.applyXQ = function (m) {
    var p = this.board[m.from];
    this.board[m.from] = EMPTY;
    this.board[m.to] = p;
    this.red = !this.red;
  };

  XiangqiGame.prototype.undoXQ = function (m) {
    this.red = !this.red;
    var p = this.board[m.to];
    this.board[m.to] = m.capture;
    this.board[m.from] = p;
  };

  XiangqiGame.prototype.legalMovesXQ = function () {
    var out = [];
    var red = this.red;
    for (var from = 0; from < 90; from++) {
      var ms = this.genPseudo(from);
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        this.applyXQ(m);
        var ok = !this.inCheckXQ(red);
        this.undoXQ(m);
        if (ok) out.push(m);
      }
    }
    return out;
  };

  XiangqiGame.prototype.refreshStatus = function () {
    var legal = this.legalMovesXQ();
    var check = this.inCheckXQ(this.red);
    if (legal.length === 0) {
      if (check) {
        this.status = 'checkmate';
        if (this.red) {
          this.winner = 'black';
          this.message = 'Chiếu bí — Đen (bot) thắng. (Checkmate — Black wins.)';
        } else {
          this.winner = 'red';
          this.message = 'Chiếu bí — Đỏ thắng. (Checkmate — Red wins.)';
        }
      } else {
        this.status = 'stalemate';
        this.winner = '';
        this.message = 'Hết nước đi — hòa. (Stalemate — draw.)';
      }
      return;
    }
    if (check) {
      this.status = 'check';
      this.message = 'Chiếu! (Check!)';
    } else {
      this.status = 'playing';
      if (this.red) this.message = 'Lượt Đỏ (bạn). (Red to move — your turn.)';
      else this.message = "Lượt Đen (bot). (Black to move — bot's turn.)";
    }
  };

  function moveUCIXQ(m) {
    return sqNameXQ(m.from) + sqNameXQ(m.to);
  }

  XiangqiGame.prototype.playUCI = function (uci) {
    if (this.botThinking) throw new Error('bot thinking');
    if (this.status === 'checkmate' || this.status === 'stalemate') throw new Error('game over');
    uci = String(uci == null ? '' : uci).toLowerCase().trim();
    if (uci.length < 4) throw new Error('bad move');
    var a = parseSqXQ(uci.substring(0, 2));
    var b = parseSqXQ(uci.substring(2, 4));
    if (!a.ok || !b.ok) throw new Error('bad squares');
    if (!this.red) throw new Error('not your turn');
    var from = a.sq, to = b.sq;
    var chosen = null;
    var legal = this.legalMovesXQ();
    for (var i = 0; i < legal.length; i++) {
      var m = legal[i];
      if (m.from === from && m.to === to) {
        chosen = mkMove(m.from, m.to, m.capture);
        break;
      }
    }
    if (chosen === null) throw new Error('illegal move');
    var youPiece = this.board[chosen.from];
    this.applyXQ(chosen);
    var u = moveUCIXQ(chosen);
    this.lastUCI = u;
    this.history.push(u);
    var youMove = u;
    this.refreshStatus();
    var needBot = this.status !== 'checkmate' && this.status !== 'stalemate' && !this.red;
    var thinkGen = 0;
    var statusAfterYou = this.status;
    var winnerAfterYou = this.winner;
    if (needBot) {
      this.botThinking = true;
      this.thinkGen++;
      thinkGen = this.thinkGen;
      this.message = 'Bot đang suy nghĩ…';
    }

    var resp = {
      board: this.boardRowsXQ(), turn: this.red ? 'red' : 'black',
      status: this.status, winner: this.winner, lastMove: this.lastUCI,
      history: this.history.slice(), message: this.message,
      youAre: 'red', botIs: 'black',
      youMove: youMove, botMove: '',
      youPiece: pieceStr(youPiece), botPiece: '',
      difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: needBot
    };

    if (youMove !== '') {
      var stSpeak = statusAfterYou;
      var winSpeak = winnerAfterYou;
      if (needBot && stSpeak !== 'checkmate' && stSpeak !== 'stalemate') {
        if (stSpeak !== 'check') stSpeak = 'playing';
        winSpeak = '';
      }
      var xqHint = 'Cờ tướng. Nước người chơi: ' + youMove + '.';
      WG.queueGameSpeak('xiangqi', WG.buildXiangqiSpokenComment(youMove, '', pieceStr(youPiece), '', stSpeak, winSpeak), xqHint);
    }

    if (needBot) {
      this.runBotThinkXQ(thinkGen).catch(function (e) {
        if (window.console) console.error('[Xiangqi] bot think:', e);
      });
    }
    return resp;
  };

  XiangqiGame.prototype.runBotThinkXQ = async function (gen) {
    var diff = this.getDifficulty();
    switch (WG.normalizeGameDifficulty(diff)) {
      case WG.diffEasy:
        await WG.sleep(900);
        break;
      case WG.diffHard:
        await WG.sleep(200);
        break;
      default:
        await WG.sleep(500);
    }
    if (gen !== this.thinkGen || !this.botThinking) return;
    var botMove = '';
    var botPiece = 0;
    if (this.status !== 'checkmate' && this.status !== 'stalemate' && !this.red) {
      botPiece = this.botMoveLocked();
      botMove = this.lastUCI;
    }
    this.botThinking = false;
    if (this.red && (this.status === 'playing' || this.status === 'check')) {
      if (this.status === 'check') this.message = 'Đến lượt bạn — đang chiếu!';
      else this.message = 'Đến lượt bạn.';
    }
    var status = this.status;
    var winner = this.winner;
    var summary = 'Cờ tướng. Trạng thái: ' + this.status + '.';
    if (this.lastUCI !== '') summary += ' Nước gần nhất: ' + this.lastUCI + '.';

    if (botMove !== '') {
      WG.queueGameSpeak('xiangqi', WG.buildXiangqiSpokenComment('', botMove, '', pieceStr(botPiece), status, winner), summary);
    }
  };

  XiangqiGame.prototype.botMoveLocked = function () {
    var legal = this.legalMovesXQ();
    if (legal.length === 0) {
      this.refreshStatus();
      return 0;
    }
    var best;
    switch (WG.normalizeGameDifficulty(this.difficulty)) {
      case WG.diffEasy:
        best = this.pickXQSearch(legal, 2, 35);
        break;
      case WG.diffHard:
        best = this.pickXQHard(legal);
        break;
      default:
        best = this.pickXQSearch(legal, 3, 4);
    }
    var piece = this.board[best.from];
    this.applyXQ(best);
    var u = moveUCIXQ(best);
    this.lastUCI = u;
    this.history.push(u);
    this.refreshStatus();
    return piece;
  };

  XiangqiGame.prototype.staticEvalRedPOV = function () {
    var score = 0;
    for (var i = 0; i < 90; i++) {
      var p = this.board[i];
      if (p === EMPTY) continue;
      var v = materialXQ(p) + xqPST(p, i);
      if (isRedXQ(p)) score += v;
      else score -= v;
    }
    return score;
  };

  function xqPST(p, sq) {
    var r = (sq / 9) | 0, f = sq % 9;
    var center = 0;
    if (f >= 2 && f <= 6 && r >= 3 && r <= 6) center = 8;
    var bonus;
    switch (xqToLower(p)) {
      case lp:
        if (isRedXQ(p)) {
          bonus = r * 6;
          if (r >= 5) bonus += 18;
          return bonus + idiv(center, 2);
        }
        bonus = (9 - r) * 6;
        if (r <= 4) bonus += 18;
        return bonus + idiv(center, 2);
      case lc:
        if (isRedXQ(p)) return center + r * 2;
        return center + (9 - r) * 2;
      case lh:
        return center + 4;
      case lr:
        return center;
      case lk:
        if (f === 4) return 12;
        return 2;
      default:
        return idiv(center, 2);
    }
  }

  XiangqiGame.prototype.evaluateSTMXQ = function () {
    var s = this.staticEvalRedPOV();
    if (this.red) return s;
    return -s;
  };

  XiangqiGame.prototype.xqSEE = function (m) {
    var mover = this.board[m.from];
    if (mover === EMPTY) return 0;
    var gain = materialXQ(m.capture);
    this.applyXQ(m);
    if (this.attackedXQ(m.to, this.red)) gain -= materialXQ(this.board[m.to]);
    var checking = this.inCheckXQ(this.red);
    this.undoXQ(m);
    if (checking && gain >= idiv(-materialXQ(mover), 2)) {
      if (gain < 0) gain += 20;
    }
    return gain;
  };

  function xqMoveOrder(m) {
    if (m.capture !== EMPTY) return 1000 + materialXQ(m.capture);
    return 0;
  }

  XiangqiGame.prototype.orderMovesXQ = function (legal) {
    var ss = new Array(legal.length);
    var i, j, t;
    for (i = 0; i < legal.length; i++) {
      var m = legal[i];
      var s = xqMoveOrder(m);
      if (m.capture !== EMPTY) s += this.xqSEE(m);
      ss[i] = { m: m, s: s };
    }
    for (i = 0; i < ss.length; i++) {
      for (j = i + 1; j < ss.length; j++) {
        if (ss[j].s > ss[i].s) {
          t = ss[i]; ss[i] = ss[j]; ss[j] = t;
        }
      }
    }
    var order = new Array(ss.length);
    for (i = 0; i < ss.length; i++) order[i] = ss[i].m;
    return order;
  };

  XiangqiGame.prototype.pickXQSearch = function (legal, depth, noisePct) {
    if (depth < 1) depth = 1;
    var order = this.orderMovesXQ(legal);
    var best = order[0];
    var bestScore = -999999;
    var alpha = -999999, beta = 999999;
    var i, m, sc;
    for (i = 0; i < order.length; i++) {
      m = order[i];
      if (noisePct < 20 && m.capture !== EMPTY && this.xqSEE(m) < -15) continue;
      this.applyXQ(m);
      sc = -this.negamaxXQ(depth - 1, -beta, -alpha, true);
      this.undoXQ(m);
      if (sc > bestScore) {
        bestScore = sc;
        best = m;
      }
      if (sc > alpha) alpha = sc;
    }
    if (bestScore <= -999990) {
      return this.orderMovesXQ(legal)[0];
    }
    if (noisePct > 0 && WG.randInt(100) < noisePct && order.length > 1) {
      var pool = [];
      for (i = 0; i < order.length; i++) {
        m = order[i];
        if (m.capture !== EMPTY && this.xqSEE(m) < -30) continue;
        this.applyXQ(m);
        sc = -this.negamaxXQ(depth - 1, -999999, 999999, true);
        this.undoXQ(m);
        if (sc >= bestScore - 80) pool.push(m);
      }
      if (pool.length > 0) return pool[WG.randInt(pool.length)];
    }
    return best;
  };

  XiangqiGame.prototype.pickXQHard = function (legal) {
    var order = this.orderMovesXQ(legal);
    var filtered = [];
    var i, m;
    for (i = 0; i < order.length; i++) {
      m = order[i];
      if (m.capture !== EMPTY && this.xqSEE(m) < 0) continue;
      filtered.push(m);
    }
    if (filtered.length === 0) filtered = order;
    else order = filtered;
    var best = order[0];
    var maxDepth = 3;
    for (var depth = 1; depth <= maxDepth; depth++) {
      var bestScore = -999999;
      var alpha = -999999, beta = 999999;
      var localBest = best;
      for (i = 0; i < order.length; i++) {
        m = order[i];
        var see = this.xqSEE(m);
        this.applyXQ(m);
        var ext = 0;
        if (this.inCheckXQ(this.red)) ext = 1;
        var sc = -this.negamaxXQ(depth - 1 + ext, -beta, -alpha, true);
        if (see < 0) sc += see * 2;
        else if (see > 0) sc += idiv(see, 2);
        if (sc > 40000) sc += depth;
        this.undoXQ(m);
        if (sc > bestScore) {
          bestScore = sc;
          localBest = m;
        }
        if (sc > alpha) alpha = sc;
      }
      best = localBest;
      for (i = 0; i < order.length; i++) {
        m = order[i];
        if (m.from === best.from && m.to === best.to) {
          var t = order[0]; order[0] = order[i]; order[i] = t;
          break;
        }
      }
    }
    return best;
  };

  XiangqiGame.prototype.negamaxXQ = function (depth, alpha, beta, doQ) {
    if (depth <= 0) {
      if (doQ) return this.quiesceXQ(alpha, beta, 3);
      return this.evaluateSTMXQ();
    }
    var legal = this.legalMovesXQ();
    if (legal.length === 0) {
      if (this.inCheckXQ(this.red)) return -50000 + depth;
      return 0;
    }
    legal = this.orderMovesXQ(legal);
    var best = -999999;
    for (var i = 0; i < legal.length; i++) {
      var m = legal[i];
      if (depth >= 2 && m.capture !== EMPTY && this.xqSEE(m) < -40) {
        this.applyXQ(m);
        var checking = this.inCheckXQ(this.red);
        this.undoXQ(m);
        if (!checking) continue;
      }
      this.applyXQ(m);
      var ext = 0;
      if (depth >= 2 && this.inCheckXQ(this.red)) ext = 1;
      var sc = -this.negamaxXQ(depth - 1 + ext, -beta, -alpha, doQ);
      this.undoXQ(m);
      if (sc > best) best = sc;
      if (sc > alpha) alpha = sc;
      if (alpha >= beta) break;
    }
    if (best <= -999990) {
      return this.evaluateSTMXQ();
    }
    return best;
  };

  XiangqiGame.prototype.quiesceXQ = function (alpha, beta, qDepth) {
    var stand = this.evaluateSTMXQ();
    if (stand >= beta) return beta;
    if (stand > alpha) alpha = stand;
    if (qDepth <= 0) return stand;
    var legal = this.orderMovesXQ(this.legalMovesXQ());
    for (var i = 0; i < legal.length; i++) {
      var m = legal[i];
      if (m.capture === EMPTY) continue;
      if (this.xqSEE(m) < 0) continue;
      this.applyXQ(m);
      var sc = -this.quiesceXQ(-beta, -alpha, qDepth - 1);
      this.undoXQ(m);
      if (sc >= beta) return beta;
      if (sc > alpha) alpha = sc;
    }
    return alpha;
  };

  function materialXQ(p) {
    switch (xqToLower(p)) {
      case lp: return 100;
      case la: case le: return 200;
      case lh: return 400;
      case lc: return 450;
      case lr: return 900;
      default: return 0;
    }
  }

  XiangqiGame.prototype.legalUCIs = function () {
    if (this.botThinking || !this.red) return [];
    var ms = this.legalMovesXQ();
    var out = [];
    for (var i = 0; i < ms.length; i++) out.push(moveUCIXQ(ms[i]));
    return out;
  };

  WG.registerBoardGame({
    name: 'Xiangqi',
    exitDefault: 'xiangqi',
    summary: function () { return getXiangqi().summaryText(); },
    snapshot: function () { return getXiangqi().snapshot(); },
    reset: function () { return getXiangqi().reset(); },
    setDifficulty: function (level) { return getXiangqi().setDifficulty(level); },
    getDifficulty: function () { return getXiangqi().getDifficulty(); },
    playUCI: function (uci) { return getXiangqi().playUCI(uci); },
    legalUCIs: function () { return getXiangqi().legalUCIs(); },
    newGameSpeak: function () {
      return [WG.xiangqiNewGameSpeak(), getXiangqi().summaryText()];
    }
  });
})(window.WG);
