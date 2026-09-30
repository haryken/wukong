/* Port of mods/mg_simon.go — Simon Says: 4 pads (0-3). The client animates `sequence` whenever
 * lastMove == "playback"; the player then repeats it one pad at a time. */
(function (WG) {
    'use strict';

    // atoi mirrors strconv.Atoi: optional sign followed by decimal digits only.
    function atoi(s) {
        if (!/^[+-]?[0-9]+$/.test(s)) return { n: 0, ok: false };
        var n = parseInt(s, 10);
        if (!isFinite(n)) return { n: 0, ok: false };
        return { n: n, ok: true };
    }

    class SimonGame extends WG.MiniCommon {
        constructor() {
            super();
            this.sequence = [WG.randInt(4)];
            this.playerInput = [];
            this.winner = '';
            this.history = [];
            this.botThinking = false;
            this.thinkGen = 0;
            this.moves = 0;
            this.status = 'playing';
            this.humanTurn = true;
            this.difficulty = WG.diffMedium;
            this.lastMove = 'playback';
            this.message = WG.viOrEN('Ván Simon mới. Xem dãy rồi lặp lại (0-3).', 'New Simon game. Watch the sequence, then repeat it (0-3).')[0];
        }

        snapshot() {
            var extra = {
                sequence: this.sequence.slice(),
                playerLen: this.playerInput.length,
                level: this.sequence.length
            };
            return this.baseSnap('simon', 'simon', extra);
        }

        summaryText() {
            return 'Simon Says. Mức hiện tại: ' + this.sequence.length + ' bước. Người chơi đã nhập ' +
                this.playerInput.length + '/' + this.sequence.length + '. Trạng thái: ' + this.status + '.';
        }

        reset() {
            var prevDiff = this.difficulty;
            var ng = new SimonGame();
            if (prevDiff !== '') ng.difficulty = prevDiff;
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
            return ['0', '1', '2', '3'];
        }

        playUCI(uci) {
            if (this.status !== 'playing') throw new Error('game over');
            var s = (uci == null ? '' : String(uci)).trim().toLowerCase();
            if (s.indexOf('pad:') === 0) s = s.slice(4);
            var a = atoi(s.trim());
            var pad = a.n;
            if (!a.ok || pad < 0 || pad > 3) throw new Error('cần một nút từ 0 đến 3');

            this.playerInput.push(pad);
            this.moves++;
            this.lastMove = String(pad);
            this.history.push(String(pad));
            var idx = this.playerInput.length - 1;

            var resp;
            if (this.playerInput[idx] !== this.sequence[idx]) {
                this.status = 'lose';
                this.winner = 'bot';
                var level = this.sequence.length;
                var seq = this.sequence.slice();
                this.playerInput = [];
                this.message = WG.speakf('Sai rồi! Bạn thua ở mức %d.', 'Wrong! You lost at level %d.', level)[0];
                resp = this.snapshot();
                resp.sequence = seq;
                WG.queueGameSpeak('simon', this.message, 'Simon: thua ở mức ' + level);
                return resp;
            }

            var msg;
            if (this.playerInput.length === this.sequence.length) {
                this.sequence.push(WG.randInt(4));
                this.playerInput = [];
                this.lastMove = 'playback';
                msg = WG.speakf('Chính xác! Dãy mới có %d bước.', 'Correct! New sequence has %d steps.', this.sequence.length)[0];
            } else {
                msg = WG.viOrEN('Tiếp tục...', 'Keep going...')[0];
            }
            this.message = msg;

            resp = this.snapshot();
            WG.queueGameSpeak('simon', msg, 'Simon');
            return resp;
        }
    }

    var inst = null;
    function getSimon() {
        if (inst === null) inst = new SimonGame();
        return inst;
    }

    WG.registerBoardGame({
        name: 'Simon',
        exitDefault: 'simon',
        summary: function () { return getSimon().summaryText(); },
        snapshot: function () { return getSimon().snapshot(); },
        reset: function () { return getSimon().reset(); },
        setDifficulty: function (s) { return getSimon().setDifficulty(s); },
        getDifficulty: function () { return getSimon().getDifficulty(); },
        playUCI: function (u) { return getSimon().playUCI(u); },
        legalUCIs: function () { return getSimon().legalUCIs(); },
        newGameSpeak: function () {
            var say = WG.viOrEN('Ván Simon mới. Xem dãy rồi lặp lại bằng các nút 0 đến 3.', 'New Simon game. Watch the sequence, then repeat it using pads 0 to 3.')[0];
            return [say, 'Simon. Ván mới.'];
        }
    });
})(window.WG);
