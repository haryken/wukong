// Port of mods/mg_trivia.go — Trivia tiếng Việt: 20 câu hỏi trắc nghiệm cố định,
// mỗi ván hỏi 5 câu (thứ tự xáo trộn ngẫu nhiên).
(function (WG) {
  'use strict';

  var triviaQuestionsPerRound = 5;

  function qa(question, choices, correct) {
    return { Question: question, Choices: choices, Correct: correct };
  }

  var triviaBank = [
    qa('Thủ đô của Việt Nam là gì?', ['Hà Nội', 'TP.HCM', 'Đà Nẵng', 'Huế'], 0),
    qa('Việt Nam hiện có bao nhiêu tỉnh thành?', ['63', '64', '61', '58'], 0),
    qa('Sông nào chảy qua Việt Nam dài nhất thế giới?', ['Sông Hồng', 'Sông Mê Kông', 'Sông Đồng Nai', 'Sông Mã'], 1),
    qa('Trái Đất quay quanh gì?', ['Mặt Trăng', 'Mặt Trời', 'Sao Hỏa', 'Sao Kim'], 1),
    qa('Nước nào có diện tích lớn nhất thế giới?', ['Trung Quốc', 'Canada', 'Nga', 'Mỹ'], 2),
    qa('1 + 1 bằng mấy?', ['1', '2', '3', '4'], 1),
    qa('Ai là tác giả của Truyện Kiều?', ['Nguyễn Du', 'Hồ Xuân Hương', 'Nguyễn Trãi', 'Tố Hữu'], 0),
    qa('Việt Nam giành độc lập năm nào?', ['1945', '1954', '1975', '1930'], 0),
    qa('Hành tinh nào gần Mặt Trời nhất?', ['Trái Đất', 'Sao Thủy', 'Sao Kim', 'Sao Hỏa'], 1),
    qa('Nước nào đông dân nhất thế giới hiện nay?', ['Trung Quốc', 'Ấn Độ', 'Mỹ', 'Indonesia'], 1),
    qa('Con vật nào được gọi là chúa sơn lâm?', ['Voi', 'Hổ', 'Sư tử', 'Gấu'], 1),
    qa('Vịnh Hạ Long thuộc tỉnh nào?', ['Quảng Ninh', 'Hải Phòng', 'Nam Định', 'Thanh Hóa'], 0),
    qa('1 giờ có bao nhiêu phút?', ['30', '45', '60', '90'], 2),
    qa('Đâu là một ngôn ngữ lập trình?', ['Python', 'Excel', 'Word', 'Chrome'], 0),
    qa('Núi cao nhất Việt Nam là núi nào?', ['Fansipan', 'Bà Đen', 'Langbiang', 'Yên Tử'], 0),
    qa('Chủ tịch Hồ Chí Minh còn được gọi là gì?', ['Bác Hồ', 'Bác Ba', 'Ông Sáu', 'Anh Hai'], 0),
    qa('Màu của lá cây thường là gì?', ['Đỏ', 'Xanh lá', 'Vàng', 'Tím'], 1),
    qa('Đâu là thủ đô nước Pháp?', ['Paris', 'London', 'Berlin', 'Rome'], 0),
    qa('Nước chiếm khoảng bao nhiêu % bề mặt Trái Đất?', ['50%', '71%', '90%', '30%'], 1),
    qa('Bộ phận nào dùng để nghe?', ['Mắt', 'Mũi', 'Tai', 'Miệng'], 2)
  ];

  function trimPrefix(s, p) {
    return s.indexOf(p) === 0 ? s.slice(p.length) : s;
  }

  // strconv.Atoi: optional sign followed by ASCII digits only.
  function atoi(s) {
    if (!/^[+-]?[0-9]+$/.test(s)) return null;
    return parseInt(s, 10);
  }

  function randPerm(n) {
    var a = [];
    for (var i = 0; i < n; i++) a.push(i);
    var r = WG.shuffle(a);
    return Array.isArray(r) ? r : a;
  }

  class TriviaGame extends WG.MiniCommon {
    constructor() {
      super();
      var perm = randPerm(triviaBank.length);
      var n = triviaQuestionsPerRound;
      if (n > perm.length) n = perm.length;
      this.order = perm.slice(0, n);
      this.qIdx = 0;
      this.score = 0;
      this.total = n;
      this.status = 'playing';
      this.winner = '';
      this.lastMove = '';
      this.history = [];
      this.humanTurn = true;
      this.botThinking = false;
      this.thinkGen = 0;
      this.moves = 0;
      this.difficulty = WG.diffMedium;
      this.message = WG.speakf('Ván đố vui mới, %d câu hỏi. Trả lời bằng 0-3.', 'New trivia round, %d questions. Answer with 0-3.', n)[0];
    }

    current() {
      if (this.qIdx < 0 || this.qIdx >= this.order.length) return null;
      return triviaBank[this.order[this.qIdx]];
    }

    snapshot() {
      var extra = {
        qIndex: this.qIdx,
        score: this.score,
        total: this.total
      };
      var q = this.current();
      if (q) {
        extra.question = q.Question;
        extra.choices = [q.Choices[0], q.Choices[1], q.Choices[2], q.Choices[3]];
      } else {
        extra.question = '';
        extra.choices = [];
      }
      return this.baseSnap('trivia', 'trivia', extra);
    }

    summaryText() {
      var q = this.current();
      var qtext = '(hết câu hỏi)';
      if (q) qtext = q.Question;
      return WG.sprintf('Đố vui tiếng Việt. Câu %d/%d. Điểm: %d. Câu hiện tại: %s', this.qIdx + 1, this.total, this.score, qtext);
    }

    reset() {
      var prevDiff = this.difficulty;
      var ng = new TriviaGame();
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
      if (this.status !== 'playing' || !this.current()) return null;
      return ['0', '1', '2', '3'];
    }

    playUCI(uci) {
      if (this.status !== 'playing') {
        throw new Error('game over');
      }
      var q = this.current();
      if (!q) {
        throw new Error('đã hết câu hỏi');
      }
      var ans = String(uci).trim().toLowerCase();
      ans = trimPrefix(ans, 'ans:');
      var n = atoi(ans.trim());
      if (n === null || n < 0 || n > 3) {
        throw new Error('đáp án phải là 0, 1, 2 hoặc 3');
      }

      var correct = n === q.Correct;
      this.moves++;
      this.lastMove = String(n);
      this.history.push(WG.sprintf('Q%d:%d', this.qIdx + 1, n));

      var msg;
      if (correct) {
        this.score++;
        msg = WG.viOrEN('Chính xác!', 'Correct!')[0];
      } else {
        msg = WG.speakf('Sai rồi! Đáp án đúng là: %s.', 'Wrong! The correct answer was: %s.', q.Choices[q.Correct])[0];
      }
      this.qIdx++;

      if (this.qIdx >= this.total) {
        this.status = 'win';
        if (this.score * 2 < this.total) this.status = 'lose';
        if (this.score * 2 === this.total) this.status = 'draw';
        this.winner = '';
        if (this.status === 'win') {
          this.winner = 'human';
        } else if (this.status === 'lose') {
          this.winner = 'bot';
        }
        var finalMsg = WG.speakf('Xong! Bạn được %d/%d điểm.', 'Done! You scored %d/%d.', this.score, this.total)[0];
        msg = msg + ' ' + finalMsg;
      }
      this.message = msg;

      var resp = this.snapshot();
      WG.queueGameSpeak('trivia', msg, 'Trivia: ' + q.Question);
      return resp;
    }
  }

  var inst = null;
  function getTrivia() {
    if (!inst) inst = new TriviaGame();
    return inst;
  }

  WG.registerBoardGame({
    name: 'Trivia',
    exitDefault: 'trivia',
    summary: function () { return getTrivia().summaryText(); },
    snapshot: function () { return getTrivia().snapshot(); },
    reset: function () { return getTrivia().reset(); },
    setDifficulty: function (s) { return getTrivia().setDifficulty(s); },
    getDifficulty: function () { return getTrivia().getDifficulty(); },
    playUCI: function (u) { return getTrivia().playUCI(u); },
    legalUCIs: function () { return getTrivia().legalUCIs(); },
    newGameSpeak: function () {
      var say = WG.speakf('Ván đố vui mới, %d câu hỏi. Trả lời bằng 0, 1, 2 hoặc 3.',
        'New trivia round, %d questions. Answer with 0, 1, 2 or 3.', triviaQuestionsPerRound)[0];
      return [say, 'Đố vui. Ván mới.'];
    }
  });
})(window.WG);
