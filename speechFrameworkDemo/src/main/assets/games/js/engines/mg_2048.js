/* Port of mods/mg_2048.go — classic 2048, 4x4, solo. UCI u/d/l/r (or up/down/left/right). */
(function (WG) {
    'use strict';

    var g2048N = 4;

    function emptyBoard() {
        var b = [];
        for (var r = 0; r < g2048N; r++) {
            var row = [];
            for (var c = 0; c < g2048N; c++) row.push(0);
            b.push(row);
        }
        return b;
    }

    function copyBoard(board) {
        return board.map(function (row) { return row.slice(); });
    }

    function reverseRow(row) {
        var out = new Array(g2048N);
        for (var i = 0; i < g2048N; i++) out[i] = row[g2048N - 1 - i];
        return out;
    }

    function getCol(board, c) {
        var out = new Array(g2048N);
        for (var r = 0; r < g2048N; r++) out[r] = board[r][c];
        return out;
    }

    function setCol(board, c, col) {
        for (var r = 0; r < g2048N; r++) board[r][c] = col[r];
    }

    // g2048SlideRowLeft compacts+merges a single row to the left.
    function g2048SlideRowLeft(row) {
        var vals = [];
        var i;
        for (i = 0; i < row.length; i++) {
            if (row[i] !== 0) vals.push(row[i]);
        }
        var merged = [];
        var gained = 0;
        var skip = false;
        for (i = 0; i < vals.length; i++) {
            if (skip) {
                skip = false;
                continue;
            }
            if (i + 1 < vals.length && vals[i] === vals[i + 1]) {
                var m = vals[i] * 2;
                merged.push(m);
                gained += m;
                skip = true;
            } else {
                merged.push(vals[i]);
            }
        }
        while (merged.length < g2048N) merged.push(0);
        var newRow = merged.slice(0, g2048N);
        var moved = false;
        for (i = 0; i < g2048N; i++) {
            if (newRow[i] !== row[i]) moved = true;
        }
        return { newRow: newRow, gained: gained, moved: moved };
    }

    // slideBoard applies one slide direction to a board copy, without mutating the caller's board.
    function slideBoard(src, dir) {
        var board = copyBoard(src);
        var moved = false;
        var gained = 0;
        var r, c, res;
        switch (dir) {
            case 'l':
                for (r = 0; r < g2048N; r++) {
                    res = g2048SlideRowLeft(board[r]);
                    if (res.moved) moved = true;
                    gained += res.gained;
                    board[r] = res.newRow;
                }
                break;
            case 'r':
                for (r = 0; r < g2048N; r++) {
                    res = g2048SlideRowLeft(reverseRow(board[r]));
                    if (res.moved) moved = true;
                    gained += res.gained;
                    board[r] = reverseRow(res.newRow);
                }
                break;
            case 'u':
                for (c = 0; c < g2048N; c++) {
                    res = g2048SlideRowLeft(getCol(board, c));
                    if (res.moved) moved = true;
                    gained += res.gained;
                    setCol(board, c, res.newRow);
                }
                break;
            case 'd':
                for (c = 0; c < g2048N; c++) {
                    res = g2048SlideRowLeft(reverseRow(getCol(board, c)));
                    if (res.moved) moved = true;
                    gained += res.gained;
                    setCol(board, c, reverseRow(res.newRow));
                }
                break;
        }
        return { board: board, gained: gained, moved: moved };
    }

    function parse2048Dir(s) {
        switch ((s == null ? '' : String(s)).trim().toLowerCase()) {
            case 'u': case 'up':
                return 'u';
            case 'd': case 'down':
                return 'd';
            case 'l': case 'left':
                return 'l';
            case 'r': case 'right':
                return 'r';
            default:
                return '';
        }
    }

    class G2048Game extends WG.MiniCommon {
        constructor() {
            super();
            this.board = emptyBoard();
            this.score = 0;
            this.won = false;
            this.winner = '';
            this.lastMove = '';
            this.history = [];
            this.botThinking = false;
            this.thinkGen = 0;
            this.moves = 0;
            this.humanTurn = true;
            this.status = 'playing';
            this.difficulty = WG.diffMedium;
            this.message = 'Gõ u/d/l/r để trượt các ô.';
            this.spawnTile();
            this.spawnTile();
        }

        spawnTile() {
            var empties = [];
            for (var r = 0; r < g2048N; r++) {
                for (var c = 0; c < g2048N; c++) {
                    if (this.board[r][c] === 0) empties.push([r, c]);
                }
            }
            if (empties.length === 0) return false;
            var p = empties[WG.randInt(empties.length)];
            var v = 2;
            if (WG.randInt(10) === 0) v = 4;
            this.board[p[0]][p[1]] = v;
            return true;
        }

        hasTile(v) {
            for (var r = 0; r < g2048N; r++) {
                for (var c = 0; c < g2048N; c++) {
                    if (this.board[r][c] === v) return true;
                }
            }
            return false;
        }

        canMove() {
            for (var r = 0; r < g2048N; r++) {
                for (var c = 0; c < g2048N; c++) {
                    if (this.board[r][c] === 0) return true;
                    if (c + 1 < g2048N && this.board[r][c] === this.board[r][c + 1]) return true;
                    if (r + 1 < g2048N && this.board[r][c] === this.board[r + 1][c]) return true;
                }
            }
            return false;
        }

        boardRows() {
            var rows = new Array(g2048N);
            for (var r = 0; r < g2048N; r++) {
                var cells = new Array(g2048N);
                for (var c = 0; c < g2048N; c++) {
                    cells[c] = this.board[r][c] === 0 ? '.' : String(this.board[r][c]);
                }
                rows[r] = cells.join(' ');
            }
            return rows;
        }

        tiles() {
            return copyBoard(this.board);
        }

        snapshot() {
            var extra = {
                board: this.boardRows(), tiles: this.tiles(),
                boardW: g2048N, boardH: g2048N, score: this.score
            };
            return this.baseSnap('g2048', 'tiles2048', extra);
        }

        summaryText() {
            return '2048 (4x4) trên web Vector. Điểm: ' + this.score + '. Trạng thái: ' + this.status + '.';
        }

        reset() {
            var ng = new G2048Game();
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
            if (this.status === 'lose') return null;
            var out = null;
            var dirs = ['u', 'd', 'l', 'r'];
            for (var i = 0; i < dirs.length; i++) {
                if (slideBoard(this.board, dirs[i]).moved) (out = out || []).push(dirs[i]);
            }
            return out;
        }

        playUCI(uci) {
            if (this.status === 'lose') throw new Error('game over');
            var dir = parse2048Dir(uci);
            if (dir === '') throw new Error('bad move');
            var res = slideBoard(this.board, dir);
            if (!res.moved) throw new Error('no tiles moved');
            this.board = res.board;
            this.score += res.gained;
            this.moves++;
            this.lastMove = dir;
            this.history.push(dir);
            this.spawnTile();
            if (!this.won && this.hasTile(2048)) {
                this.won = true;
                this.status = 'win';
                this.winner = 'human';
                this.message = 'Bạn đạt 2048!';
            }
            if (!this.canMove()) {
                this.status = 'lose';
                this.message = 'Hết nước đi. Điểm: ' + this.score + '.';
            } else if (this.status === 'playing') {
                this.message = 'Tiếp tục.';
            }
            var scoreCopy = this.score;
            var resp = this.snapshot();
            resp.youMove = dir;
            resp.gained = res.gained;

            WG.queueGameSpeak('g2048', WG.buildPlaceSpokenHumanOnly('g2048', dir, resp.status, resp.winner), '2048. Điểm: ' + scoreCopy + '.');
            return resp;
        }
    }

    var inst = null;
    function getG2048() {
        if (inst === null) inst = new G2048Game();
        return inst;
    }

    WG.registerBoardGame({
        name: 'G2048',
        exitDefault: 'g2048',
        summary: function () { return getG2048().summaryText(); },
        snapshot: function () { return getG2048().snapshot(); },
        reset: function () { return getG2048().reset(); },
        setDifficulty: function (s) { return getG2048().setDifficulty(s); },
        getDifficulty: function () { return getG2048().getDifficulty(); },
        playUCI: function (u) { return getG2048().playUCI(u); },
        legalUCIs: function () { return getG2048().legalUCIs(); },
        newGameSpeak: function () {
            return WG.speakNew(
                'New 2048 game. Merge matching tiles to reach 2048.',
                'Ván 2048 mới. Gộp các ô cùng số để đạt 2048.'
            );
        }
    });
})(window.WG);
