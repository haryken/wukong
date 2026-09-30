/* Port of mods/mg_tictactoe.go — Tic-tac-toe 3x3, human X first, bot O. UCI squares a0..c2. */
(function (WG) {
    'use strict';

    var tttN = 3;

    // tttLines enumerates the 8 winning lines as [rank][file] pairs.
    var tttLines = [
        [[0, 0], [0, 1], [0, 2]],
        [[1, 0], [1, 1], [1, 2]],
        [[2, 0], [2, 1], [2, 2]],
        [[0, 0], [1, 0], [2, 0]],
        [[0, 1], [1, 1], [2, 1]],
        [[0, 2], [1, 2], [2, 2]],
        [[0, 0], [1, 1], [2, 2]],
        [[0, 2], [1, 1], [2, 0]]
    ];

    function newBoard() {
        var b = [];
        for (var r = 0; r < tttN; r++) {
            var row = [];
            for (var f = 0; f < tttN; f++) row.push(WG.caroEmpty);
            b.push(row);
        }
        return b;
    }

    function tttWinner(b) {
        for (var i = 0; i < tttLines.length; i++) {
            var ln = tttLines[i];
            var a = b[ln[0][0]][ln[0][1]];
            if (a !== WG.caroEmpty && a === b[ln[1][0]][ln[1][1]] && a === b[ln[2][0]][ln[2][1]]) {
                return a;
            }
        }
        return WG.caroEmpty;
    }

    function tttFull(b) {
        for (var r = 0; r < tttN; r++) {
            for (var f = 0; f < tttN; f++) {
                if (b[r][f] === WG.caroEmpty) return false;
            }
        }
        return true;
    }

    // tttMinimaxScore is a perfect-play evaluation, positive favors bot (O).
    function tttMinimaxScore(b, depth, botToMove) {
        var w = tttWinner(b);
        if (w === WG.caroO) {
            return 10 - depth;
        } else if (w === WG.caroX) {
            return depth - 10;
        }
        if (tttFull(b)) return 0;
        var r, f, sc, best;
        if (botToMove) {
            best = -1000;
            for (r = 0; r < tttN; r++) {
                for (f = 0; f < tttN; f++) {
                    if (b[r][f] !== WG.caroEmpty) continue;
                    b[r][f] = WG.caroO;
                    sc = tttMinimaxScore(b, depth + 1, false);
                    b[r][f] = WG.caroEmpty;
                    if (sc > best) best = sc;
                }
            }
            return best;
        }
        best = 1000;
        for (r = 0; r < tttN; r++) {
            for (f = 0; f < tttN; f++) {
                if (b[r][f] !== WG.caroEmpty) continue;
                b[r][f] = WG.caroX;
                sc = tttMinimaxScore(b, depth + 1, true);
                b[r][f] = WG.caroEmpty;
                if (sc < best) best = sc;
            }
        }
        return best;
    }

    class TTTGame extends WG.MiniCommon {
        constructor() {
            super();
            this.status = 'playing';
            this.winner = '';
            this.lastMove = '';
            this.history = [];
            this.botThinking = false;
            this.thinkGen = 0;
            this.moves = 0;
            this.humanTurn = true;
            this.difficulty = WG.diffMedium;
            this.message = 'Đến lượt bạn (X).';
            this.board = newBoard();
        }

        // boardRows renders top rank2 → rank0, matching the caro/connect4 convention.
        boardRows() {
            var rows = new Array(tttN);
            for (var r = tttN - 1; r >= 0; r--) {
                var s = '';
                for (var f = 0; f < tttN; f++) {
                    var v = this.board[r][f];
                    if (v === WG.caroX) s += 'X';
                    else if (v === WG.caroO) s += 'O';
                    else s += '.';
                }
                rows[tttN - 1 - r] = s;
            }
            return rows;
        }

        snapshot() {
            var extra = {
                board: this.boardRows(), boardW: tttN, boardH: tttN,
                youAre: 'X', botIs: 'O'
            };
            return this.baseSnap('tictactoe', 'tictactoe', extra);
        }

        summaryText() {
            var turn = 'bot (O)';
            if (this.humanTurn) turn = 'người chơi (X)';
            var b = 'Tic-tac-toe 3x3 trên web Vector. Bạn X đi trước, bot O. ';
            b += 'Lượt: ' + turn + '. Trạng thái: ' + this.status + '. ';
            if (this.winner !== '') b += 'Thắng: ' + this.winner + '. ';
            if (this.lastMove !== '') b += 'Nước gần nhất: ' + this.lastMove + '. ';
            b += 'Bàn (hàng ' + (tttN - 1) + '→0): ';
            var rows = this.boardRows();
            for (var i = 0; i < rows.length; i++) {
                b += 'r' + (tttN - 1 - i) + '=' + rows[i] + ' ';
            }
            if (this.history.length > 0) b += 'Lịch sử: ' + this.history.join(' ') + '.';
            return b;
        }

        reset() {
            var prev = WG.normalizeGameDifficulty(this.difficulty);
            this.thinkGen++;
            this.botThinking = false;
            var ng = new TTTGame();
            ng.difficulty = prev;
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
            if (this.status !== 'playing' || this.botThinking || !this.humanTurn) return null;
            var out = null;
            for (var r = 0; r < tttN; r++) {
                for (var f = 0; f < tttN; f++) {
                    if (this.board[r][f] === WG.caroEmpty) {
                        (out = out || []).push(WG.miniSqName(f, r));
                    }
                }
            }
            return out;
        }

        playUCI(uci) {
            if (this.botThinking) throw new Error('bot thinking');
            if (this.status !== 'playing') throw new Error('game over');
            if (!this.humanTurn) throw new Error('not your turn');
            var p = WG.miniParseSq(uci);
            var f = p.file, r = p.rank;
            if (!p.ok || f < 0 || f >= tttN || r < 0 || r >= tttN) throw new Error('bad move');
            if (this.board[r][f] !== WG.caroEmpty) throw new Error('occupied');
            this.board[r][f] = WG.caroX;
            var u = WG.miniSqName(f, r);
            this.lastMove = u;
            this.history.push('X:' + u);
            this.moves++;
            if (tttWinner(this.board) === WG.caroX) {
                this.status = 'win';
                this.winner = 'human';
                this.message = 'Bạn thắng!';
                this.humanTurn = false;
            } else if (tttFull(this.board)) {
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
            var resp = this.snapshot();
            resp.youMove = u;
            resp.botMove = '';

            WG.queueGameSpeak('tictactoe', WG.buildPlaceSpokenHumanOnly('tictactoe', u, resp.status, resp.winner), 'Tic tac toe. Nước người chơi: ' + u + '.');
            if (needBot) {
                this.runBotThink(thinkGen);
            }
            return resp;
        }

        async runBotThink(gen) {
            var diff = this.getDifficulty();
            switch (diff) {
                case WG.diffEasy:
                    await WG.sleep(500);
                    break;
                case WG.diffHard:
                    await WG.sleep(200);
                    break;
                default:
                    await WG.sleep(350);
            }
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
                WG.queueGameSpeak('tictactoe', WG.buildPlaceSpokenBotOnly('tictactoe', botMove, status, winner), 'Tic tac toe. Bot: ' + botMove + '.');
            }
        }

        // botMoveLocked picks bot's (O) move given current difficulty.
        botMoveLocked() {
            var cands = [];
            var r, f, i, c;
            for (r = 0; r < tttN; r++) {
                for (f = 0; f < tttN; f++) {
                    if (this.board[r][f] !== WG.caroEmpty) continue;
                    this.board[r][f] = WG.caroO;
                    var sc = tttMinimaxScore(this.board, 1, false);
                    this.board[r][f] = WG.caroEmpty;
                    cands.push({ f: f, r: r, sc: sc });
                }
            }
            if (cands.length === 0) {
                this.status = 'draw';
                this.message = 'Hoà.';
                return '';
            }
            var diff = WG.normalizeGameDifficulty(this.difficulty);
            var best = cands[0];
            for (i = 0; i < cands.length; i++) {
                if (cands[i].sc > best.sc) best = cands[i];
            }
            var pick = { f: 0, r: 0, sc: 0 };
            switch (diff) {
                case WG.diffHard:
                    pick = best;
                    break;
                case WG.diffEasy: {
                    // Prefer an immediate win or block if trivially available, else mostly random.
                    var win = false;
                    for (i = 0; i < cands.length; i++) {
                        if (cands[i].sc >= 9) {
                            pick = cands[i];
                            win = true;
                            break;
                        }
                    }
                    if (!win && WG.randInt(100) < 70) {
                        pick = cands[WG.randInt(cands.length)];
                    } else if (!win) {
                        pick = best;
                    }
                    break;
                }
                default: { // medium: perfect play with occasional noise among near-best
                    var top = [];
                    for (i = 0; i < cands.length; i++) {
                        c = cands[i];
                        if (c.sc >= best.sc - 2) top.push(c);
                    }
                    if (WG.randInt(100) < 20 && cands.length > 1) {
                        pick = cands[WG.randInt(cands.length)];
                    } else {
                        pick = top[WG.randInt(top.length)];
                    }
                }
            }
            this.board[pick.r][pick.f] = WG.caroO;
            var u = WG.miniSqName(pick.f, pick.r);
            this.lastMove = u;
            this.history.push('O:' + u);
            this.moves++;
            if (tttWinner(this.board) === WG.caroO) {
                this.status = 'win';
                this.winner = 'bot';
                this.message = 'Bot thắng.';
                this.humanTurn = false;
            } else if (tttFull(this.board)) {
                this.status = 'draw';
                this.message = 'Hoà.';
                this.humanTurn = false;
            } else {
                this.humanTurn = true;
            }
            return u;
        }
    }

    var inst = null;
    function getTTT() {
        if (inst === null) inst = new TTTGame();
        return inst;
    }

    WG.registerBoardGame({
        name: 'TicTacToe',
        exitDefault: 'tictactoe',
        summary: function () { return getTTT().summaryText(); },
        snapshot: function () { return getTTT().snapshot(); },
        reset: function () { return getTTT().reset(); },
        setDifficulty: function (s) { return getTTT().setDifficulty(s); },
        getDifficulty: function () { return getTTT().getDifficulty(); },
        playUCI: function (u) { return getTTT().playUCI(u); },
        legalUCIs: function () { return getTTT().legalUCIs(); },
        newGameSpeak: function () {
            return WG.speakNew(
                'New tic-tac-toe game. You are X and go first. Your move.',
                'Ván tic tac toe mới. Bạn cầm X, đi trước. Đến lượt bạn.'
            );
        }
    });
})(window.WG);
