/* Port of mods/mg_mines.go — Minesweeper 9x9, mine count depends on difficulty.
 * UCI "a0" reveals; "fa0" or "flag:a0" toggles a flag. First click never a mine. */
(function (WG) {
    'use strict';

    var minesN = 9;

    function grid(v) {
        var g = [];
        for (var r = 0; r < minesN; r++) {
            var row = [];
            for (var c = 0; c < minesN; c++) row.push(v);
            g.push(row);
        }
        return g;
    }

    function minesCountForDifficulty(d) {
        switch (WG.normalizeGameDifficulty(d)) {
            case WG.diffEasy:
                return 10;
            case WG.diffHard:
                return 20;
            default:
                return 15;
        }
    }

    function parseMinesUCI(s) {
        s = (s == null ? '' : String(s)).trim().toLowerCase();
        var p;
        if (s.indexOf('flag:') === 0) {
            p = WG.miniParseSq(s.slice('flag:'.length));
            return { file: p.file, rank: p.rank, flag: true, ok: p.ok };
        }
        if (s.length >= 3 && s[0] === 'f') {
            p = WG.miniParseSq(s.slice(1));
            return { file: p.file, rank: p.rank, flag: true, ok: p.ok };
        }
        p = WG.miniParseSq(s);
        return { file: p.file, rank: p.rank, flag: false, ok: p.ok };
    }

    class MinesGame extends WG.MiniCommon {
        constructor(level) {
            super();
            this.board = grid(0); // -1 mine, 0-8 neighbor count
            this.revealed = grid(false);
            this.flagged = grid(false);
            this.started = false;
            this.revealedCount = 0;
            this.winner = '';
            this.lastMove = '';
            this.history = [];
            this.botThinking = false;
            this.thinkGen = 0;
            this.moves = 0;
            this.difficulty = WG.normalizeGameDifficulty(level);
            this.humanTurn = true;
            this.status = 'playing';
            this.mineCount = minesCountForDifficulty(this.difficulty);
            this.message = 'Mở một ô để bắt đầu. Gõ f rồi ô để cắm cờ.';
        }

        // placeMinesLocked places mines avoiding (er,ec) and its neighbors, then computes neighbor counts.
        placeMinesLocked(er, ec) {
            var banned = {};
            var r, c, dr, dc, nr, nc;
            for (dr = -1; dr <= 1; dr++) {
                for (dc = -1; dc <= 1; dc++) {
                    nr = er + dr; nc = ec + dc;
                    if (nr >= 0 && nr < minesN && nc >= 0 && nc < minesN) banned[nr + ',' + nc] = true;
                }
            }
            var cells = [];
            for (r = 0; r < minesN; r++) {
                for (c = 0; c < minesN; c++) {
                    if (!banned[r + ',' + c]) cells.push([r, c]);
                }
            }
            var shuffled = WG.shuffle(cells);
            if (Array.isArray(shuffled)) cells = shuffled;
            var n = this.mineCount;
            if (n > cells.length) n = cells.length;
            for (var i = 0; i < n; i++) {
                this.board[cells[i][0]][cells[i][1]] = -1;
            }
            for (r = 0; r < minesN; r++) {
                for (c = 0; c < minesN; c++) {
                    if (this.board[r][c] === -1) continue;
                    var cnt = 0;
                    for (dr = -1; dr <= 1; dr++) {
                        for (dc = -1; dc <= 1; dc++) {
                            if (dr === 0 && dc === 0) continue;
                            nr = r + dr; nc = c + dc;
                            if (nr >= 0 && nr < minesN && nc >= 0 && nc < minesN && this.board[nr][nc] === -1) cnt++;
                        }
                    }
                    this.board[r][c] = cnt;
                }
            }
            this.started = true;
        }

        // revealLocked flood-fills from (r,c).
        revealLocked(r, c) {
            if (r < 0 || r >= minesN || c < 0 || c >= minesN) return;
            if (this.revealed[r][c] || this.flagged[r][c]) return;
            this.revealed[r][c] = true;
            this.revealedCount++;
            if (this.board[r][c] === 0) {
                for (var dr = -1; dr <= 1; dr++) {
                    for (var dc = -1; dc <= 1; dc++) {
                        if (dr === 0 && dc === 0) continue;
                        this.revealLocked(r + dr, c + dc);
                    }
                }
            }
        }

        boardRows() {
            var rows = new Array(minesN);
            var lost = this.status === 'lose';
            for (var r = 0; r < minesN; r++) {
                var s = '';
                for (var c = 0; c < minesN; c++) {
                    if (this.flagged[r][c] && !this.revealed[r][c]) {
                        s += 'F';
                    } else if (!this.revealed[r][c]) {
                        s += (lost && this.board[r][c] === -1) ? '*' : '.';
                    } else if (this.board[r][c] === -1) {
                        s += '*';
                    } else if (this.board[r][c] === 0) {
                        s += ' ';
                    } else {
                        s += String(this.board[r][c]);
                    }
                }
                rows[r] = s;
            }
            return rows;
        }

        countFlags() {
            var n = 0;
            for (var r = 0; r < minesN; r++) {
                for (var c = 0; c < minesN; c++) {
                    if (this.flagged[r][c]) n++;
                }
            }
            return n;
        }

        snapshot() {
            var extra = {
                board: this.boardRows(), boardW: minesN, boardH: minesN,
                mines: this.mineCount, flagsUsed: this.countFlags(), revealed: this.revealedCount
            };
            return this.baseSnap('mines', 'mines', extra);
        }

        summaryText() {
            return 'Dò mìn ' + minesN + 'x' + minesN + ', ' + this.mineCount + ' mìn, độ khó ' +
                WG.normalizeGameDifficulty(this.difficulty) + ' trên web Vector. Trạng thái: ' + this.status + '.';
        }

        reset() {
            var prev = WG.normalizeGameDifficulty(this.difficulty);
            var ng = new MinesGame(prev);
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
            if (this.status !== 'playing') return null;
            var out = null;
            for (var r = 0; r < minesN; r++) {
                for (var c = 0; c < minesN; c++) {
                    if (!this.revealed[r][c] && !this.flagged[r][c]) (out = out || []).push(WG.miniSqName(c, r));
                }
            }
            return out;
        }

        playUCI(uci) {
            if (this.status !== 'playing') throw new Error('game over');
            var p = parseMinesUCI(uci);
            var file = p.file, rank = p.rank;
            if (!p.ok || file < 0 || file >= minesN || rank < 0 || rank >= minesN) throw new Error('bad move');
            var u = WG.miniSqName(file, rank);
            var resp;
            if (p.flag) {
                if (this.revealed[rank][file]) throw new Error('cell already revealed');
                this.flagged[rank][file] = !this.flagged[rank][file];
                this.lastMove = 'flag:' + u;
                this.history.push('flag:' + u);
                this.message = 'Đã cắm/gỡ cờ.';
                resp = this.snapshot();
                resp.youMove = 'flag:' + u;
                return resp;
            }
            if (this.flagged[rank][file]) throw new Error('cell flagged');
            if (this.revealed[rank][file]) throw new Error('already revealed');
            if (!this.started) this.placeMinesLocked(rank, file);
            if (this.board[rank][file] === -1) {
                this.revealed[rank][file] = true;
                this.status = 'lose';
                this.winner = 'bot';
                this.message = 'Bạn đã đạp trúng mìn!';
                this.lastMove = u;
                this.history.push('boom:' + u);
                for (var r = 0; r < minesN; r++) {
                    for (var c = 0; c < minesN; c++) {
                        if (this.board[r][c] === -1) this.revealed[r][c] = true;
                    }
                }
            } else {
                this.revealLocked(rank, file);
                this.lastMove = u;
                this.history.push(u);
                if (this.revealedCount === minesN * minesN - this.mineCount) {
                    this.status = 'win';
                    this.winner = 'human';
                    this.message = 'Bạn đã dò hết mìn!';
                } else {
                    this.message = 'Tiếp tục dò mìn.';
                }
            }
            this.moves++;
            resp = this.snapshot();
            resp.youMove = u;

            WG.queueGameSpeak('mines', WG.buildPlaceSpokenHumanOnly('mines', u, resp.status, resp.winner), 'Dò mìn. Ô: ' + u + '.');
            return resp;
        }
    }

    var inst = null;
    function getMinesweeper() {
        if (inst === null) inst = new MinesGame(WG.diffMedium);
        return inst;
    }

    WG.registerBoardGame({
        name: 'Minesweeper',
        exitDefault: 'mines',
        summary: function () { return getMinesweeper().summaryText(); },
        snapshot: function () { return getMinesweeper().snapshot(); },
        reset: function () { return getMinesweeper().reset(); },
        setDifficulty: function (s) { return getMinesweeper().setDifficulty(s); },
        getDifficulty: function () { return getMinesweeper().getDifficulty(); },
        playUCI: function (u) { return getMinesweeper().playUCI(u); },
        legalUCIs: function () { return getMinesweeper().legalUCIs(); },
        newGameSpeak: function () {
            return WG.speakNew(
                'New minesweeper game. Reveal a cell to start.',
                'Ván dò mìn mới. Mở một ô để bắt đầu.'
            );
        }
    });
})(window.WG);
