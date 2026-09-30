// Port of mods/mg_hangman.go — Hangman tiếng Việt (không dấu), maxWrong = 7.
(function (WG) {
  'use strict';

  var hangmanMaxWrong = 7;

  var hangmanWords = [
    'NGAY', 'BUOI', 'CHAO', 'TUOI', 'SACH', 'HOA', 'MEO', 'CHO', 'VIT', 'CAY',
    'NUOC', 'LUA', 'DAT', 'TROI', 'MAY', 'MUA', 'NANG', 'GIO', 'BIEN', 'NUI',
    'SONG', 'RUNG', 'CHIM', 'TOM', 'CUA', 'GAO', 'BANH', 'KEO', 'SUA', 'TRA'
  ];

  function trimPrefix(s, p) {
    return s.indexOf(p) === 0 ? s.slice(p.length) : s;
  }

  class HangmanGame extends WG.MiniCommon {
    constructor() {
      super();
      this.secret = hangmanWords[WG.randInt(hangmanWords.length)];
      this.guessed = {};
      this.wrong = [];
      this.status = 'playing';
      this.winner = '';
      this.lastMove = '';
      this.history = [];
      this.humanTurn = true;
      this.botThinking = false;
      this.thinkGen = 0;
      this.moves = 0;
      this.difficulty = WG.diffMedium;
      this.message = WG.viOrEN('Ván treo cổ mới. Đoán từng chữ cái.', 'New hangman game. Guess a letter.')[0];
    }

    masked() {
      var b = '';
      for (var i = 0; i < this.secret.length; i++) {
        var ch = this.secret[i];
        b += this.guessed[ch] ? ch : '_';
      }
      return b;
    }

    won() {
      for (var i = 0; i < this.secret.length; i++) {
        if (!this.guessed[this.secret[i]]) return false;
      }
      return true;
    }

    snapshot() {
      var extra = {
        masked: this.masked(),
        wrong: this.wrong.slice(),
        wrongCount: this.wrong.length,
        maxWrong: hangmanMaxWrong,
        wordLen: this.secret.length
      };
      if (this.status !== 'playing') {
        extra.secret = this.secret;
      }
      return this.baseSnap('hangman', 'hangman', extra);
    }

    summaryText() {
      return WG.sprintf('Hangman tiếng Việt không dấu. Từ: %s. Sai %d/%d. Trạng thái: %s.',
        this.masked(), this.wrong.length, hangmanMaxWrong, this.status);
    }

    reset() {
      var prevDiff = this.difficulty;
      var ng = new HangmanGame();
      if (prevDiff) ng.difficulty = prevDiff;
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
      for (var c = 65; c <= 90; c++) {
        var ch = String.fromCharCode(c);
        if (!this.guessed[ch]) {
          if (!out) out = [];
          out.push(ch.toLowerCase());
        }
      }
      return out;
    }

    playUCI(uci) {
      if (this.status !== 'playing') {
        throw new Error('game over');
      }
      var letter = String(uci).trim().toUpperCase();
      letter = trimPrefix(letter, 'LETTER:');
      letter = letter.trim();
      if (letter.length !== 1 || letter < 'A' || letter > 'Z') {
        throw new Error('cần đúng 1 chữ cái A-Z');
      }
      if (this.guessed[letter]) {
        throw new Error('đã đoán chữ này rồi');
      }
      this.guessed[letter] = true;
      this.moves++;
      this.lastMove = letter;

      var hit = this.secret.indexOf(letter) !== -1;
      if (hit) {
        this.history.push(letter + ':hit');
        this.message = WG.viOrEN('Đúng rồi!', 'Correct!')[0];
      } else {
        this.wrong.push(letter);
        this.history.push(letter + ':miss');
        this.message = WG.viOrEN('Sai rồi!', 'Wrong!')[0];
      }

      if (this.won()) {
        this.status = 'win';
        this.winner = 'human';
        this.message = WG.viOrEN('Bạn đoán đúng cả từ! Bạn thắng.', 'You guessed the whole word! You win.')[0];
      } else if (this.wrong.length >= hangmanMaxWrong) {
        this.status = 'lose';
        this.winner = 'bot';
        this.message = WG.speakf('Bạn thua! Từ đúng là %s.', 'You lose! The word was %s.', this.secret)[0];
      }

      var resp = this.snapshot();
      var msg = this.message;
      WG.queueGameSpeak('hangman', msg, 'Hangman: ' + letter);
      return resp;
    }
  }

  var inst = null;
  function getHangman() {
    if (!inst) inst = new HangmanGame();
    return inst;
  }

  WG.registerBoardGame({
    name: 'Hangman',
    exitDefault: 'hangman',
    summary: function () { return getHangman().summaryText(); },
    snapshot: function () { return getHangman().snapshot(); },
    reset: function () { return getHangman().reset(); },
    setDifficulty: function (s) { return getHangman().setDifficulty(s); },
    getDifficulty: function () { return getHangman().getDifficulty(); },
    playUCI: function (u) { return getHangman().playUCI(u); },
    legalUCIs: function () { return getHangman().legalUCIs(); },
    newGameSpeak: function () {
      var say = WG.viOrEN('Ván treo cổ mới. Đoán từng chữ cái tiếng Việt không dấu.', 'New hangman game. Guess a letter of the Vietnamese word (no diacritics).')[0];
      return [say, 'Hangman. Ván mới.'];
    }
  });
})(window.WG);
