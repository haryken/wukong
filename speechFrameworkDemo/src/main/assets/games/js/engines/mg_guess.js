// Port of mods/mg_guess.go — GuessNum: Đoán số 1..100, bot chọn số bí mật, tối đa 10 lượt.
(function (WG) {
  'use strict';

  var guessMin = 1;
  var guessMax = 100;
  var guessMaxAttempt = 10;

  function trimPrefix(s, p) {
    return s.indexOf(p) === 0 ? s.slice(p.length) : s;
  }

  // strconv.Atoi: optional sign followed by ASCII digits only.
  function atoi(s) {
    if (!/^[+-]?[0-9]+$/.test(s)) return null;
    return parseInt(s, 10);
  }

  function guessDiffLabel(diff) {
    switch (WG.normalizeGameDifficulty(diff)) {
      case WG.diffEasy: return ['Dễ', 'Easy'];
      case WG.diffHard: return ['Khó', 'Hard'];
      default: return ['Trung bình', 'Medium'];
    }
  }

  function guessPick(linesVI, linesEN) {
    if (linesVI.length === 0) return ['', ''];
    var i = WG.randInt(linesVI.length);
    var vi = linesVI[i];
    var en = i < linesEN.length ? linesEN[i] : linesVI[i];
    return [vi, en];
  }

  class GuessGame extends WG.MiniCommon {
    constructor() {
      super();
      this.secret = guessMin + WG.randInt(guessMax - guessMin + 1);
      this.low = guessMin;
      this.high = guessMax;
      this.attempts = 0;
      this.startedAt = Date.now();
      this.lastGuess = 0;
      this.lastHint = '';
      this.lastClose = false;
      this.hist = [];
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
        'Tôi đã nghĩ ra một số từ %d đến %d. Hãy đoán xem!',
        'I picked a number from %d to %d. Guess it!',
        guessMin, guessMax
      )[0];
    }

    commentary(n, higher, exact, outOfTries) {
      var dist = Math.abs(n - this.secret);
      var closeHit = dist > 0 && dist <= 5;
      var p;

      if (exact) {
        p = guessPick([
          'Xuất sắc! Bạn đã tìm ra con số bí mật.',
          'Tôi biết bạn sẽ làm được!',
          'Chiến thắng rồi!',
          'Bạn thật sự rất giỏi.',
          'Làm thêm một ván nữa nhé!',
          'Đúng rồi! Phải công nhận bạn đoán hay.',
          'Tuyệt vời! Số bí mật đã lộ diện.'
        ], [
          'Brilliant! You found the secret number.',
          'I knew you could do it!',
          'Victory!',
          "You're really good at this.",
          "Let's play another round!",
          'Correct! That was a sharp guess.',
          'Awesome! The secret is out.'
        ]);
        var flavor = WG.localizeSpeak(p[1], p[0]);
        return WG.speakf(
          '%s Số bí mật là %d — bạn thắng sau %d lượt.',
          '%s The number was %d — you won in %d guesses.',
          flavor, this.secret, this.attempts
        )[0];
      }

      if (outOfTries) {
        return WG.speakf(
          'Hết lượt rồi! Số bí mật là %d.',
          'Out of guesses! The number was %d.',
          this.secret
        )[0];
      }

      if (closeHit) {
        if (higher) {
          p = guessPick([
            'Rất gần! Cao hơn một chút nữa.',
            'Tôi có cảm giác bạn sắp đúng — thử số lớn hơn.',
            'Chỉ lệch một chút! Hãy đoán cao hơn.'
          ], [
            'So close! Go a little higher.',
            "I feel you're almost there — try a bigger number.",
            'Just a bit off! Guess higher.'
          ]);
          return WG.viOrEN(p[0], p[1])[0];
        }
        p = guessPick([
          'Rất gần! Thấp hơn một chút nữa.',
          'Tôi có cảm giác bạn sắp đúng — thử số nhỏ hơn.',
          'Chỉ lệch một chút! Hãy đoán thấp hơn.'
        ], [
          'So close! Go a little lower.',
          "I feel you're almost there — try a smaller number.",
          'Just a bit off! Guess lower.'
        ]);
        return WG.viOrEN(p[0], p[1])[0];
      }

      if (higher) {
        if (dist >= 30) {
          p = guessPick([
            'Bạn còn cách khá xa. Thử một số lớn hơn nhé.',
            'Thấp quá rồi — cao hơn nữa!',
            'Còn xa lắm. Hãy đoán cao hơn.'
          ], [
            "You're still quite far. Try a bigger number.",
            'Way too low — go higher!',
            'Still far away. Guess higher.'
          ]);
          return WG.viOrEN(p[0], p[1])[0];
        }
        p = guessPick([
          'Cao hơn nữa.',
          'Thử một số lớn hơn nhé.',
          'Hơi thấp — tăng lên một chút.'
        ], [
          'Higher.',
          'Try a bigger number.',
          'A bit low — go up a little.'
        ]);
        return WG.viOrEN(p[0], p[1])[0];
      }

      if (dist >= 30) {
        p = guessPick([
          'Hơi quá rồi. Giảm xuống nhiều hơn.',
          'Cao quá — thử số nhỏ hơn nhé.',
          'Bạn còn cách khá xa về phía trên. Hạ xuống.'
        ], [
          'A bit too high. Come down more.',
          'Too high — try a smaller number.',
          'Still far on the high side. Go lower.'
        ]);
        return WG.viOrEN(p[0], p[1])[0];
      }
      p = guessPick([
        'Thấp hơn nữa.',
        'Giảm xuống một chút.',
        'Hơi cao — thử số nhỏ hơn.'
      ], [
        'Lower.',
        'Come down a little.',
        'A bit high — try smaller.'
      ]);
      return WG.viOrEN(p[0], p[1])[0];
    }

    score() {
      if (this.status !== 'win') return 0;
      // Fewer attempts = higher score (max 1000).
      var s = 1100 - this.attempts * 100;
      if (s < 100) s = 100;
      return s;
    }

    elapsedSec() {
      if (!this.startedAt) return 0;
      return Math.floor((Date.now() - this.startedAt) / 1000);
    }

    snapshot() {
      var hist = this.hist.map(function (h) {
        return { guess: h.guess, hint: h.hint, close: h.close };
      });
      var rem = guessMaxAttempt - this.attempts;
      if (rem < 0) rem = 0;
      var d = guessDiffLabel(this.difficulty);
      var diffLabel = WG.localizeSpeak(d[1], d[0]);
      var extra = {
        low: this.low,
        high: this.high,
        min: guessMin,
        max: guessMax,
        attempts: this.attempts,
        maxAttempts: guessMaxAttempt,
        remaining: rem,
        lastGuess: this.lastGuess,
        lastHint: this.lastHint,
        lastClose: this.lastClose,
        guessHistory: hist,
        difficultyLabel: diffLabel
      };
      if (this.status !== 'playing') {
        extra.secret = this.secret;
        extra.elapsedSec = this.elapsedSec();
        extra.score = this.score();
      }
      return this.baseSnap('guess', 'guess', extra);
    }

    summaryText() {
      return WG.sprintf('Đoán số 1-100. Khoảng còn lại: %d-%d. Đã đoán %d/%d lượt. Trạng thái: %s.',
        this.low, this.high, this.attempts, guessMaxAttempt, this.status);
    }

    reset() {
      var prevDiff = this.difficulty;
      var ng = new GuessGame();
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
      return [WG.sprintf('<%d-%d>', this.low, this.high)];
    }

    playUCI(uci) {
      if (this.status !== 'playing') {
        throw new Error('game over');
      }
      var s = String(uci).trim().toLowerCase();
      s = trimPrefix(s, 'n:');
      var n = atoi(s.trim());
      if (n === null || n < guessMin || n > guessMax) {
        throw new Error(WG.sprintf('cần một số từ %d đến %d', guessMin, guessMax));
      }

      this.attempts++;
      this.moves++;
      this.lastMove = String(n);
      this.lastGuess = n;
      this.history.push(String(n));

      var dist = Math.abs(n - this.secret);
      var closeHit = dist > 0 && dist <= 5;

      if (n === this.secret) {
        this.status = 'win';
        this.winner = 'human';
        this.lastHint = 'exact';
        this.lastClose = false;
        this.hist.push({ guess: n, hint: 'exact', close: false });
        this.message = this.commentary(n, false, true, false);
      } else if (n < this.secret) {
        if (n + 1 > this.low) this.low = n + 1;
        this.lastHint = 'higher';
        this.lastClose = closeHit;
        this.hist.push({ guess: n, hint: 'higher', close: closeHit });
        if (this.attempts >= guessMaxAttempt) {
          this.status = 'lose';
          this.winner = 'bot';
          this.lastHint = 'lose';
          this.message = this.commentary(n, true, false, true);
        } else {
          this.message = this.commentary(n, true, false, false);
        }
      } else {
        if (n - 1 < this.high) this.high = n - 1;
        this.lastHint = 'lower';
        this.lastClose = closeHit;
        this.hist.push({ guess: n, hint: 'lower', close: closeHit });
        if (this.attempts >= guessMaxAttempt) {
          this.status = 'lose';
          this.winner = 'bot';
          this.lastHint = 'lose';
          this.message = this.commentary(n, false, false, true);
        } else {
          this.message = this.commentary(n, false, false, false);
        }
      }

      var resp = this.snapshot();
      var msg = this.message;
      var status = this.status;

      var hint = WG.sprintf('GuessNum: đoán %d', n);
      if (status === 'win') {
        hint = 'Đoán số. Chúc mừng! Bạn thắng.';
      } else if (status === 'lose') {
        hint = 'Đoán số. Hết lượt.';
      }
      WG.queueGameSpeak('guessnum', msg, hint);
      return resp;
    }
  }

  var inst = null;
  function getGuessNum() {
    if (!inst) inst = new GuessGame();
    return inst;
  }

  WG.registerBoardGame({
    name: 'GuessNum',
    exitDefault: 'guessnum',
    summary: function () { return getGuessNum().summaryText(); },
    snapshot: function () { return getGuessNum().snapshot(); },
    reset: function () { return getGuessNum().reset(); },
    setDifficulty: function (s) { return getGuessNum().setDifficulty(s); },
    getDifficulty: function () { return getGuessNum().getDifficulty(); },
    playUCI: function (u) { return getGuessNum().playUCI(u); },
    legalUCIs: function () { return getGuessNum().legalUCIs(); },
    newGameSpeak: function () {
      var say = WG.speakf(
        'Ván đoán số mới. Tôi đã nghĩ ra một số từ %d đến %d. Bạn có %d lượt. Chúc may mắn!',
        'New guess-the-number round. I picked a number from %d to %d. You have %d guesses. Good luck!',
        guessMin, guessMax, guessMaxAttempt
      )[0];
      return [say, 'Đoán số. Ván mới.'];
    }
  });
})(window.WG);
