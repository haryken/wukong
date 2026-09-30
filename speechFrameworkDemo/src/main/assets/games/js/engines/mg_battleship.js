/* Port of mods/mg_battleship.go — Battleship 8x8, both fleets auto-placed randomly.
 * Player fires on the enemy via UCI a0-h7; bot fires back after every player shot. */
(function (WG) {
    'use strict';

    var bsN = 8;
    var bsShipSizes = [5, 4, 3, 3, 2];

    function grid(v) {
        var g = [];
        for (var r = 0; r < bsN; r++) {
            var row = [];
            for (var c = 0; c < bsN; c++) row.push(v);
            g.push(row);
        }
        return g;
    }

    function canPlaceShip(g, r, c, size, horiz) {
        for (var i = -1; i <= size; i++) {
            for (var j = -1; j <= 1; j++) {
                var rr, cc;
                if (horiz) {
                    rr = r + j; cc = c + i;
                } else {
                    rr = r + i; cc = c + j;
                }
                if (rr < 0 || rr >= bsN || cc < 0 || cc >= bsN) continue;
                if (g[rr][cc]) return false;
            }
        }
        return true;
    }

    function placeFleetRandom() {
        var g = grid(false);
        var idx = grid(-1);
        var fleet = [];
        for (var s = 0; s < bsShipSizes.length; s++) {
            var size = bsShipSizes[s];
            for (;;) {
                var horiz = WG.randInt(2) === 0;
                var r, c;
                if (horiz) {
                    r = WG.randInt(bsN);
                    c = WG.randInt(bsN - size + 1);
                } else {
                    r = WG.randInt(bsN - size + 1);
                    c = WG.randInt(bsN);
                }
                if (!canPlaceShip(g, r, c, size, horiz)) continue;
                var ship = { cells: [], hits: 0 };
                for (var i = 0; i < size; i++) {
                    var rr = r, cc = c;
                    if (horiz) cc += i; else rr += i;
                    g[rr][cc] = true;
                    idx[rr][cc] = fleet.length;
                    ship.cells.push([rr, cc]);
                }
                fleet.push(ship);
                break;
            }
        }
        return { grid: g, idx: idx, fleet: fleet };
    }

    function fleetRemaining(fleet) {
        var n = 0;
        for (var i = 0; i < fleet.length; i++) n += fleet[i].cells.length - fleet[i].hits;
        return n;
    }

    function fleetSunkCount(fleet) {
        var n = 0;
        for (var i = 0; i < fleet.length; i++) {
            if (fleet[i].hits >= fleet[i].cells.length) n++;
        }
        return n;
    }

    class BattleshipGame extends WG.MiniCommon {
        constructor() {
            super();
            this.winner = '';
            this.lastMove = '';
            this.history = [];
            this.botThinking = false;
            this.thinkGen = 0;
            this.moves = 0;
            this.humanTurn = true;
            this.status = 'playing';
            this.difficulty = WG.diffMedium;
            this.message = 'Bắn vào bàn đối phương.';
            var enemy = placeFleetRandom();
            this.enemyGrid = enemy.grid;
            this.enemyIdx = enemy.idx;
            this.enemyFleet = enemy.fleet;
            this.enemyHit = grid(false);
            var mine = placeFleetRandom();
            this.myGrid = mine.grid;
            this.myIdx = mine.idx;
            this.myFleet = mine.fleet;
            this.myHit = grid(false);
            this.botTargets = [];
        }

        // fireAt marks a shot against idx/fleet at (r,c); hit is true iff a ship
        // occupies that cell, sunk is true iff that ship's cells are all hit now.
        fireAt(idx, fleet, r, c) {
            var i = idx[r][c];
            if (i < 0) return { hit: false, sunk: false };
            fleet[i].hits++;
            return { hit: true, sunk: fleet[i].hits >= fleet[i].cells.length };
        }

        botFireLocked() {
            var r = -1, c = -1, found = false;
            while (this.botTargets.length > 0) {
                var p = this.botTargets.shift();
                if (p.r >= 0 && p.r < bsN && p.c >= 0 && p.c < bsN && !this.myHit[p.r][p.c]) {
                    r = p.r; c = p.c; found = true;
                    break;
                }
            }
            if (!found) {
                for (;;) {
                    var rr = WG.randInt(bsN), cc = WG.randInt(bsN);
                    if (!this.myHit[rr][cc]) {
                        r = rr; c = cc;
                        break;
                    }
                }
            }
            this.myHit[r][c] = true;
            var res = this.fireAt(this.myIdx, this.myFleet, r, c);
            var u = WG.miniSqName(c, r);
            if (res.hit && !res.sunk) {
                this.botTargets.push({ r: r - 1, c: c }, { r: r + 1, c: c }, { r: r, c: c - 1 }, { r: r, c: c + 1 });
            }
            return { u: u, hit: res.hit, sunk: res.sunk };
        }

        enemyBoardRows() {
            var rows = new Array(bsN);
            for (var r = 0; r < bsN; r++) {
                var s = '';
                for (var c = 0; c < bsN; c++) {
                    var i = this.enemyIdx[r][c];
                    if (!this.enemyHit[r][c]) s += '.';
                    else if (i < 0) s += 'X';
                    else if (this.enemyFleet[i].hits >= this.enemyFleet[i].cells.length) s += 'S';
                    else s += 'H';
                }
                rows[r] = s;
            }
            return rows;
        }

        myBoardRows() {
            var rows = new Array(bsN);
            for (var r = 0; r < bsN; r++) {
                var s = '';
                for (var c = 0; c < bsN; c++) {
                    var i = this.myIdx[r][c];
                    if (this.myHit[r][c] && i >= 0 && this.myFleet[i].hits >= this.myFleet[i].cells.length) s += 'S';
                    else if (this.myHit[r][c] && i >= 0) s += 'H';
                    else if (this.myHit[r][c]) s += 'X';
                    else if (this.myGrid[r][c]) s += 'O';
                    else s += '.';
                }
                rows[r] = s;
            }
            return rows;
        }

        snapshot() {
            var extra = {
                board: this.enemyBoardRows(), myBoard: this.myBoardRows(),
                boardW: bsN, boardH: bsN,
                enemyRemaining: fleetRemaining(this.enemyFleet), myRemaining: fleetRemaining(this.myFleet),
                enemySunk: fleetSunkCount(this.enemyFleet), mySunk: fleetSunkCount(this.myFleet),
                fleetSizes: bsShipSizes.slice()
            };
            return this.baseSnap('battleship', 'battleship', extra);
        }

        summaryText() {
            return 'Battleship 8x8 trên web Vector. Ô tàu còn lại của bạn: ' + fleetRemaining(this.myFleet) +
                ', đối phương: ' + fleetRemaining(this.enemyFleet) + '. Trạng thái: ' + this.status + '.';
        }

        reset() {
            var prev = WG.normalizeGameDifficulty(this.difficulty);
            this.thinkGen++;
            this.botThinking = false;
            var ng = new BattleshipGame();
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
            for (var r = 0; r < bsN; r++) {
                for (var c = 0; c < bsN; c++) {
                    if (!this.enemyHit[r][c]) (out = out || []).push(WG.miniSqName(c, r));
                }
            }
            return out;
        }

        playUCI(uci) {
            if (this.botThinking) throw new Error('bot thinking');
            if (this.status !== 'playing') throw new Error('game over');
            if (!this.humanTurn) throw new Error('not your turn');
            var sq = WG.miniParseSq(uci);
            var file = sq.file, rank = sq.rank;
            if (!sq.ok || file < 0 || file >= bsN || rank < 0 || rank >= bsN) throw new Error('bad move');
            if (this.enemyHit[rank][file]) throw new Error('already fired there');
            var u = WG.miniSqName(file, rank);
            this.enemyHit[rank][file] = true;
            var res = this.fireAt(this.enemyIdx, this.enemyFleet, rank, file);
            var hit = res.hit, sunk = res.sunk;
            var msg;
            if (sunk) msg = 'Bắn chìm tàu!';
            else if (hit) msg = 'Trúng!';
            else msg = 'Trượt.';
            var mark = hit ? '*' : '';
            this.lastMove = u;
            this.history.push('H:' + u + mark);
            this.moves++;
            if (fleetRemaining(this.enemyFleet) <= 0) {
                this.status = 'win';
                this.winner = 'human';
                this.message = 'Bạn đã đánh chìm toàn bộ hạm đội!';
            } else {
                this.humanTurn = false;
                this.message = msg + ' Đến lượt bot.';
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
            resp.hit = hit;
            resp.sunk = sunk;

            WG.queueGameSpeak('battleship', WG.buildPlaceSpokenHumanOnly('battleship', u, resp.status, resp.winner), 'Battleship. Bạn bắn ' + u + '. ' + msg);
            if (needBot) {
                this.runBotThink(thinkGen);
            }
            return resp;
        }

        async runBotThink(gen) {
            await WG.sleep(600);
            if (gen !== this.thinkGen || !this.botThinking) return;
            var botMove = '', msg = '';
            if (this.status === 'playing' && !this.humanTurn) {
                var shot = this.botFireLocked();
                var u = shot.u;
                botMove = u;
                if (shot.sunk) msg = 'Bot bắn chìm tàu của bạn tại ' + u + '!';
                else if (shot.hit) msg = 'Bot bắn trúng ' + u + '.';
                else msg = 'Bot bắn trượt ' + u + '.';
                var mark = shot.hit ? '*' : '';
                this.lastMove = u;
                this.history.push('B:' + u + mark);
                this.moves++;
                if (fleetRemaining(this.myFleet) <= 0) {
                    this.status = 'lose';
                    this.winner = 'bot';
                    this.message = 'Bot đã đánh chìm hạm đội của bạn.';
                } else {
                    this.humanTurn = true;
                    this.message = msg + ' Đến lượt bạn.';
                }
            }
            this.botThinking = false;
            var status = this.status, winner = this.winner;
            if (botMove !== '') {
                WG.queueGameSpeak('battleship', WG.buildPlaceSpokenBotOnly('battleship', botMove, status, winner), 'Battleship. ' + msg);
            }
        }
    }

    var inst = null;
    function getBattleship() {
        if (inst === null) inst = new BattleshipGame();
        return inst;
    }

    WG.registerBoardGame({
        name: 'Battleship',
        exitDefault: 'battleship',
        summary: function () { return getBattleship().summaryText(); },
        snapshot: function () { return getBattleship().snapshot(); },
        reset: function () { return getBattleship().reset(); },
        setDifficulty: function (s) { return getBattleship().setDifficulty(s); },
        getDifficulty: function () { return getBattleship().getDifficulty(); },
        playUCI: function (u) { return getBattleship().playUCI(u); },
        legalUCIs: function () { return getBattleship().legalUCIs(); },
        newGameSpeak: function () {
            return WG.speakNew(
                'New battleship game. Fire at the enemy board.',
                'Ván battleship mới. Bắn vào bàn đối phương.'
            );
        }
    });
})(window.WG);
