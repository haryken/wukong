// Port of wired/mods/chess_engine.go + chess.go (human White vs bot Black).
(function (WG) {
  'use strict';

  var EMPTY = 0;
  function c(s) { return s.charCodeAt(0); }
  var cP = c('P'), cN = c('N'), cB = c('B'), cR = c('R'), cQ = c('Q'), cK = c('K');
  var lp = c('p'), ln = c('n'), lb = c('b'), lr = c('r'), lq = c('q'), lk = c('k');

  var KNIGHT_D = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];
  var DIAG_D = [[1, 1], [1, -1], [-1, 1], [-1, -1]];
  var ORTH_D = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  var PROMO_PIECES = [lq, lr, lb, ln];

  function isWhite(p) { return p >= 65 && p <= 90; }
  function isBlack(p) { return p >= 97 && p <= 122; }
  function isEnemy(p, side) {
    if (p === EMPTY) return false;
    if (isWhite(side)) return isBlack(p);
    return isWhite(p);
  }
  function isFriend(p, side) {
    if (p === EMPTY) return false;
    if (isWhite(side)) return isWhite(p);
    return isBlack(p);
  }
  function toLower(p) {
    if (p >= 65 && p <= 90) return p + 32;
    return p;
  }
  function idiv(a, b) { return (a / b) | 0; }
  function abs(x) { return x < 0 ? -x : x; }
  function onBoard(r, f) { return r >= 0 && r < 8 && f >= 0 && f < 8; }
  function pieceStr(p) { return String.fromCharCode(p); }

  function mkMove(from, to, promo, capture, isCastle, isEnPassant) {
    return {
      from: from, to: to, promo: promo, capture: capture,
      isCastle: isCastle, isEnPassant: isEnPassant,
      prevEP: 0, prevCastle: '', prevHalfmove: 0
    };
  }
  function copyMove(m) {
    return mkMove(m.from, m.to, m.promo, m.capture, m.isCastle, m.isEnPassant);
  }

  function sqName(i) {
    return String.fromCharCode(97 + i % 8) + String.fromCharCode(49 + ((i / 8) | 0));
  }

  function parseSq(s) {
    if (s.length !== 2) return { sq: 0, ok: false };
    var f = s.charCodeAt(0) - 97;
    var r = s.charCodeAt(1) - 49;
    if (f < 0 || f > 7 || r < 0 || r > 7) return { sq: 0, ok: false };
    return { sq: r * 8 + f, ok: true };
  }

  function ChessGame() {
    this.board = new Uint8Array(64);
    this.white = true;
    this.castle = 'KQkq';
    this.ep = -1;
    this.halfmove = 0;
    this.fullmove = 1;
    this.history = [];
    this.lastUCI = '';
    this.lastSAN = '';
    this.status = 'playing';
    this.winner = '';
    this.message = 'Ván mới — bạn đi Trắng. (New game — you are White.)';
    this.difficulty = WG.diffMedium;
    this.botThinking = false;
    this.thinkGen = 0;
    this.setFENBoard('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR');
  }

  var chessInst = null;

  function getChess() {
    if (chessInst === null) chessInst = new ChessGame();
    return chessInst;
  }

  ChessGame.prototype.setFENBoard = function (fenBoard) {
    var i;
    for (i = 0; i < 64; i++) this.board[i] = EMPTY;
    var rank = 7, file = 0;
    for (i = 0; i < fenBoard.length; i++) {
      var ch = fenBoard.charAt(i);
      if (ch === '/') {
        rank--;
        file = 0;
      } else if (ch >= '1' && ch <= '8') {
        file += ch.charCodeAt(0) - 48;
      } else {
        this.board[rank * 8 + file] = ch.charCodeAt(0);
        file++;
      }
    }
  };

  ChessGame.prototype.fen = function () {
    var b = '';
    for (var rank = 7; rank >= 0; rank--) {
      var emptyN = 0;
      for (var file = 0; file < 8; file++) {
        var p = this.board[rank * 8 + file];
        if (p === EMPTY) {
          emptyN++;
          continue;
        }
        if (emptyN > 0) {
          b += String(emptyN);
          emptyN = 0;
        }
        b += pieceStr(p);
      }
      if (emptyN > 0) b += String(emptyN);
      if (rank > 0) b += '/';
    }
    var stm = this.white ? 'w' : 'b';
    var cast = this.castle === '' ? '-' : this.castle;
    var ep = this.ep >= 0 ? sqName(this.ep) : '-';
    return b + ' ' + stm + ' ' + cast + ' ' + ep + ' ' + this.halfmove + ' ' + this.fullmove;
  };

  ChessGame.prototype.boardRows = function () {
    var rows = new Array(8);
    for (var rank = 7; rank >= 0; rank--) {
      var s = '';
      for (var file = 0; file < 8; file++) {
        var p = this.board[rank * 8 + file];
        s += p === EMPTY ? '.' : pieceStr(p);
      }
      rows[7 - rank] = s;
    }
    return rows;
  };

  ChessGame.prototype.snapshot = function () {
    return {
      fen: this.fen(),
      board: this.boardRows(),
      turn: this.white ? 'white' : 'black',
      status: this.status,
      winner: this.winner,
      lastMove: this.lastUCI,
      lastSAN: this.lastSAN,
      history: this.history.slice(),
      message: this.message,
      youAre: 'white',
      botIs: 'black',
      difficulty: WG.normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking
    };
  };

  ChessGame.prototype.summaryText = function () {
    return this.summaryTextUnlocked();
  };

  ChessGame.prototype.summaryTextUnlocked = function () {
    var turn = this.white ? 'trắng (white / người chơi)' : 'đen (black / bot)';
    var b = 'Cờ vua trên web Vector. Người chơi cầm Trắng; bot cầm Đen. ';
    b += 'Lượt hiện tại: ' + turn + '. Trạng thái: ' + this.status + '. ';
    if (this.winner !== '') b += 'Người thắng: ' + this.winner + '. ';
    if (this.lastUCI !== '') {
      b += 'Nước gần nhất: ' + this.lastUCI;
      if (this.lastSAN !== '') b += ' (' + this.lastSAN + ')';
      b += '. ';
    }
    b += 'FEN: ' + this.fen() + '. ';
    b += 'Bàn (hàng 8→1, A→H): ';
    var rows = this.boardRows();
    for (var i = 0; i < rows.length; i++) b += 'r' + (8 - i) + '=' + rows[i] + ' ';
    if (this.history.length > 0) b += 'Lịch sử UCI: ' + this.history.join(' ') + '.';
    return b;
  };

  ChessGame.prototype.reset = function () {
    var prev = WG.normalizeGameDifficulty(this.difficulty);
    this.thinkGen++;
    this.botThinking = false;
    var ng = new ChessGame();
    ng.difficulty = prev;
    chessInst = ng;
    return ng.snapshot();
  };

  ChessGame.prototype.setDifficulty = function (level) {
    this.difficulty = WG.normalizeGameDifficulty(level);
    return this.difficulty;
  };

  ChessGame.prototype.getDifficulty = function () {
    return WG.normalizeGameDifficulty(this.difficulty);
  };

  ChessGame.prototype.kingSq = function (white) {
    var target = white ? cK : lk;
    for (var i = 0; i < 64; i++) {
      if (this.board[i] === target) return i;
    }
    return -1;
  };

  ChessGame.prototype.attacked = function (sq, byWhite) {
    var board = this.board;
    var tr = (sq / 8) | 0, tf = sq % 8;
    var r, f, i, d;
    if (byWhite) {
      for (i = -1; i <= 1; i += 2) {
        r = tr - 1; f = tf + i;
        if (onBoard(r, f) && board[r * 8 + f] === cP) return true;
      }
    } else {
      for (i = -1; i <= 1; i += 2) {
        r = tr + 1; f = tf + i;
        if (onBoard(r, f) && board[r * 8 + f] === lp) return true;
      }
    }
    var wantN = byWhite ? cN : ln;
    for (i = 0; i < KNIGHT_D.length; i++) {
      d = KNIGHT_D[i];
      r = tr + d[0]; f = tf + d[1];
      if (onBoard(r, f) && board[r * 8 + f] === wantN) return true;
    }
    var wantK = byWhite ? cK : lk;
    for (var dr = -1; dr <= 1; dr++) {
      for (var df = -1; df <= 1; df++) {
        if (dr === 0 && df === 0) continue;
        r = tr + dr; f = tf + df;
        if (onBoard(r, f) && board[r * 8 + f] === wantK) return true;
      }
    }
    function slide(dirs, bishops, rooks) {
      for (var j = 0; j < dirs.length; j++) {
        var dd = dirs[j];
        var rr = tr + dd[0], ff = tf + dd[1];
        while (onBoard(rr, ff)) {
          var p = board[rr * 8 + ff];
          if (p !== EMPTY) {
            var pl = toLower(p);
            var okColor = (byWhite && isWhite(p)) || (!byWhite && isBlack(p));
            if (okColor) {
              if (bishops && (pl === lb || pl === lq)) return true;
              if (rooks && (pl === lr || pl === lq)) return true;
            }
            break;
          }
          rr += dd[0];
          ff += dd[1];
        }
      }
      return false;
    }
    if (slide(DIAG_D, true, false)) return true;
    if (slide(ORTH_D, false, true)) return true;
    return false;
  };

  ChessGame.prototype.inCheck = function (white) {
    var ks = this.kingSq(white);
    if (ks < 0) return true;
    return this.attacked(ks, !white);
  };

  ChessGame.prototype.genPseudo = function (from) {
    var g = this;
    var board = this.board;
    var p = board[from];
    if (p === EMPTY) return [];
    var white = isWhite(p);
    if (white !== this.white) return [];
    var side = p;
    var moves = [];
    function add(to, promo, castle, ep) {
      if (to < 0 || to > 63) return;
      var cap = board[to];
      if (isFriend(cap, side)) return;
      moves.push(mkMove(from, to, promo, cap, castle, ep));
    }
    var r = (from / 8) | 0, f = from % 8;
    var kind = toLower(p);
    var i, j, d, nr, nf, to;

    if (kind === lp) {
      var dir = 1, startRank = 1, promoRank = 7;
      if (!white) {
        dir = -1;
        startRank = 6;
        promoRank = 0;
      }
      var one = (r + dir) * 8 + f;
      if (onBoard(r + dir, f) && board[one] === EMPTY) {
        if (r + dir === promoRank) {
          for (i = 0; i < PROMO_PIECES.length; i++) {
            add(one, white ? PROMO_PIECES[i] - 32 : PROMO_PIECES[i], false, false);
          }
        } else {
          add(one, 0, false, false);
          if (r === startRank) {
            var two = (r + 2 * dir) * 8 + f;
            if (board[two] === EMPTY) add(two, 0, false, false);
          }
        }
      }
      for (j = -1; j <= 1; j += 2) {
        nr = r + dir; nf = f + j;
        if (!onBoard(nr, nf)) continue;
        to = nr * 8 + nf;
        if (isEnemy(board[to], side)) {
          if (nr === promoRank) {
            for (i = 0; i < PROMO_PIECES.length; i++) {
              add(to, white ? PROMO_PIECES[i] - 32 : PROMO_PIECES[i], false, false);
            }
          } else {
            add(to, 0, false, false);
          }
        } else if (to === this.ep) {
          add(to, 0, false, true);
        }
      }
    } else if (kind === ln) {
      for (i = 0; i < KNIGHT_D.length; i++) {
        d = KNIGHT_D[i];
        nr = r + d[0]; nf = f + d[1];
        if (onBoard(nr, nf)) add(nr * 8 + nf, 0, false, false);
      }
    } else if (kind === lb || kind === lr || kind === lq) {
      var dirs = [];
      if (kind === lb || kind === lq) dirs = dirs.concat(DIAG_D);
      if (kind === lr || kind === lq) dirs = dirs.concat(ORTH_D);
      for (i = 0; i < dirs.length; i++) {
        d = dirs[i];
        nr = r + d[0]; nf = f + d[1];
        while (onBoard(nr, nf)) {
          to = nr * 8 + nf;
          if (isFriend(board[to], side)) break;
          add(to, 0, false, false);
          if (board[to] !== EMPTY) break;
          nr += d[0];
          nf += d[1];
        }
      }
    } else if (kind === lk) {
      for (var dr = -1; dr <= 1; dr++) {
        for (var df = -1; df <= 1; df++) {
          if (dr === 0 && df === 0) continue;
          nr = r + dr; nf = f + df;
          if (onBoard(nr, nf)) add(nr * 8 + nf, 0, false, false);
        }
      }
      var cs = this.castle;
      if (white && r === 0 && f === 4) {
        if (cs.indexOf('K') >= 0 && board[5] === EMPTY && board[6] === EMPTY &&
          board[7] === cR && !g.inCheck(true) && !g.attacked(5, false) && !g.attacked(6, false)) {
          add(6, 0, true, false);
        }
        if (cs.indexOf('Q') >= 0 && board[3] === EMPTY && board[2] === EMPTY && board[1] === EMPTY &&
          board[0] === cR && !g.inCheck(true) && !g.attacked(3, false) && !g.attacked(2, false)) {
          add(2, 0, true, false);
        }
      }
      if (!white && r === 7 && f === 4) {
        if (cs.indexOf('k') >= 0 && board[61] === EMPTY && board[62] === EMPTY &&
          board[63] === lr && !g.inCheck(false) && !g.attacked(61, true) && !g.attacked(62, true)) {
          add(62, 0, true, false);
        }
        if (cs.indexOf('q') >= 0 && board[59] === EMPTY && board[58] === EMPTY && board[57] === EMPTY &&
          board[56] === lr && !g.inCheck(false) && !g.attacked(59, true) && !g.attacked(58, true)) {
          add(58, 0, true, false);
        }
      }
    }
    return moves;
  };

  ChessGame.prototype.apply = function (m) {
    m.prevEP = this.ep;
    m.prevCastle = this.castle;
    m.prevHalfmove = this.halfmove;
    var board = this.board;
    var p = board[m.from];
    board[m.from] = EMPTY;
    if (m.isEnPassant) {
      if (this.white) board[m.to - 8] = EMPTY;
      else board[m.to + 8] = EMPTY;
    }
    if (m.isCastle) {
      switch (m.to) {
        case 6: board[7] = EMPTY; board[5] = cR; break;
        case 2: board[0] = EMPTY; board[3] = cR; break;
        case 62: board[63] = EMPTY; board[61] = lr; break;
        case 58: board[56] = EMPTY; board[59] = lr; break;
      }
    }
    board[m.to] = m.promo !== 0 ? m.promo : p;
    this.ep = -1;
    if (toLower(p) === lp && abs(((m.to / 8) | 0) - ((m.from / 8) | 0)) === 2) {
      this.ep = idiv(m.from + m.to, 2);
    }
    var g = this;
    function strip(ch) { g.castle = g.castle.split(ch).join(''); }
    if (p === cK) { strip('K'); strip('Q'); }
    if (p === lk) { strip('k'); strip('q'); }
    if (m.from === 0 || m.to === 0) strip('Q');
    if (m.from === 7 || m.to === 7) strip('K');
    if (m.from === 56 || m.to === 56) strip('q');
    if (m.from === 63 || m.to === 63) strip('k');
    if (toLower(p) === lp || m.capture !== EMPTY || m.isEnPassant) this.halfmove = 0;
    else this.halfmove++;
    if (!this.white) this.fullmove++;
    this.white = !this.white;
  };

  ChessGame.prototype.undo = function (m) {
    var board = this.board;
    this.white = !this.white;
    if (!this.white) this.fullmove--;
    this.ep = m.prevEP;
    this.castle = m.prevCastle;
    this.halfmove = m.prevHalfmove;
    var p = board[m.to];
    if (m.promo !== 0) p = this.white ? cP : lp;
    board[m.to] = EMPTY;
    board[m.from] = p;
    if (m.isEnPassant) {
      if (this.white) board[m.to - 8] = lp;
      else board[m.to + 8] = cP;
    } else if (m.capture !== EMPTY) {
      board[m.to] = m.capture;
    }
    if (m.isCastle) {
      switch (m.to) {
        case 6: board[5] = EMPTY; board[7] = cR; break;
        case 2: board[3] = EMPTY; board[0] = cR; break;
        case 62: board[61] = EMPTY; board[63] = lr; break;
        case 58: board[59] = EMPTY; board[56] = lr; break;
      }
    }
  };

  ChessGame.prototype.legalMoves = function () {
    var out = [];
    var white = this.white;
    for (var from = 0; from < 64; from++) {
      var ms = this.genPseudo(from);
      for (var i = 0; i < ms.length; i++) {
        var m = ms[i];
        this.apply(m);
        var ok = !this.inCheck(white);
        this.undo(m);
        if (ok) out.push(m);
      }
    }
    return out;
  };

  ChessGame.prototype.refreshStatus = function () {
    var legal = this.legalMoves();
    var check = this.inCheck(this.white);
    if (legal.length === 0) {
      if (check) {
        this.status = 'checkmate';
        if (this.white) {
          this.winner = 'black';
          this.message = 'Chiếu hết — Đen (bot) thắng. (Checkmate — Black wins.)';
        } else {
          this.winner = 'white';
          this.message = 'Chiếu hết — Trắng thắng. (Checkmate — White wins.)';
        }
      } else {
        this.status = 'stalemate';
        this.winner = '';
        this.message = 'Hết nước — hòa. (Stalemate — draw.)';
      }
      return;
    }
    if (check) {
      this.status = 'check';
      this.message = 'Đang chiếu! (Check!)';
    } else {
      this.status = 'playing';
      if (this.white) this.message = 'Lượt Trắng (bạn). (White to move.)';
      else this.message = 'Lượt Đen (bot). (Black to move.)';
    }
  };

  function moveUCI(m) {
    var u = sqName(m.from) + sqName(m.to);
    if (m.promo !== 0) u += pieceStr(toLower(m.promo));
    return u;
  }

  ChessGame.prototype.playUCI = function (uci) {
    if (this.botThinking) throw new Error('bot thinking');
    if (this.status === 'checkmate' || this.status === 'stalemate') throw new Error('game over');
    if (!this.white) throw new Error('not your turn');
    uci = String(uci == null ? '' : uci).toLowerCase().trim();
    if (uci.length < 4) throw new Error('bad move');
    var a = parseSq(uci.substring(0, 2));
    var b = parseSq(uci.substring(2, 4));
    if (!a.ok || !b.ok) throw new Error('bad squares');
    var from = a.sq, to = b.sq;
    var promo = 0;
    if (uci.length >= 5) {
      promo = uci.charCodeAt(4) & 0xff;
      if (this.white) promo = (promo - 32) & 0xff;
    }
    var chosen = null;
    var legal = this.legalMoves();
    for (var i = 0; i < legal.length; i++) {
      var m = legal[i];
      if (m.from === from && m.to === to) {
        if (promo === 0 || m.promo === promo || (promo !== 0 && toLower(m.promo) === toLower(promo))) {
          var mm = copyMove(m);
          if (promo !== 0) {
            mm.promo = promo;
          } else if (m.promo !== 0) {
            mm.promo = this.white ? cQ : lq;
          }
          chosen = mm;
          break;
        }
      }
    }
    if (chosen === null) throw new Error('illegal move');
    var youPiece = this.board[chosen.from];
    this.apply(chosen);
    var u = moveUCI(chosen);
    this.lastUCI = u;
    this.lastSAN = u;
    this.history.push(u);
    var youMove = u;
    this.refreshStatus();
    var needBot = this.status !== 'checkmate' && this.status !== 'stalemate' && !this.white;
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
      fen: this.fen(), board: this.boardRows(), turn: this.white ? 'white' : 'black',
      status: this.status, winner: this.winner, lastMove: this.lastUCI,
      lastSAN: this.lastSAN, history: this.history.slice(), message: this.message,
      youAre: 'white', botIs: 'black',
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
      var chessHint = 'Cờ vua. Nước người chơi: ' + youMove + '.';
      WG.queueGameSpeak('chess', WG.buildChessSpokenComment(youMove, '', pieceStr(youPiece), '', stSpeak, winSpeak), chessHint);
    }

    if (needBot) {
      this.runBotThink(thinkGen).catch(function (e) {
        if (window.console) console.error('[Chess] bot think:', e);
      });
    }
    return resp;
  };

  ChessGame.prototype.runBotThink = async function (gen) {
    await WG.sleep(2000);
    if (gen !== this.thinkGen || !this.botThinking) return;
    var botMove = '';
    var botPiece = 0;
    if (this.status !== 'checkmate' && this.status !== 'stalemate' && !this.white) {
      botPiece = this.botMoveLocked();
      botMove = this.lastUCI;
    }
    this.botThinking = false;
    if (this.white && (this.status === 'playing' || this.status === 'check')) {
      if (this.status === 'check') this.message = 'Đến lượt bạn — đang chiếu!';
      else this.message = 'Đến lượt bạn.';
    }
    var status = this.status;
    var winner = this.winner;
    var summary = this.summaryTextUnlocked();

    if (botMove !== '') {
      WG.queueGameSpeak('chess', WG.buildChessSpokenComment('', botMove, '', pieceStr(botPiece), status, winner), summary);
    }
  };

  ChessGame.prototype.botMoveLocked = function () {
    var legal = this.legalMoves();
    if (legal.length === 0) {
      this.refreshStatus();
      return 0;
    }
    var best;
    switch (WG.normalizeGameDifficulty(this.difficulty)) {
      case WG.diffEasy:
        best = this.pickChessSearch(legal, 1, 28);
        break;
      case WG.diffHard:
        best = this.pickChessHard(legal);
        break;
      default:
        best = this.pickChessSearch(legal, 3, 6);
    }
    var piece = this.board[best.from];
    this.apply(best);
    var u = moveUCI(best);
    this.lastUCI = u;
    this.lastSAN = u;
    this.history.push(u);
    this.refreshStatus();
    return piece;
  };

  ChessGame.prototype.staticEvalWhitePOV = function () {
    var score = 0;
    for (var i = 0; i < 64; i++) {
      var p = this.board[i];
      if (p === EMPTY) continue;
      var v = materialCP(p) + chessPST(p, i);
      if (isWhite(p)) score += v;
      else score -= v;
    }
    return score;
  };

  function chessPST(p, sq) {
    var r = (sq / 8) | 0, f = sq % 8;
    var center = 0;
    if (f >= 2 && f <= 5 && r >= 2 && r <= 5) center = 6;
    switch (toLower(p)) {
      case lp:
        if (isWhite(p)) return r * 4 + idiv(center, 2);
        return (7 - r) * 4 + idiv(center, 2);
      case ln:
      case lb:
        return center + 4;
      case lq:
        if (isWhite(p)) {
          if (r <= 1) return 2;
          if (r >= 5) return -8;
          return idiv(center, 2);
        }
        if (r >= 6) return 2;
        if (r <= 2) return -8;
        return idiv(center, 2);
      case lr:
        return idiv(center, 2);
      case lk:
        if (r === 0 || r === 7) {
          if (f === 1 || f === 2 || f === 6) return 10;
          return 6;
        }
        return -12;
      default:
        return idiv(center, 2);
    }
  }

  ChessGame.prototype.evaluateSTM = function () {
    var s = this.staticEvalWhitePOV();
    var sHang = this.hangSoftSTM();
    if (this.white) return s + sHang;
    return -s + sHang;
  };

  ChessGame.prototype.hangSoftSTM = function () {
    var pen = 0;
    for (var i = 0; i < 64; i++) {
      var p = this.board[i];
      if (p === EMPTY) continue;
      var ours = (this.white && isWhite(p)) || (!this.white && isBlack(p));
      if (!ours) continue;
      if (!this.attacked(i, !this.white)) continue;
      var defended = this.attacked(i, this.white);
      var v = materialCP(p);
      if (v <= 0) continue;
      if (!defended) pen -= idiv(v, 2);
      else if (v >= 900) pen -= 30;
    }
    return pen;
  };

  ChessGame.prototype.chessSEE = function (m) {
    var mover = this.board[m.from];
    if (mover === EMPTY) return 0;
    var gain = materialCP(m.capture);
    if (m.isEnPassant) gain = materialCP(lp);
    if (m.promo !== 0) gain += materialCP(m.promo) - materialCP(lp);
    this.apply(m);
    var toPiece = this.board[m.to];
    if (toPiece !== EMPTY && this.attacked(m.to, this.white)) gain -= materialCP(toPiece);
    var checking = this.inCheck(this.white);
    this.undo(m);
    if (checking && gain >= idiv(-materialCP(mover), 2)) {
      if (gain < 0) gain += 40;
    }
    if (gain === 0 && materialCP(m.capture) >= 900) gain = -25;
    return gain;
  };

  function isCap(m) { return m.capture !== EMPTY || m.isEnPassant; }

  function chessMoveOrder(m) {
    var s = 0;
    if (isCap(m)) s += 100 + material(m.capture) * 10;
    if (m.promo !== 0) s += 80;
    return s;
  }

  ChessGame.prototype.orderMovesChess = function (legal) {
    var ss = new Array(legal.length);
    var i, j, t;
    for (i = 0; i < legal.length; i++) {
      var m = legal[i];
      var s = chessMoveOrder(m);
      if (isCap(m)) s += idiv(this.chessSEE(m), 10);
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

  ChessGame.prototype.pickChessSearch = function (legal, depth, noisePct) {
    if (depth < 1) depth = 1;
    var order = this.orderMovesChess(legal);
    var i, m, sc;
    if (noisePct < 20) {
      var filt = [];
      for (i = 0; i < order.length; i++) {
        m = order[i];
        if (isCap(m) && this.chessSEE(m) < -50) continue;
        filt.push(m);
      }
      if (filt.length > 0) order = filt;
    }
    var best = order[0];
    var bestScore = -999999;
    var bestSEE = -999999;
    var alpha = -999999, beta = 999999;
    for (i = 0; i < order.length; i++) {
      m = order[i];
      this.apply(m);
      sc = -this.negamaxChess(depth - 1, -beta, -alpha, true);
      this.undo(m);
      var see = 0;
      if (isCap(m)) see = this.chessSEE(m);
      var scAdj = sc + idiv(see, 4);
      if (scAdj > bestScore || (scAdj === bestScore && see > bestSEE)) {
        bestScore = scAdj;
        bestSEE = see;
        best = m;
      }
      if (sc > alpha) alpha = sc;
    }
    if (noisePct > 0 && WG.randInt(100) < noisePct && order.length > 1) {
      var pool = [];
      for (i = 0; i < order.length; i++) {
        m = order[i];
        if (isCap(m) && this.chessSEE(m) < -100) continue;
        this.apply(m);
        sc = -this.negamaxChess(depth - 1, -999999, 999999, true);
        this.undo(m);
        if (sc >= bestScore - 80) pool.push(m);
      }
      if (pool.length > 0) return pool[WG.randInt(pool.length)];
    }
    return best;
  };

  ChessGame.prototype.pickChessHard = function (legal) {
    var order = this.orderMovesChess(legal);
    var filtered = [];
    var i, m;
    for (i = 0; i < order.length; i++) {
      m = order[i];
      if (isCap(m) && this.chessSEE(m) < 0) continue;
      filtered.push(m);
    }
    if (filtered.length === 0) filtered = order;
    else order = filtered;
    var best = order[0];
    var maxDepth = 4;
    for (var depth = 1; depth <= maxDepth; depth++) {
      var bestScore = -999999;
      var bestSEE = -999999;
      var alpha = -999999, beta = 999999;
      var localBest = best;
      for (i = 0; i < order.length; i++) {
        m = order[i];
        var see = 0;
        if (isCap(m)) {
          see = this.chessSEE(m);
          if (see < -30) continue;
        }
        this.apply(m);
        var ext = 0;
        if (this.inCheck(this.white) && see >= 0) ext = 1;
        var sc = -this.negamaxChess(depth - 1 + ext, -beta, -alpha, true);
        if (sc > 40000) sc += depth;
        sc += idiv(see, 5);
        this.undo(m);
        if (sc > bestScore || (sc === bestScore && see > bestSEE)) {
          bestScore = sc;
          bestSEE = see;
          localBest = m;
        }
        if (sc > alpha) alpha = sc;
      }
      best = localBest;
      for (i = 0; i < order.length; i++) {
        m = order[i];
        if (m.from === best.from && m.to === best.to && m.promo === best.promo) {
          var t = order[0]; order[0] = order[i]; order[i] = t;
          break;
        }
      }
    }
    return best;
  };

  ChessGame.prototype.negamaxChess = function (depth, alpha, beta, doQ) {
    if (depth <= 0) {
      if (doQ) return this.quiesceChess(alpha, beta, 4);
      return this.evaluateSTM();
    }
    var legal = this.legalMoves();
    if (legal.length === 0) {
      if (this.inCheck(this.white)) return -50000 + depth;
      return 0;
    }
    legal = this.orderMovesChess(legal);
    var best = -999999;
    var i, m, sc;
    for (i = 0; i < legal.length; i++) {
      m = legal[i];
      if (depth >= 2 && isCap(m) && this.chessSEE(m) < -200) continue;
      this.apply(m);
      var ext = 0;
      if (depth >= 2 && this.inCheck(this.white)) {
        var hungMajor = false;
        var p = this.board[m.to];
        if (p !== EMPTY && materialCP(p) >= 500 && this.attacked(m.to, this.white)) hungMajor = true;
        if (!hungMajor) ext = 1;
      }
      sc = -this.negamaxChess(depth - 1 + ext, -beta, -alpha, doQ);
      this.undo(m);
      if (sc > best) best = sc;
      if (sc > alpha) alpha = sc;
      if (alpha >= beta) break;
    }
    if (best <= -999990) {
      for (i = 0; i < legal.length; i++) {
        m = legal[i];
        this.apply(m);
        sc = -this.negamaxChess(depth - 1, -beta, -alpha, doQ);
        this.undo(m);
        if (sc > best) best = sc;
        if (sc > alpha) alpha = sc;
        if (alpha >= beta) break;
      }
    }
    return best;
  };

  ChessGame.prototype.quiesceChess = function (alpha, beta, qDepth) {
    var stand = this.evaluateSTM();
    if (stand >= beta) return beta;
    if (stand > alpha) alpha = stand;
    if (qDepth <= 0) return stand;
    var legal = this.legalMoves();
    var i, j, t;
    for (i = 0; i < legal.length; i++) {
      for (j = i + 1; j < legal.length; j++) {
        if (chessMoveOrder(legal[j]) > chessMoveOrder(legal[i])) {
          t = legal[i]; legal[i] = legal[j]; legal[j] = t;
        }
      }
    }
    for (i = 0; i < legal.length; i++) {
      var m = legal[i];
      if (m.capture === EMPTY && !m.isEnPassant) continue;
      var see = this.chessSEE(m);
      if (see < 0) continue;
      if (stand + see + 50 < alpha) continue;
      this.apply(m);
      var sc = -this.quiesceChess(-beta, -alpha, qDepth - 1);
      this.undo(m);
      if (sc >= beta) return beta;
      if (sc > alpha) alpha = sc;
    }
    return alpha;
  };

  function material(p) {
    switch (toLower(p)) {
      case lp: return 1;
      case ln: case lb: return 3;
      case lr: return 5;
      case lq: return 9;
      default: return 0;
    }
  }

  function materialCP(p) {
    switch (toLower(p)) {
      case lp: return 100;
      case ln: case lb: return 320;
      case lr: return 500;
      case lq: return 900;
      default: return 0;
    }
  }

  ChessGame.prototype.legalUCIs = function () {
    if (this.botThinking || !this.white) return [];
    var ms = this.legalMoves();
    var out = [];
    for (var i = 0; i < ms.length; i++) out.push(moveUCI(ms[i]));
    return out;
  };

  WG.registerBoardGame({
    name: 'Chess',
    exitDefault: 'chess',
    summary: function () { return getChess().summaryText(); },
    snapshot: function () { return getChess().snapshot(); },
    reset: function () { return getChess().reset(); },
    setDifficulty: function (level) { return getChess().setDifficulty(level); },
    getDifficulty: function () { return getChess().getDifficulty(); },
    playUCI: function (uci) { return getChess().playUCI(uci); },
    legalUCIs: function () { return getChess().legalUCIs(); },
    newGameSpeak: function () {
      var say = WG.speakNew('New game. You are white. Your move.', 'Ván mới. Bạn cầm trắng. Đến lượt bạn.')[0];
      return [say, getChess().summaryText()];
    }
  });
})(window.WG);
