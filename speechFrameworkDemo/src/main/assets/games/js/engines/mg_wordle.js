// Port of mods/mg_wordle.go — Wordle tiếng Việt (không dấu): 5-letter words, 6 guesses.
(function (WG) {
  'use strict';

  var wordleWordLen = 5;
  var wordleMaxGuesses = 6;

  var wordleWords = [
    'NGOAI', 'KHONG', 'THUOC', 'TRUOC', 'DUONG', 'THANG', 'NGANH', 'NGUOI',
    'CHUAN', 'PHUOC', 'TRANG', 'CHANH', 'THICH', 'KHACH', 'NHANH', 'THANH',
    'HOANG', 'QUANG', 'THUAN', 'NHUNG', 'TRONG', 'PHONG', 'CHONG', 'THONG',
    'VUONG', 'CUONG', 'GIANG', 'XUONG', 'TUONG', 'LUONG', 'MUONG', 'NUONG',
    'RUONG', 'SUONG', 'THIEN', 'QUYEN', 'TUYEN', 'NGHIA', 'NHIEU', 'CHIEU',
    'THIEU', 'TRIEU'
  ];

  function trimPrefix(s, p) {
    return s.indexOf(p) === 0 ? s.slice(p.length) : s;
  }

  function utf8Len(s) {
    return new TextEncoder().encode(s).length;
  }

  // wordleRank maps a feedback letter to its priority for keyboard hints.
  function wordleRank(s) {
    switch (s) {
      case 'G': return 3;
      case 'Y': return 2;
      case 'B': return 1;
      default: return 0;
    }
  }

  // Classic two-pass Wordle scoring so duplicate letters are handled correctly.
  function wordleComputeFeedback(secret, guess) {
    var n = secret.length;
    var fb = new Array(n);
    var remaining = {};
    var i;
    for (i = 0; i < n; i++) {
      if (guess[i] === secret[i]) {
        fb[i] = 'G';
      } else {
        remaining[secret[i]] = (remaining[secret[i]] || 0) + 1;
      }
    }
    for (i = 0; i < n; i++) {
      if (fb[i] === 'G') continue;
      var c = guess[i];
      if ((remaining[c] || 0) > 0) {
        fb[i] = 'Y';
        remaining[c]--;
      } else {
        fb[i] = 'B';
      }
    }
    return fb.join('');
  }

  class WordleGame extends WG.MiniCommon {
    constructor() {
      super();
      this.secret = wordleWords[WG.randInt(wordleWords.length)];
      this.guesses = [];
      this.feedback = [];
      this.keyboard = {};
      this.status = 'playing';
      this.winner = '';
      this.lastMove = '';
      this.history = [];
      this.humanTurn = true;
      this.botThinking = false;
      this.thinkGen = 0;
      this.moves = 0;
      this.difficulty = WG.diffMedium;
      this.message = WG.speakf(
        'Ván Wordle mới. Đoán từ %d chữ cái tiếng Việt không dấu. Bạn có %d lượt.',
        'New Wordle. Guess the %d-letter Vietnamese word (no diacritics). You have %d guesses.',
        wordleWordLen, wordleMaxGuesses
      )[0];
    }

    snapshot() {
      var extra = {
        guesses: this.guesses.slice(),
        feedback: this.feedback.slice(),
        maxGuesses: wordleMaxGuesses,
        wordLen: wordleWordLen,
        keyboard: this.keyboard
      };
      if (this.status !== 'playing') {
        extra.secret = this.secret;
      }
      return this.baseSnap('wordle', 'wordle', extra);
    }

    summaryText() {
      var b = '';
      b += WG.sprintf('Wordle tiếng Việt không dấu, từ %d chữ cái, tối đa %d lượt. ', wordleWordLen, wordleMaxGuesses);
      b += WG.sprintf('Trạng thái: %s. Đã đoán %d lượt. ', this.status, this.guesses.length);
      for (var i = 0; i < this.guesses.length; i++) {
        b += this.guesses[i] + '(' + this.feedback[i] + ') ';
      }
      if (this.status !== 'playing') {
        b += 'Từ đúng: ' + this.secret + '.';
      }
      return b;
    }

    reset() {
      var prevDiff = this.difficulty;
      var ng = new WordleGame();
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
      return ['<5 chữ cái A-Z>'];
    }

    playUCI(uci) {
      if (this.status !== 'playing') {
        throw new Error('game over');
      }
      var guess = String(uci).trim().toUpperCase();
      guess = trimPrefix(guess, 'GUESS:');
      guess = guess.trim();
      if (utf8Len(guess) !== wordleWordLen) {
        throw new Error(WG.sprintf('cần đoán đúng %d chữ cái', wordleWordLen));
      }
      for (var i = 0; i < guess.length; i++) {
        if (guess[i] < 'A' || guess[i] > 'Z') {
          throw new Error('chỉ dùng chữ cái A-Z, không dấu');
        }
      }

      var fb = wordleComputeFeedback(this.secret, guess);
      this.guesses.push(guess);
      this.feedback.push(fb);
      this.moves++;
      this.lastMove = guess;
      this.history.push(guess + ':' + fb);
      for (var j = 0; j < guess.length; j++) {
        var ch = guess[j];
        var st = fb[j];
        if (wordleRank(st) > wordleRank(this.keyboard[ch])) {
          this.keyboard[ch] = st;
        }
      }

      var won = guess === this.secret;
      if (won) {
        this.status = 'win';
        this.winner = 'human';
        this.message = WG.viOrEN('Chính xác! Bạn thắng.', 'Correct! You win.')[0];
      } else if (this.guesses.length >= wordleMaxGuesses) {
        this.status = 'lose';
        this.winner = 'bot';
        this.message = WG.speakf('Hết lượt. Từ đúng là %s.', 'Out of guesses. The word was %s.', this.secret)[0];
      } else {
        var remain = wordleMaxGuesses - this.guesses.length;
        this.message = WG.speakf('Còn %d lượt đoán.', '%d guesses left.', remain)[0];
      }

      var resp = this.snapshot();
      var msg = this.message;
      WG.queueGameSpeak('wordle', msg, 'Wordle: ' + guess + ' -> ' + fb);
      return resp;
    }
  }

  var inst = null;
  function getWordle() {
    if (!inst) inst = new WordleGame();
    return inst;
  }

  WG.registerBoardGame({
    name: 'Wordle',
    exitDefault: 'wordle',
    summary: function () { return getWordle().summaryText(); },
    snapshot: function () { return getWordle().snapshot(); },
    reset: function () { return getWordle().reset(); },
    setDifficulty: function (s) { return getWordle().setDifficulty(s); },
    getDifficulty: function () { return getWordle().getDifficulty(); },
    playUCI: function (u) { return getWordle().playUCI(u); },
    legalUCIs: function () { return getWordle().legalUCIs(); },
    newGameSpeak: function () {
      var say = WG.speakf(
        'Ván Wordle mới. Đoán từ tiếng Việt %d chữ cái, không dấu. Bạn có %d lượt.',
        'New Wordle. Guess the %d-letter Vietnamese word, no diacritics. You have %d guesses.',
        wordleWordLen, wordleMaxGuesses
      )[0];
      return [say, 'Wordle. Ván mới.'];
    }
  });
})(window.WG);
