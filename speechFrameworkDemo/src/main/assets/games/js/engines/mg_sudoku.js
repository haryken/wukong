/* Port of mods/mg_sudoku.go — Sudoku 9x9, solo puzzle (no bot turn).
 * UCI "a0:5" / "a05" sets digit 1-9; "a0:0" / "a0x" clears; "hint" fills one correct empty cell. */
(function (WG) {
    'use strict';

    var sudokuN = 9;

    // One hard-coded, uniquely-solvable puzzle per difficulty (row-major, '0' = empty).
    var sudokuPuzzles = {};
    sudokuPuzzles[WG.diffEasy] = '000068710300970804081002630512703090800629100600154000067005302420000900138000540';
    sudokuPuzzles[WG.diffMedium] = '009060015000000800001000630502700400800009100603054278907400300400830000100096000';
    sudokuPuzzles[WG.diffHard] = '200060000006001804701500609000783400000000000600004070000005300000800060100096007';

    function grid(v) {
        var g = [];
        for (var r = 0; r < sudokuN; r++) {
            var row = [];
            for (var c = 0; c < sudokuN; c++) row.push(v);
            g.push(row);
        }
        return g;
    }

    function parseSudokuUCI(s) {
        var bad = { file: 0, rank: 0, val: 0, hint: false, ok: false };
        s = (s == null ? '' : String(s)).trim().toLowerCase();
        if (s === 'hint') return { file: 0, rank: 0, val: 0, hint: true, ok: true };
        if (s.length < 2) return bad;
        if (s[0] < 'a' || s[0] > 'i') return bad;
        if (s[1] < '0' || s[1] > '8') return bad;
        var file = s.charCodeAt(0) - 97;
        var rank = s.charCodeAt(1) - 48;
        var rest = s.slice(2);
        if (rest.indexOf(':') === 0) rest = rest.slice(1);
        var val;
        switch (rest) {
            case '':
                return bad;
            case 'x':
            case '0':
                val = 0;
                break;
            default:
                if (rest.length !== 1 || rest[0] < '1' || rest[0] > '9') return bad;
                val = rest.charCodeAt(0) - 48;
        }
        return { file: file, rank: rank, val: val, hint: false, ok: true };
    }

    function sudokuValidPlace(board, r, c, v) {
        for (var i = 0; i < sudokuN; i++) {
            if (board[r][i] === v || board[i][c] === v) return false;
        }
        var br = Math.floor(r / 3) * 3, bc = Math.floor(c / 3) * 3;
        for (var a = br; a < br + 3; a++) {
            for (var b = bc; b < bc + 3; b++) {
                if (board[a][b] === v) return false;
            }
        }
        return true;
    }

    // sudokuSolve fills empty (0) cells of board in place via backtracking.
    function sudokuSolve(board) {
        var r = -1, c = -1, found = false;
        for (var i = 0; i < sudokuN && !found; i++) {
            for (var j = 0; j < sudokuN; j++) {
                if (board[i][j] === 0) {
                    r = i; c = j; found = true;
                    break;
                }
            }
        }
        if (!found) return true;
        for (var v = 1; v <= 9; v++) {
            if (sudokuValidPlace(board, r, c, v)) {
                board[r][c] = v;
                if (sudokuSolve(board)) return true;
                board[r][c] = 0;
            }
        }
        return false;
    }

    class SudokuGame extends WG.MiniCommon {
        constructor(level) {
            super();
            this.board = grid(0);
            this.given = grid(false);
            this.winner = '';
            this.lastMove = '';
            this.history = [];
            this.botThinking = false;
            this.thinkGen = 0;
            this.moves = 0;
            this.difficulty = WG.normalizeGameDifficulty(level);
            this.humanTurn = true;
            this.status = 'playing';
            this.message = 'Điền số 1-9. Không được lặp trong hàng, cột, hoặc ô 3x3.';
            this.loadPuzzleLocked(this.difficulty);
        }

        loadPuzzleLocked(level) {
            var s = sudokuPuzzles[WG.normalizeGameDifficulty(level)];
            if (typeof s !== 'string' || s.length !== sudokuN * sudokuN) s = sudokuPuzzles[WG.diffMedium];
            for (var i = 0; i < s.length; i++) {
                var ch = s[i];
                var r = Math.floor(i / sudokuN), c = i % sudokuN;
                if (ch >= '1' && ch <= '9') {
                    this.board[r][c] = ch.charCodeAt(0) - 48;
                    this.given[r][c] = true;
                } else {
                    this.board[r][c] = 0;
                    this.given[r][c] = false;
                }
            }
        }

        // hasConflictAt reports whether the digit at (r,c) duplicates in its row/col/box.
        hasConflictAt(r, c) {
            var v = this.board[r][c];
            if (v === 0) return false;
            for (var i = 0; i < sudokuN; i++) {
                if (i !== c && this.board[r][i] === v) return true;
                if (i !== r && this.board[i][c] === v) return true;
            }
            var br = Math.floor(r / 3) * 3, bc = Math.floor(c / 3) * 3;
            for (var a = br; a < br + 3; a++) {
                for (var b = bc; b < bc + 3; b++) {
                    if ((a !== r || b !== c) && this.board[a][b] === v) return true;
                }
            }
            return false;
        }

        boardValid() {
            for (var r = 0; r < sudokuN; r++) {
                for (var c = 0; c < sudokuN; c++) {
                    if (this.hasConflictAt(r, c)) return false;
                }
            }
            return true;
        }

        boardFull() {
            for (var r = 0; r < sudokuN; r++) {
                for (var c = 0; c < sudokuN; c++) {
                    if (this.board[r][c] === 0) return false;
                }
            }
            return true;
        }

        // findHintLocked solves the original puzzle (fixed givens only) and returns a
        // correct value for the first empty cell of the live board; that cell then becomes fixed.
        findHintLocked() {
            var puzzle = grid(0);
            var r, c;
            for (r = 0; r < sudokuN; r++) {
                for (c = 0; c < sudokuN; c++) {
                    if (this.given[r][c]) puzzle[r][c] = this.board[r][c];
                }
            }
            if (!sudokuSolve(puzzle)) return { file: 0, rank: 0, val: 0, found: false };
            for (r = 0; r < sudokuN; r++) {
                for (c = 0; c < sudokuN; c++) {
                    if (this.board[r][c] === 0) {
                        this.board[r][c] = puzzle[r][c];
                        this.given[r][c] = true;
                        return { file: c, rank: r, val: puzzle[r][c], found: true };
                    }
                }
            }
            return { file: 0, rank: 0, val: 0, found: false };
        }

        boardRows() {
            var rows = new Array(sudokuN);
            for (var r = 0; r < sudokuN; r++) {
                var s = '';
                for (var c = 0; c < sudokuN; c++) {
                    s += this.board[r][c] === 0 ? '.' : String(this.board[r][c]);
                }
                rows[r] = s;
            }
            return rows;
        }

        givenRows() {
            var rows = new Array(sudokuN);
            for (var r = 0; r < sudokuN; r++) {
                var s = '';
                for (var c = 0; c < sudokuN; c++) {
                    s += this.given[r][c] ? '1' : '0';
                }
                rows[r] = s;
            }
            return rows;
        }

        snapshot() {
            var extra = {
                board: this.boardRows(), given: this.givenRows(),
                boardW: sudokuN, boardH: sudokuN,
                valid: this.boardValid()
            };
            return this.baseSnap('sudoku', 'sudoku', extra);
        }

        summaryText() {
            var b = 'Sudoku 9x9 độ khó ' + WG.normalizeGameDifficulty(this.difficulty) + ' trên web Vector. Trạng thái: ' + this.status + '. ';
            if (this.lastMove !== '') b += 'Nước gần nhất: ' + this.lastMove + '. ';
            b += 'Số nước: ' + this.moves + '.';
            return b;
        }

        reset() {
            var prev = WG.normalizeGameDifficulty(this.difficulty);
            var ng = new SudokuGame(prev);
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
            for (var r = 0; r < sudokuN; r++) {
                for (var c = 0; c < sudokuN; c++) {
                    if (!this.given[r][c]) (out = out || []).push(WG.miniSqName(c, r));
                }
            }
            return out;
        }

        playUCI(uci) {
            if (this.status !== 'playing') throw new Error('game over');
            var p = parseSudokuUCI(uci);
            if (!p.ok) throw new Error('bad move');
            var file = p.file, rank = p.rank, val = p.val;
            var moveDesc, u;
            if (p.hint) {
                var h = this.findHintLocked();
                if (!h.found) throw new Error('no hint available');
                u = WG.miniSqName(h.file, h.rank);
                this.lastMove = u + ':' + h.val;
                this.history.push('hint:' + u);
                this.moves++;
                moveDesc = 'gợi ý ' + u + '=' + h.val;
            } else {
                if (rank < 0 || rank >= sudokuN || file < 0 || file >= sudokuN) throw new Error('out of range');
                if (this.given[rank][file]) throw new Error('cell is fixed');
                u = WG.miniSqName(file, rank);
                this.board[rank][file] = val;
                if (val === 0) {
                    this.lastMove = u + ':x';
                    this.history.push('clear:' + u);
                    moveDesc = 'xoá ' + u;
                } else {
                    this.lastMove = u + ':' + val;
                    this.history.push(u + ':' + val);
                    moveDesc = u + '=' + val;
                }
                this.moves++;
            }
            var valid = this.boardValid();
            var full = this.boardFull();
            if (full && valid) {
                this.status = 'win';
                this.winner = 'human';
                this.message = 'Chúc mừng! Bạn đã giải xong Sudoku.';
            } else if (!valid) {
                this.message = 'Có xung đột trong hàng, cột, hoặc ô 3x3.';
            } else {
                this.message = 'Tiếp tục điền số.';
            }
            var resp = this.snapshot();
            resp.youMove = moveDesc;

            WG.queueGameSpeak('sudoku', WG.buildPlaceSpokenHumanOnly('sudoku', moveDesc, resp.status, resp.winner), 'Sudoku. ' + moveDesc + '.');
            return resp;
        }
    }

    var inst = null;
    function getSudoku() {
        if (inst === null) inst = new SudokuGame(WG.diffMedium);
        return inst;
    }

    WG.registerBoardGame({
        name: 'Sudoku',
        exitDefault: 'sudoku',
        summary: function () { return getSudoku().summaryText(); },
        snapshot: function () { return getSudoku().snapshot(); },
        reset: function () { return getSudoku().reset(); },
        setDifficulty: function (s) { return getSudoku().setDifficulty(s); },
        getDifficulty: function () { return getSudoku().getDifficulty(); },
        playUCI: function (u) { return getSudoku().playUCI(u); },
        legalUCIs: function () { return getSudoku().legalUCIs(); },
        newGameSpeak: function () {
            return WG.speakNew(
                'New sudoku puzzle. Fill in digits 1 to 9.',
                'Ván sudoku mới. Điền số từ 1 đến 9.'
            );
        }
    });
})(window.WG);
