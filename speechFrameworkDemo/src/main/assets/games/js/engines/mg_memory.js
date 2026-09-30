/* Port of mods/mg_memory.go — Memory (concentration) 4x4, 8 pairs A-H. UCI square a0-d3 flips a card.
 * Human flips 2 cards; on mismatch turn passes to the bot, which also flips 2. */
(function (WG) {
    'use strict';

    var memN = 4;

    function grid(v) {
        var g = [];
        for (var r = 0; r < memN; r++) {
            var row = [];
            for (var c = 0; c < memN; c++) row.push(v);
            g.push(row);
        }
        return g;
    }

    function shuffled(arr) {
        var res = WG.shuffle(arr);
        return Array.isArray(res) ? res : arr;
    }

    class MemoryGame extends WG.MiniCommon {
        constructor() {
            super();
            this.cards = grid('');
            this.matched = grid(false);
            this.faceUp = grid(false);
            this.pending = null;
            this.known = new Map(); // remembered symbols from prior flips (both sides), key r*memN+c
            this.pairsHuman = 0;
            this.pairsBot = 0;
            this.winner = '';
            this.lastMove = '';
            this.history = [];
            this.botThinking = false;
            this.thinkGen = 0;
            this.moves = 0;
            this.humanTurn = true;
            this.status = 'playing';
            this.difficulty = WG.diffMedium;
            this.message = 'Lật 2 ô để tìm cặp giống nhau.';
            this.shuffleCards();
        }

        shuffleCards() {
            var symbols = shuffled(['A', 'A', 'B', 'B', 'C', 'C', 'D', 'D', 'E', 'E', 'F', 'F', 'G', 'G', 'H', 'H']);
            var idx = 0;
            for (var r = 0; r < memN; r++) {
                for (var c = 0; c < memN; c++) {
                    this.cards[r][c] = symbols[idx];
                    idx++;
                }
            }
        }

        rememberLocked(r, c) {
            this.known.set(r * memN + c, this.cards[r][c]);
        }

        // knownEntries mimics Go's randomized map iteration order.
        knownEntries() {
            var out = [];
            this.known.forEach(function (sym, key) {
                out.push({ r: Math.floor(key / memN), c: key % memN, sym: sym });
            });
            return shuffled(out);
        }

        boardRows() {
            var rows = new Array(memN);
            for (var r = 0; r < memN; r++) {
                var s = '';
                for (var c = 0; c < memN; c++) {
                    if (this.matched[r][c]) s += this.cards[r][c].toLowerCase();
                    else if (this.faceUp[r][c]) s += this.cards[r][c];
                    else s += '?';
                }
                rows[r] = s;
            }
            return rows;
        }

        snapshot() {
            var extra = {
                board: this.boardRows(), boardW: memN, boardH: memN,
                pairsHuman: this.pairsHuman, pairsBot: this.pairsBot
            };
            return this.baseSnap('memory', 'memory', extra);
        }

        summaryText() {
            return 'Trò chơi trí nhớ 4x4 (8 cặp) trên web Vector. Bạn: ' + this.pairsHuman + ' cặp, Bot: ' +
                this.pairsBot + ' cặp. Trạng thái: ' + this.status + '.';
        }

        reset() {
            var prev = WG.normalizeGameDifficulty(this.difficulty);
            this.thinkGen++;
            this.botThinking = false;
            var ng = new MemoryGame();
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
            for (var r = 0; r < memN; r++) {
                for (var c = 0; c < memN; c++) {
                    if (!this.matched[r][c] && !this.faceUp[r][c]) (out = out || []).push(WG.miniSqName(c, r));
                }
            }
            return out;
        }

        checkGameOverLocked() {
            if (this.pairsHuman + this.pairsBot === memN * memN / 2) {
                if (this.pairsHuman > this.pairsBot) {
                    this.status = 'win'; this.winner = 'human'; this.message = 'Bạn thắng!';
                } else if (this.pairsBot > this.pairsHuman) {
                    this.status = 'win'; this.winner = 'bot'; this.message = 'Bot thắng.';
                } else {
                    this.status = 'draw'; this.winner = ''; this.message = 'Hoà.';
                }
            }
        }

        playUCI(uci) {
            if (this.botThinking) throw new Error('bot thinking');
            if (this.status !== 'playing') throw new Error('game over');
            if (!this.humanTurn) throw new Error('not your turn');
            var sq = WG.miniParseSq(uci);
            var file = sq.file, rank = sq.rank;
            if (!sq.ok || file < 0 || file >= memN || rank < 0 || rank >= memN) throw new Error('bad move');
            if (this.matched[rank][file] || this.faceUp[rank][file]) throw new Error('cell unavailable');
            var u = WG.miniSqName(file, rank);
            this.faceUp[rank][file] = true;
            this.rememberLocked(rank, file);

            var resp;
            if (this.pending === null) {
                this.pending = { file: file, rank: rank };
                this.lastMove = u;
                this.history.push('H1:' + u);
                this.message = 'Lật ô thứ hai.';
                resp = this.snapshot();
                resp.youMove = u;
                resp.flipStage = 1;
                WG.queueGameSpeak('memory', 'Bạn lật ' + u + '.', 'Trí nhớ. Lật ' + u + '.');
                return resp;
            }

            var p = this.pending;
            this.pending = null;
            var u1 = WG.miniSqName(p.file, p.rank);
            var matched = this.cards[p.rank][p.file] === this.cards[rank][file];
            var speakMsg;
            if (matched) {
                this.matched[p.rank][p.file] = true;
                this.matched[rank][file] = true;
                this.pairsHuman++;
                this.message = 'Trùng khớp! Bạn đi tiếp.';
                speakMsg = 'Trùng cặp ' + u1 + ' và ' + u + '. Bạn đi tiếp.';
            } else {
                this.faceUp[p.rank][p.file] = false;
                this.faceUp[rank][file] = false;
                this.message = 'Không trùng. Đến lượt bot.';
                speakMsg = 'Không trùng ' + u1 + ' và ' + u + '.';
            }
            this.lastMove = u;
            this.history.push('H2:' + u);
            this.moves++;
            this.checkGameOverLocked();
            var gameOver = this.status !== 'playing';

            var needBot = false;
            var thinkGen = 0;
            if (!gameOver && !matched) {
                this.humanTurn = false;
                this.botThinking = true;
                this.thinkGen++;
                thinkGen = this.thinkGen;
                needBot = true;
            }
            resp = this.snapshot();
            resp.youMove = u;
            resp.matched = matched;
            resp.flipStage = 2;
            resp.pair = [u1, u];

            WG.queueGameSpeak('memory', WG.buildPlaceSpokenHumanOnly('memory', speakMsg, resp.status, resp.winner), 'Trí nhớ. ' + speakMsg);
            if (needBot) {
                this.runBotThink(thinkGen);
            }
            return resp;
        }

        // memoryChance is the % chance the bot uses its remembered-card knowledge.
        memoryChance() {
            switch (WG.normalizeGameDifficulty(this.difficulty)) {
                case WG.diffEasy:
                    return 25;
                case WG.diffHard:
                    return 100;
                default:
                    return 60;
            }
        }

        findKnownPairLocked() {
            var bySym = new Map();
            var entries = this.knownEntries();
            for (var i = 0; i < entries.length; i++) {
                var e = entries[i];
                if (this.matched[e.r][e.c] || this.faceUp[e.r][e.c]) continue;
                if (!bySym.has(e.sym)) bySym.set(e.sym, []);
                bySym.get(e.sym).push([e.r, e.c]);
            }
            var groups = shuffled(Array.from(bySym.values()));
            for (var j = 0; j < groups.length; j++) {
                var positions = groups[j];
                if (positions.length >= 2) {
                    return { r1: positions[0][0], c1: positions[0][1], r2: positions[1][0], c2: positions[1][1], ok: true };
                }
            }
            return { r1: 0, c1: 0, r2: 0, c2: 0, ok: false };
        }

        randomUnrevealedLocked(excludeR, excludeC) {
            var cands = [];
            for (var r = 0; r < memN; r++) {
                for (var c = 0; c < memN; c++) {
                    if (this.matched[r][c] || this.faceUp[r][c]) continue;
                    if (r === excludeR && c === excludeC) continue;
                    cands.push([r, c]);
                }
            }
            if (cands.length === 0) return [0, 0];
            var p = cands[WG.randInt(cands.length)];
            return [p[0], p[1]];
        }

        // botPickLocked chooses the bot's next flip. excludeR<0 means this is the
        // first flip of the turn; otherwise it is the second, excluding the first.
        botPickLocked(excludeR, excludeC) {
            var useMemory = WG.randInt(100) < this.memoryChance();
            if (excludeR < 0) {
                if (useMemory) {
                    var kp = this.findKnownPairLocked();
                    if (kp.ok) return [kp.r1, kp.c1];
                }
                return this.randomUnrevealedLocked(-1, -1);
            }
            if (useMemory) {
                var target = this.cards[excludeR][excludeC];
                var entries = this.knownEntries();
                for (var i = 0; i < entries.length; i++) {
                    var e = entries[i];
                    if (e.sym === target && !(e.r === excludeR && e.c === excludeC) &&
                        !this.matched[e.r][e.c] && !this.faceUp[e.r][e.c]) {
                        return [e.r, e.c];
                    }
                }
            }
            return this.randomUnrevealedLocked(excludeR, excludeC);
        }

        async runBotThink(gen) {
            await WG.sleep(700);
            if (gen !== this.thinkGen || !this.botThinking) return;
            if (this.status !== 'playing' || this.humanTurn) {
                this.botThinking = false;
                return;
            }
            var p1 = this.botPickLocked(-1, -1);
            var r1 = p1[0], c1 = p1[1];
            this.faceUp[r1][c1] = true;
            this.rememberLocked(r1, c1);
            var u1 = WG.miniSqName(c1, r1);

            await WG.sleep(500);

            if (gen !== this.thinkGen) return;
            var p2 = this.botPickLocked(r1, c1);
            var r2 = p2[0], c2 = p2[1];
            this.faceUp[r2][c2] = true;
            this.rememberLocked(r2, c2);
            var u2 = WG.miniSqName(c2, r2);
            var matched = this.cards[r1][c1] === this.cards[r2][c2];
            var msg;
            if (matched) {
                this.matched[r1][c1] = true;
                this.matched[r2][c2] = true;
                this.pairsBot++;
                msg = 'Bot lật ' + u1 + ' và ' + u2 + ', trùng cặp!';
                this.message = 'Bot trùng cặp, bot đi tiếp.';
            } else {
                this.faceUp[r1][c1] = false;
                this.faceUp[r2][c2] = false;
                msg = 'Bot lật ' + u1 + ' và ' + u2 + ', không trùng.';
                this.message = 'Đến lượt bạn.';
            }
            this.moves++;
            this.lastMove = u2;
            this.history.push('B:' + u1 + ',' + u2);
            this.checkGameOverLocked();
            var gameOver = this.status !== 'playing';

            if (!gameOver && matched) {
                this.thinkGen++;
                var thinkGen2 = this.thinkGen;
                WG.queueGameSpeak('memory', msg, 'Trí nhớ. ' + msg);
                this.runBotThink(thinkGen2);
                return;
            }
            this.botThinking = false;
            if (!gameOver) this.humanTurn = true;
            var status = this.status, winner = this.winner;
            WG.queueGameSpeak('memory', WG.buildPlaceSpokenBotOnly('memory', msg, status, winner), 'Trí nhớ. ' + msg);
        }
    }

    var inst = null;
    function getMemory() {
        if (inst === null) inst = new MemoryGame();
        return inst;
    }

    WG.registerBoardGame({
        name: 'Memory',
        exitDefault: 'memory',
        summary: function () { return getMemory().summaryText(); },
        snapshot: function () { return getMemory().snapshot(); },
        reset: function () { return getMemory().reset(); },
        setDifficulty: function (s) { return getMemory().setDifficulty(s); },
        getDifficulty: function () { return getMemory().getDifficulty(); },
        playUCI: function (u) { return getMemory().playUCI(u); },
        legalUCIs: function () { return getMemory().legalUCIs(); },
        newGameSpeak: function () {
            return WG.speakNew(
                'New memory game. Flip two cards to find matching pairs.',
                'Ván trí nhớ mới. Lật 2 ô để tìm cặp giống nhau.'
            );
        }
    });
})(window.WG);
