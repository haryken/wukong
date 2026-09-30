/*
 * wg_core.js — browser port of the wired Go game core:
 *   game_speak_i18n.go, board_game_speak.go, chess_speak.go, xiangqi_speak.go,
 *   minigame_util.go, game_difficulty.go, board_game_http.go (+ chess.go / xiangqi.go HTTP).
 *
 * Plain script (no modules). Defines window.WG and wraps window.fetch so that
 * /api/mods/<Name>/<path> is answered locally by engines registered with
 * WG.registerBoardGame(). Robot speech goes to POST /api/game_speak.
 */
(function (global) {
  'use strict';

  var WG = global.WG || {};
  global.WG = WG;

  var _origFetch = (typeof global.fetch === 'function') ? global.fetch.bind(global) : null;

  // ---------------------------------------------------------------------------
  // Small helpers (Go stdlib stand-ins)
  // ---------------------------------------------------------------------------

  function hasOwn(o, k) {
    return o != null && Object.prototype.hasOwnProperty.call(o, k);
  }

  function trimSpace(s) {
    return String(s == null ? '' : s).trim();
  }

  function sleep(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, Math.max(0, Number(ms) || 0)); });
  }

  function randInt(n) {
    n = Math.floor(Number(n));
    if (!(n > 0)) {
      throw new Error('invalid argument to Intn');
    }
    return Math.floor(Math.random() * n);
  }

  function randFloat() {
    return Math.random();
  }

  function shuffle(arr) {
    if (!arr) return arr;
    for (var i = arr.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = arr[i];
      arr[i] = arr[j];
      arr[j] = t;
    }
    return arr;
  }

  // --- Go fmt subset ---------------------------------------------------------

  function goTypeName(v) {
    if (v === null || v === undefined) return '<nil>';
    if (typeof v === 'string') return 'string';
    if (typeof v === 'boolean') return 'bool';
    if (typeof v === 'number') return Number.isInteger(v) ? 'int' : 'float64';
    if (typeof v === 'bigint') return 'int64';
    if (Array.isArray(v)) return '[]interface {}';
    if (v instanceof Error) return '*errors.errorString';
    return 'map[string]interface {}';
  }

  function fixExp(s) {
    // JS "1e-7" / "1.5e+21" -> Go "1e-07" / "1.5e+21"
    return s.replace(/e([+-])(\d)$/, 'e$10$2');
  }

  function fmtFloatV(n) {
    if (Number.isNaN(n)) return 'NaN';
    if (n === Infinity) return '+Inf';
    if (n === -Infinity) return '-Inf';
    if (n === 0) return (1 / n < 0) ? '-0' : '0';
    var a = Math.abs(n);
    if (a < 1e-4 || a >= 1e21) {
      return fixExp(n.toExponential());
    }
    return String(n);
  }

  function fmtV(v) {
    if (v === null || v === undefined) return '<nil>';
    if (typeof v === 'string') return v;
    if (typeof v === 'boolean') return v ? 'true' : 'false';
    if (typeof v === 'number') return fmtFloatV(v);
    if (typeof v === 'bigint') return v.toString();
    if (Array.isArray(v)) return '[' + v.map(fmtV).join(' ') + ']';
    if (v instanceof Error) return v.message;
    if (typeof v === 'object') {
      if (typeof v.toString === 'function' && v.toString !== Object.prototype.toString) {
        return String(v);
      }
      var keys = Object.keys(v).sort();
      return 'map[' + keys.map(function (k) { return k + ':' + fmtV(v[k]); }).join(' ') + ']';
    }
    return String(v);
  }

  function goQuote(s) {
    var out = '"';
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      var ch = s[i];
      if (ch === '"') out += '\\"';
      else if (ch === '\\') out += '\\\\';
      else if (ch === '\n') out += '\\n';
      else if (ch === '\r') out += '\\r';
      else if (ch === '\t') out += '\\t';
      else if (c === 7) out += '\\a';
      else if (c === 8) out += '\\b';
      else if (c === 12) out += '\\f';
      else if (c === 11) out += '\\v';
      else if (c < 0x20 || c === 0x7f) out += '\\x' + ('0' + c.toString(16)).slice(-2);
      else out += ch;
    }
    return out + '"';
  }

  function utf8Bytes(s) {
    var bytes = [];
    var enc = (typeof TextEncoder !== 'undefined') ? new TextEncoder() : null;
    if (enc) {
      var u = enc.encode(s);
      for (var i = 0; i < u.length; i++) bytes.push(u[i]);
      return bytes;
    }
    var e = unescape(encodeURIComponent(s));
    for (var j = 0; j < e.length; j++) bytes.push(e.charCodeAt(j));
    return bytes;
  }

  function runeLen(s) {
    return Array.from(s).length;
  }

  function intArg(v) {
    if (typeof v === 'bigint') return v;
    var n = Number(v);
    if (!Number.isFinite(n)) return NaN;
    return Math.trunc(n);
  }

  function signed(numStr, isNeg, f) {
    if (isNeg) return '-' + numStr;
    if (f.plus) return '+' + numStr;
    if (f.space) return ' ' + numStr;
    return numStr;
  }

  function fmtInt(v, f, base, upper) {
    var n = intArg(v);
    if (typeof n !== 'bigint' && Number.isNaN(n)) return null;
    var neg = typeof n === 'bigint' ? n < 0n : n < 0;
    var abs = neg ? -n : n;
    var digits = abs.toString(base);
    if (upper) digits = digits.toUpperCase();
    if (f.prec !== null) {
      if (f.prec === 0 && digits === '0') digits = '';
      while (digits.length < f.prec) digits = '0' + digits;
    }
    if (f.sharp) {
      if (base === 16) digits = (upper ? '0X' : '0x') + digits;
      else if (base === 8 && digits[0] !== '0') digits = '0' + digits;
      else if (base === 2) digits = '0b' + digits;
    }
    return signed(digits, neg, f);
  }

  // Go rounds exact decimal ties to even (2.25 -> "2.2"); JS toFixed rounds them up.
  function toFixedHalfEven(a, prec) {
    var s = a.toFixed(prec);
    if (a >= 1e21 || prec + 30 > 100) return s;
    var exact = a.toFixed(prec + 30);
    var tail = exact.slice(exact.length - 30);
    if (!/^50*$/.test(tail)) return s;
    var trunc = exact.slice(0, exact.length - 30);
    if (trunc.charAt(trunc.length - 1) === '.') trunc = trunc.slice(0, -1);
    var lastDigit = trunc.replace('.', '').slice(-1);
    return (parseInt(lastDigit, 10) % 2 === 0) ? trunc : s;
  }

  function fmtFloat(v, verb, f) {
    var n = Number(v);
    if (Number.isNaN(n)) return signed('NaN', false, f);
    if (!Number.isFinite(n)) return n > 0 ? (f.plus ? '+Inf' : (f.space ? ' Inf' : '+Inf')) : '-Inf';
    var neg = n < 0 || (n === 0 && 1 / n < 0);
    var a = Math.abs(n);
    var s;
    var prec = f.prec;
    if (verb === 'f' || verb === 'F') {
      s = toFixedHalfEven(a, prec === null ? 6 : prec);
    } else if (verb === 'e' || verb === 'E') {
      s = fixExp(a.toExponential(prec === null ? 6 : prec));
      if (verb === 'E') s = s.toUpperCase();
    } else {
      // g / G
      if (prec === null) {
        s = fmtFloatV(a);
      } else {
        var p = prec === 0 ? 1 : prec;
        var exp = a === 0 ? 0 : Math.floor(Math.log10(a));
        if (exp < -4 || exp >= p) {
          s = fixExp(a.toExponential(p - 1).replace(/\.?0+e/, 'e'));
        } else {
          s = a.toPrecision(p);
          if (s.indexOf('.') >= 0 && !f.sharp) s = s.replace(/\.?0+$/, '');
        }
      }
      if (verb === 'G') s = s.toUpperCase();
    }
    return signed(s, neg, f);
  }

  function badVerb(verb, arg) {
    return '%!' + verb + '(' + goTypeName(arg) + '=' + fmtV(arg) + ')';
  }

  function formatVerb(verb, arg, f) {
    if (Array.isArray(arg) && verb !== 'v' && verb !== 's' && verb !== 'q' && verb !== 'T') {
      return '[' + arg.map(function (x) { return formatVerb(verb, x, f); }).join(' ') + ']';
    }
    var s;
    switch (verb) {
      case 'v':
        if (f.sharp && typeof arg === 'string') return goQuote(arg);
        if (typeof arg === 'number' && Number.isInteger(arg)) return signed(String(Math.abs(arg)), arg < 0, f);
        return fmtV(arg);
      case 's':
        s = fmtV(arg);
        if (f.prec !== null) s = Array.from(s).slice(0, f.prec).join('');
        return s;
      case 'q':
        if (typeof arg === 'number') return "'" + String.fromCodePoint(Math.trunc(arg)) + "'";
        s = fmtV(arg);
        if (f.prec !== null) s = Array.from(s).slice(0, f.prec).join('');
        return f.sharp && s.indexOf('`') < 0 ? '`' + s + '`' : goQuote(s);
      case 'd':
        if (typeof arg === 'boolean' || typeof arg === 'string' || arg == null) return badVerb(verb, arg);
        s = fmtInt(arg, f, 10, false);
        return s === null ? badVerb(verb, arg) : s;
      case 'b':
      case 'o':
      case 'O':
        if (typeof arg === 'boolean' || typeof arg === 'string' || arg == null) return badVerb(verb, arg);
        s = fmtInt(arg, verb === 'O' ? Object.assign({}, f, { sharp: false }) : f, verb === 'b' ? 2 : 8, false);
        if (s !== null && verb === 'O') s = s.replace(/^([+ -]?)/, '$10o');
        return s === null ? badVerb(verb, arg) : s;
      case 'x':
      case 'X':
        if (typeof arg === 'string') {
          var hex = utf8Bytes(arg).map(function (b) { return ('0' + b.toString(16)).slice(-2); }).join('');
          if (verb === 'X') hex = hex.toUpperCase();
          if (f.sharp) hex = (verb === 'X' ? '0X' : '0x') + hex;
          return hex;
        }
        if (typeof arg === 'number' && !Number.isInteger(arg)) return badVerb(verb, arg);
        if (typeof arg === 'boolean' || arg == null) return badVerb(verb, arg);
        s = fmtInt(arg, f, 16, verb === 'X');
        return s === null ? badVerb(verb, arg) : s;
      case 'c':
        if (typeof arg !== 'number' && typeof arg !== 'bigint') return badVerb(verb, arg);
        try { return String.fromCodePoint(Number(arg)); } catch (e) { return '\uFFFD'; }
      case 'U':
        if (typeof arg !== 'number') return badVerb(verb, arg);
        s = Math.trunc(arg).toString(16).toUpperCase();
        while (s.length < 4) s = '0' + s;
        return 'U+' + s;
      case 'f':
      case 'F':
      case 'e':
      case 'E':
      case 'g':
      case 'G':
        if (typeof arg !== 'number') return badVerb(verb, arg);
        return fmtFloat(arg, verb, f);
      case 't':
        if (typeof arg !== 'boolean') return badVerb(verb, arg);
        return arg ? 'true' : 'false';
      case 'T':
        return goTypeName(arg);
      default:
        return badVerb(verb, arg);
    }
  }

  function padField(s, width, f, verb) {
    if (width === null) return s;
    var len = runeLen(s);
    if (len >= width) return s;
    var fill = width - len;
    if (f.minus) return s + ' '.repeat(fill);
    var numeric = 'dbboOxXeEfFgG'.indexOf(verb) >= 0 || (verb === 'v' && typeof f.arg === 'number');
    if (f.zero && !(numeric && f.prec !== null && 'dboOxX'.indexOf(verb) >= 0)) {
      if (numeric && (s[0] === '-' || s[0] === '+' || s[0] === ' ')) {
        return s[0] + '0'.repeat(fill) + s.slice(1);
      }
      return '0'.repeat(fill) + s;
    }
    return ' '.repeat(fill) + s;
  }

  function sprintf(fmt) {
    var args = Array.prototype.slice.call(arguments, 1);
    fmt = String(fmt == null ? '' : fmt);
    var out = '';
    var ai = 0;
    var i = 0;
    var n = fmt.length;
    while (i < n) {
      var c = fmt[i];
      if (c !== '%') {
        out += c;
        i++;
        continue;
      }
      i++;
      if (i >= n) {
        out += '%!(NOVERB)';
        break;
      }
      var f = { minus: false, plus: false, zero: false, space: false, sharp: false, prec: null, arg: undefined };
      for (; i < n; i++) {
        var fc = fmt[i];
        if (fc === '-') f.minus = true;
        else if (fc === '+') f.plus = true;
        else if (fc === '0') f.zero = true;
        else if (fc === ' ') f.space = true;
        else if (fc === '#') f.sharp = true;
        else break;
      }
      var width = null;
      if (fmt[i] === '*') {
        i++;
        width = Math.trunc(Number(args[ai++]) || 0);
        if (width < 0) { f.minus = true; width = -width; }
      } else {
        var w = '';
        while (i < n && fmt[i] >= '0' && fmt[i] <= '9') w += fmt[i++];
        if (w) width = parseInt(w, 10);
      }
      if (fmt[i] === '.') {
        i++;
        if (fmt[i] === '*') {
          i++;
          f.prec = Math.trunc(Number(args[ai++]) || 0);
          if (f.prec < 0) f.prec = null;
        } else {
          var p = '';
          while (i < n && fmt[i] >= '0' && fmt[i] <= '9') p += fmt[i++];
          f.prec = p ? parseInt(p, 10) : 0;
        }
      }
      if (i >= n) {
        out += '%!(NOVERB)';
        break;
      }
      var verb = fmt[i++];
      if (verb === '%') {
        out += '%';
        continue;
      }
      if (ai >= args.length) {
        out += '%!' + verb + '(MISSING)';
        continue;
      }
      var arg = args[ai++];
      f.arg = arg;
      out += padField(formatVerb(verb, arg, f), width, f, verb);
    }
    if (ai < args.length) {
      out += '%!(EXTRA ' + args.slice(ai).map(function (a) {
        return goTypeName(a) + '=' + fmtV(a);
      }).join(', ') + ')';
    }
    return out;
  }

  // toStr is connect4_engine.go toStr: nil -> "", string -> itself, else fmt.Sprint.
  function toStr(v) {
    if (v === null || v === undefined) return '';
    if (typeof v === 'string') return v;
    return fmtV(v);
  }

  // ---------------------------------------------------------------------------
  // game_difficulty.go
  // ---------------------------------------------------------------------------

  var diffEasy = 'easy';
  var diffMedium = 'medium';
  var diffHard = 'hard';

  function normalizeGameDifficulty(d) {
    switch (trimSpace(d).toLowerCase()) {
      case 'easy': case 'de': case 'dễ': case 'e': case '1':
        return diffEasy;
      case 'hard': case 'kho': case 'khó': case 'h': case '3': case 'difficult':
        return diffHard;
      default:
        return diffMedium;
    }
  }

  function difficultyLabelVI(d) {
    switch (normalizeGameDifficulty(d)) {
      case diffEasy:
        return 'Dễ';
      case diffHard:
        return 'Khó';
      default:
        return 'Trung bình';
    }
  }

  // ---------------------------------------------------------------------------
  // Settings (replaces the Go xiaozhi config file + atomics)
  // ---------------------------------------------------------------------------

  var chessAnnouncePath = '/run/vic-cloud/chess-announce';
  var chessModeSayText = 'saytext';
  var chessModeXiaozhi = 'xiaozhi';
  var chessModeGoogleVI = 'google_vi';

  var LS_PREFIX = 'wg.';
  var memStore = {};

  function lsGet(key, def) {
    try {
      var v = global.localStorage.getItem(LS_PREFIX + key);
      if (v !== null && v !== undefined) return v;
    } catch (e) { /* storage unavailable */ }
    return hasOwn(memStore, key) ? memStore[key] : def;
  }

  function lsSet(key, val) {
    memStore[key] = String(val);
    try {
      global.localStorage.setItem(LS_PREFIX + key, String(val));
    } catch (e) { /* storage unavailable */ }
  }

  function normalizeGoogleTTSLang(lang) {
    lang = trimSpace(lang).toLowerCase();
    switch (lang) {
      case '': case 'vi': case 'vi-vn':
        return 'vi';
      case 'zh': case 'zh-cn': case 'cn': case 'chinese':
        return 'zh-CN';
      case 'en': case 'en-us': case 'en-gb':
        return 'en';
      case 'it': case 'it-it':
        return 'it';
      case 'ru': case 'ru-ru':
        return 'ru';
      case 'fr': case 'fr-fr':
        return 'fr';
      case 'de': case 'de-de':
        return 'de';
      case 'es': case 'es-es':
        return 'es';
      case 'pt': case 'pt-br': case 'pt-pt':
        return 'pt';
      default:
        return 'vi';
    }
  }

  function modeToInt(m) {
    m = trimSpace(m).toLowerCase();
    if (m === chessModeGoogleVI) return 2;
    // xiaozhi is never available in the browser port -> saytext.
    return 0;
  }

  // 1 = on, 0 = off
  var chessCommentOn = lsGet('commentOn', '1') === '0' ? 0 : 1;
  // 0 = saytext, 1 = xiaozhi, 2 = google_vi
  var chessCommentMode = modeToInt(lsGet('commentMode', chessModeGoogleVI));
  var googleTTSLangSetting = normalizeGoogleTTSLang(lsGet('googleTTSLang', 'vi'));

  function chessCommentEnabled() {
    return chessCommentOn === 1;
  }

  function setChessCommentEnabled(on) {
    chessCommentOn = on ? 1 : 0;
    lsSet('commentOn', chessCommentOn);
  }

  function chessXiaozhiAvailable() {
    return false;
  }

  function chessGoogleVIAvailable() {
    // Always on — language is chosen in the Games lobby dropdown.
    return true;
  }

  // chessGoogleTTSLang is the Google Translate TTS language code (default vi).
  function chessGoogleTTSLang() {
    return normalizeGoogleTTSLang(googleTTSLangSetting);
  }

  function setChessGoogleTTSLang(lang) {
    lang = normalizeGoogleTTSLang(lang);
    googleTTSLangSetting = lang;
    lsSet('googleTTSLang', lang);
    return lang;
  }

  // chessPreferVIText is true only for Google TTS + Vietnamese.
  function chessPreferVIText() {
    return gameSpeakLang() === 'vi';
  }

  function getChessCommentMode() {
    switch (chessCommentMode) {
      case 1:
        if (chessXiaozhiAvailable()) {
          return chessModeXiaozhi;
        }
        break;
      case 2:
        if (chessGoogleVIAvailable()) {
          return chessModeGoogleVI;
        }
        break;
    }
    return chessModeSayText;
  }

  function storeMode(n) {
    chessCommentMode = n;
    lsSet('commentMode', n === 2 ? chessModeGoogleVI : (n === 1 ? chessModeXiaozhi : chessModeSayText));
  }

  function setChessCommentMode(mode) {
    mode = trimSpace(mode).toLowerCase();
    switch (mode) {
      case chessModeXiaozhi: case 'conversation': case 'ws':
        if (chessXiaozhiAvailable()) {
          storeMode(1);
          return chessModeXiaozhi;
        }
        storeMode(0);
        return chessModeSayText;
      case chessModeGoogleVI: case 'google': case 'google-vi':
        if (chessGoogleVIAvailable()) {
          storeMode(2);
          return chessModeGoogleVI;
        }
        storeMode(0);
        return chessModeSayText;
      default:
        storeMode(0);
        return chessModeSayText;
    }
  }

  // ---------------------------------------------------------------------------
  // game_speak_i18n.go
  // ---------------------------------------------------------------------------

  // gameSpeakLang is the language of spoken game comments.
  // Google TTS: match the Games lobby voice (vi, zh-CN, it, …).
  // SayText: English.
  function gameSpeakLang() {
    var override = trimSpace(WG.chessSpeakLangOverride);
    if (override !== '') {
      return normalizeGoogleTTSLang(override);
    }
    if (getChessCommentMode() === chessModeGoogleVI) {
      return chessGoogleTTSLang();
    }
    return 'en';
  }

  // L is a phrase table keyed by Google TTS language code.
  function L(m) {
    if (!(this instanceof L)) return new L(m);
    if (m) {
      for (var k in m) {
        if (hasOwn(m, k)) this[k] = m[k];
      }
    }
  }
  L.prototype.get = function () {
    var lang = gameSpeakLang();
    var s = hasOwn(this, lang) ? trimSpace(this[lang]) : '';
    if (s !== '') return s;
    s = hasOwn(this, 'en') ? trimSpace(this.en) : '';
    if (s !== '') return s;
    return hasOwn(this, 'vi') ? trimSpace(this.vi) : '';
  };
  L.prototype.sprintf = function () {
    var s = this.get();
    if (s === '' || arguments.length === 0) return s;
    return sprintf.apply(null, [s].concat(Array.prototype.slice.call(arguments)));
  };

  function lTable(obj) {
    var out = {};
    for (var k in obj) {
      if (hasOwn(obj, k)) out[k] = (obj[k] instanceof L) ? obj[k] : new L(obj[k]);
    }
    return out;
  }

  function phraseLookup(en, lang) {
    if (!hasOwn(phraseByEN, en)) return '';
    var m = phraseByEN[en];
    return hasOwn(m, lang) ? (m[lang] || '') : '';
  }

  // localizeSpeak picks vi / en / other-lang text for the current speak language.
  function localizeSpeak(en, vi) {
    var lang = gameSpeakLang();
    switch (lang) {
      case 'vi':
        return vi;
      case 'en':
        return en;
      default:
        var t = phraseLookup(en, lang);
        if (t !== '') return t;
        return en;
    }
  }

  // speakNew is localizeSpeak as a (say, summary) pair for new-game lines.
  function speakNew(en, vi) {
    var s = localizeSpeak(en, vi);
    return [s, s];
  }

  // speakf localizes a printf template (English format is the catalog key).
  function speakf(viFmt, enFmt) {
    var args = Array.prototype.slice.call(arguments, 2);
    var lang = gameSpeakLang();
    var tpl = enFmt;
    switch (lang) {
      case 'vi':
        tpl = viFmt;
        break;
      case 'en':
        tpl = enFmt;
        break;
      default:
        var t = phraseLookup(enFmt, lang);
        if (t !== '') tpl = t;
    }
    var s = sprintf.apply(null, [tpl].concat(args));
    return [s, s];
  }

  function wordL(table, key, fallback) {
    if (table == null) {
      return fallback;
    }
    var k1 = trimSpace(key).toLowerCase();
    if (hasOwn(table, k1)) {
      var s1 = table[k1].get();
      if (s1 !== '') return s1;
    }
    var k2 = trimSpace(key);
    if (hasOwn(table, k2)) {
      var s2 = table[k2].get();
      if (s2 !== '') return s2;
    }
    return fallback;
  }

  // phraseByEN maps English source (or English printf format) → other Google langs.
  // Vietnamese is taken from the viOrEN / speakNew first argument, not this table.
  var phraseByEN = {
    "New game. You are white. Your move.": {
      "zh-CN": "新对局。你执白棋。该你走了。",
      "it": "Nuova partita. Sei il bianco. Tocca a te.",
      "ru": "Новая партия. Вы играете белыми. Ваш ход.",
      "fr": "Nouvelle partie. Vous êtes les blancs. À vous.",
      "de": "Neue Partie. Du spielst Weiß. Du bist am Zug.",
      "es": "Nueva partida. Eres blancas. Te toca.",
      "pt": "Novo jogo. Você é as brancas. Sua vez."
    },
    "New game. You are red. Your move.": {
      "zh-CN": "新对局。你执红棋。该你走了。",
      "it": "Nuova partita. Sei il rosso. Tocca a te.",
      "ru": "Новая партия. Вы играете красными. Ваш ход.",
      "fr": "Nouvelle partie. Vous êtes les rouges. À vous.",
      "de": "Neue Partie. Du spielst Rot. Du bist am Zug.",
      "es": "Nueva partida. Eres rojas. Te toca.",
      "pt": "Novo jogo. Você é o vermelho. Sua vez."
    },
    "New caro game. You are X. Freestyle with double-open bans. Your move.": {
      "zh-CN": "新的五子棋。你执 X。自由开局，禁双头活三。该你走了。",
      "it": "Nuova partita di Caro. Sei X. Freestyle con divieto di doppia apertura. Tocca a te.",
      "ru": "Новая партия каро. Вы играете X. Свободный стиль, запрет двойного открытого ряда. Ваш ход.",
      "fr": "Nouvelle partie de Caro. Vous êtes X. Freestyle, interdiction des doubles ouvertures. À vous.",
      "de": "Neues Caro. Du bist X. Freestyle mit Verbot doppelter offener Reihen. Du bist am Zug.",
      "es": "Nueva partida de Caro. Eres X. Estilo libre, prohibidas las dobles abiertas. Te toca.",
      "pt": "Novo Caro. Você é X. Freestyle, proibido dois abertos. Sua vez."
    },
    "New Connect Four. You drop first. Your move.": {
      "zh-CN": "新的四子棋。你先落子。该你了。",
      "it": "Nuovo Forza 4. Inizi tu. Tocca a te.",
      "ru": "Новая партия в «Четыре в ряд». Вы ходите первыми.",
      "fr": "Nouveau Puissance 4. Vous jouez en premier. À vous.",
      "de": "Neues Vier gewinnt. Du wirfst zuerst. Du bist am Zug.",
      "es": "Nuevo Conecta 4. Tiras primero. Te toca.",
      "pt": "Novo Liga 4. Você começa. Sua vez."
    },
    "New Reversi. You are black. Your move.": {
      "zh-CN": "新的黑白棋。你执黑。该你走了。",
      "it": "Nuovo Reversi. Sei il nero. Tocca a te.",
      "ru": "Новая партия реверси. Вы играете чёрными. Ваш ход.",
      "fr": "Nouveau Reversi. Vous êtes les noirs. À vous.",
      "de": "Neues Reversi. Du spielst Schwarz. Du bist am Zug.",
      "es": "Nuevo Reversi. Eres negras. Te toca.",
      "pt": "Novo Reversi. Você é as pretas. Sua vez."
    },
    "New checkers game. You are white. Your move.": {
      "zh-CN": "新的跳棋。你执白。该你走了。",
      "it": "Nuova dama. Sei il bianco. Tocca a te.",
      "ru": "Новые шашки. Вы играете белыми. Ваш ход.",
      "fr": "Nouvelle partie de dames. Vous êtes les blancs. À vous.",
      "de": "Neues Dame-Spiel. Du spielst Weiß. Du bist am Zug.",
      "es": "Nuevas damas. Eres blancas. Te toca.",
      "pt": "Novas damas. Você é as brancas. Sua vez."
    },
    "New 9 by 9 go game. You are black. Your move.": {
      "zh-CN": "新的九路围棋。你执黑。该你走了。",
      "it": "Nuova partita di Go 9 per 9. Sei il nero. Tocca a te.",
      "ru": "Новое го 9×9. Вы играете чёрными. Ваш ход.",
      "fr": "Nouveau go 9 par 9. Vous êtes les noirs. À vous.",
      "de": "Neues Go 9 mal 9. Du spielst Schwarz. Du bist am Zug.",
      "es": "Nuevo go 9 por 9. Eres negras. Te toca.",
      "pt": "Novo go 9 por 9. Você é as pretas. Sua vez."
    },
    "New battleship game. Fire at the enemy board.": {
      "zh-CN": "新的海战棋。向对方棋盘开火。",
      "it": "Nuova battaglia navale. Fuoco sulla griglia nemica.",
      "ru": "Новой морской бой. Стреляйте по полю противника.",
      "fr": "Nouvelle bataille navale. Tirez sur la grille adverse.",
      "de": "Neues Schiffe versenken. Schieß auf das gegnerische Feld.",
      "es": "Nueva batalla naval. Dispara al tablero enemigo.",
      "pt": "Nova batalha naval. Atire no tabuleiro inimigo."
    },
    "New memory game. Flip two cards to find matching pairs.": {
      "zh-CN": "新的记忆翻牌。翻两张牌找对子。",
      "it": "Nuovo memory. Gira due carte per trovare le coppie.",
      "ru": "Новая игра на память. Откройте две карты и найдите пары.",
      "fr": "Nouveau memory. Retournez deux cartes pour trouver les paires.",
      "de": "Neues Memory. Decke zwei Karten auf, um Paare zu finden.",
      "es": "Nuevo memory. Voltea dos cartas para encontrar parejas.",
      "pt": "Novo jogo da memória. Vire duas cartas para achar os pares."
    },
    "New minesweeper game. Reveal a cell to start.": {
      "zh-CN": "新的扫雷。先翻开一格。",
      "it": "Nuovo campo minato. Scopri una cella per iniziare.",
      "ru": "Новый сапёр. Откройте клетку, чтобы начать.",
      "fr": "Nouveau démineur. Révélez une case pour commencer.",
      "de": "Neues Minesweeper. Decke ein Feld auf, um zu starten.",
      "es": "Nuevo buscaminas. Destapa una casilla para empezar.",
      "pt": "Novo campo minado. Revele uma célula para começar."
    },
    "New tic-tac-toe game. You are X and go first. Your move.": {
      "zh-CN": "新的井字棋。你执 X，先走。该你了。",
      "it": "Nuovo tris. Sei X e inizi tu. Tocca a te.",
      "ru": "Новые крестики-нолики. Вы играете X и ходите первыми.",
      "fr": "Nouveau morpion. Vous êtes X et jouez en premier. À vous.",
      "de": "Neues Tic-Tac-Toe. Du bist X und fängst an. Du bist am Zug.",
      "es": "Nuevo tres en raya. Eres X y empiezas. Te toca.",
      "pt": "Novo jogo da velha. Você é X e começa. Sua vez."
    },
    "New 2048 game. Merge matching tiles to reach 2048.": {
      "zh-CN": "新的 2048。合并相同数字，凑到 2048。",
      "it": "Nuovo 2048. Unisci le tessere uguali per arrivare a 2048.",
      "ru": "Новая игра 2048. Складывайте одинаковые плитки, чтобы получить 2048.",
      "fr": "Nouveau 2048. Fusionnez les tuiles identiques pour atteindre 2048.",
      "de": "Neues 2048. Verschmelze gleiche Steine, um 2048 zu erreichen.",
      "es": "Nuevo 2048. Junta fichas iguales para llegar a 2048.",
      "pt": "Novo 2048. Junte peças iguais até chegar a 2048."
    },
    "New sudoku puzzle. Fill in digits 1 to 9.": {
      "zh-CN": "新的数独。填入 1 到 9。",
      "it": "Nuovo sudoku. Inserisci le cifre da 1 a 9.",
      "ru": "Новое судоку. Заполните цифры от 1 до 9.",
      "fr": "Nouveau sudoku. Remplissez les chiffres de 1 à 9.",
      "de": "Neues Sudoku. Trage die Ziffern 1 bis 9 ein.",
      "es": "Nuevo sudoku. Rellena los dígitos del 1 al 9.",
      "pt": "Novo sudoku. Preencha os dígitos de 1 a 9."
    },
    "New Blackjack round. Press Start to deal.": {
      "zh-CN": "新的二十一点。按开始发牌。",
      "it": "Nuovo Blackjack. Premi Avvia per dare le carte.",
      "ru": "Новый раунд блэкджека. Нажмите Старт, чтобы раздать.",
      "fr": "Nouveau Blackjack. Appuyez sur Démarrer pour distribuer.",
      "de": "Neue Blackjack-Runde. Drücke Start zum Geben.",
      "es": "Nueva ronda de Blackjack. Pulsa Iniciar para repartir.",
      "pt": "Nova rodada de Blackjack. Toque em Iniciar para dar as cartas."
    },
    "Bust! You lose.": {
      "zh-CN": "爆牌了！你输了。",
      "it": "Sballato! Hai perso.",
      "ru": "Перебор! Вы проиграли.",
      "fr": "Bust ! Vous perdez.",
      "de": "Bust! Du verlierst.",
      "es": "¡Te pasaste! Pierdes.",
      "pt": "Estourou! Você perdeu."
    },
    "Five-card Charlie! You win!": {
      "zh-CN": "五张查理！你赢了！",
      "it": "Five-card Charlie! Hai vinto!",
      "ru": "Пять карт Чарли! Вы победили!",
      "fr": "Five-card Charlie ! Vous gagnez !",
      "de": "Five-card Charlie! Du gewinnst!",
      "es": "¡Five-card Charlie! ¡Ganaste!",
      "pt": "Five-card Charlie! Você ganhou!"
    },
    "New Simon game. Watch the sequence, then repeat it (0-3).": {
      "zh-CN": "新的西蒙。看序列，再用 0 到 3 重复。",
      "it": "Nuovo Simon. Guarda la sequenza e ripetila (0-3).",
      "ru": "Новый Саймон. Смотрите последовательность и повторите (0–3).",
      "fr": "Nouveau Simon. Regardez la séquence puis répétez-la (0-3).",
      "de": "Neues Simon. Schau die Folge an und wiederhole sie (0-3).",
      "es": "Nuevo Simon. Mira la secuencia y repítela (0-3).",
      "pt": "Novo Simon. Veja a sequência e repita (0-3)."
    },
    "New Simon game. Watch the sequence, then repeat it using pads 0 to 3.": {
      "zh-CN": "新的西蒙。看序列，再用按键 0 到 3 重复。",
      "it": "Nuovo Simon. Guarda la sequenza e ripetila con i tasti da 0 a 3.",
      "ru": "Новый Саймон. Смотрите последовательность и повторите кнопками 0–3.",
      "fr": "Nouveau Simon. Regardez la séquence puis répétez avec les touches 0 à 3.",
      "de": "Neues Simon. Schau die Folge an und wiederhole sie mit den Tasten 0 bis 3.",
      "es": "Nuevo Simon. Mira la secuencia y repítela con las teclas 0 a 3.",
      "pt": "Novo Simon. Veja a sequência e repita com as teclas 0 a 3."
    },
    "Keep going...": {
      "zh-CN": "继续……",
      "it": "Continua...",
      "ru": "Продолжайте...",
      "fr": "Continuez...",
      "de": "Weiter...",
      "es": "Sigue...",
      "pt": "Continue..."
    },
    "New hangman game. Guess a letter.": {
      "zh-CN": "新的猜词游戏。猜一个字母。",
      "it": "Nuovo impiccato. Indovina una lettera.",
      "ru": "Новая виселица. Назовите букву.",
      "fr": "Nouveau pendu. Devinez une lettre.",
      "de": "Neues Hangman. Rate einen Buchstaben.",
      "es": "Nuevo ahorcado. Adivina una letra.",
      "pt": "Nova forca. Adivinhe uma letra."
    },
    "New hangman game. Guess a letter of the Vietnamese word (no diacritics).": {
      "zh-CN": "新的猜词。猜越南语单词的一个字母（无声调）。",
      "it": "Nuovo impiccato. Indovina una lettera della parola vietnamita (senza segni).",
      "ru": "Новая виселица. Назовите букву вьетнамского слова (без диакритики).",
      "fr": "Nouveau pendu. Devinez une lettre du mot vietnamien (sans accents).",
      "de": "Neues Hangman. Rate einen Buchstaben des vietnamesischen Worts (ohne Akzente).",
      "es": "Nuevo ahorcado. Adivina una letra de la palabra vietnamita (sin tildes).",
      "pt": "Nova forca. Adivinhe uma letra da palavra vietnamita (sem acentos)."
    },
    "Correct!": {
      "zh-CN": "对了！",
      "it": "Giusto!",
      "ru": "Верно!",
      "fr": "Correct !",
      "de": "Richtig!",
      "es": "¡Correcto!",
      "pt": "Correto!"
    },
    "Wrong!": {
      "zh-CN": "错了！",
      "it": "Sbagliato!",
      "ru": "Неверно!",
      "fr": "Faux !",
      "de": "Falsch!",
      "es": "¡Incorrecto!",
      "pt": "Errado!"
    },
    "You guessed the whole word! You win.": {
      "zh-CN": "你猜对整个词了！你赢了。",
      "it": "Hai indovinato tutta la parola! Hai vinto.",
      "ru": "Вы угадали всё слово! Вы победили.",
      "fr": "Vous avez trouvé tout le mot ! Vous gagnez.",
      "de": "Du hast das ganze Wort erraten! Du gewinnst.",
      "es": "¡Adivinaste la palabra entera! Ganaste.",
      "pt": "Você acertou a palavra inteira! Você ganhou."
    },
    "Correct! You win.": {
      "zh-CN": "对了！你赢了。",
      "it": "Giusto! Hai vinto.",
      "ru": "Верно! Вы победили.",
      "fr": "Correct ! Vous gagnez.",
      "de": "Richtig! Du gewinnst.",
      "es": "¡Correcto! Ganaste.",
      "pt": "Correto! Você ganhou."
    },
    "I win!": {
      "zh-CN": "我赢了！",
      "it": "Ho vinto!",
      "ru": "Я победил!",
      "fr": "J'ai gagné !",
      "de": "Ich gewinne!",
      "es": "¡Gané!",
      "pt": "Eu ganhei!"
    },
    "Next game.": {
      "zh-CN": "下一局。",
      "it": "Prossima partita.",
      "ru": "Следующая игра.",
      "fr": "Prochaine partie.",
      "de": "Nächstes Spiel.",
      "es": "Siguiente partida.",
      "pt": "Próximo jogo."
    },
    "Better luck next time.": {
      "zh-CN": "下次好运。",
      "it": "Sarà per la prossima.",
      "ru": "Повезёт в следующий раз.",
      "fr": "Plus de chance la prochaine fois.",
      "de": "Beim nächsten Mal mehr Glück.",
      "es": "Más suerte la próxima.",
      "pt": "Mais sorte da próxima vez."
    },
    "I'm out — robot wins.": {
      "zh-CN": "我出完了——机器人赢了。",
      "it": "Sono a zero — vince il robot.",
      "ru": "У меня пусто — победил робот.",
      "fr": "Je n'ai plus de cartes — le robot gagne.",
      "de": "Ich bin leer — der Roboter gewinnt.",
      "es": "Me quedé sin cartas — gana el robot.",
      "pt": "Acabei as cartas — o robô ganhou."
    },
    "Luck wasn't with you.": {
      "zh-CN": "这次运气不在你这边。",
      "it": "La fortuna non era con te.",
      "ru": "Удача была не на вашей стороне.",
      "fr": "La chance n'était pas avec vous.",
      "de": "Das Glück war nicht auf deiner Seite.",
      "es": "La suerte no te acompañó.",
      "pt": "A sorte não estava com você."
    },
    "You drew a %s. Your total is %d. Do you want to hit again?": {
      "zh-CN": "你抽到了%s。当前点数 %d。还要加牌吗？",
      "it": "Hai pescato un %s. Totale %d. Vuoi un'altra carta?",
      "ru": "Вы взяли %s. Сумма %d. Взять ещё?",
      "fr": "Vous avez tiré un %s. Total %d. Voulez-vous une autre carte ?",
      "de": "Du hast %s gezogen. Summe %d. Noch eine Karte?",
      "es": "Sacaste un %s. Total %d. ¿Quieres otra carta?",
      "pt": "Você tirou um %s. Total %d. Quer mais uma carta?"
    },
    "Your current score is %d. Do you want to hit?": {
      "zh-CN": "你现在是 %d 点。还要加牌吗？",
      "it": "Il tuo punteggio è %d. Vuoi un'altra carta?",
      "ru": "У вас %d. Взять карту?",
      "fr": "Votre score est %d. Voulez-vous une carte ?",
      "de": "Dein Stand ist %d. Noch eine Karte?",
      "es": "Tu puntuación es %d. ¿Quieres carta?",
      "pt": "Sua pontuação é %d. Quer carta?"
    },
    "Dealer busts with %d! You win!": {
      "zh-CN": "庄家以 %d 点爆牌！你赢了！",
      "it": "Il banco sballa con %d! Hai vinto!",
      "ru": "Дилер перебрал с %d! Вы победили!",
      "fr": "Le croupier dépasse avec %d ! Vous gagnez !",
      "de": "Der Dealer bustet mit %d! Du gewinnst!",
      "es": "¡La banca se pasa con %d! ¡Ganaste!",
      "pt": "A banca estourou com %d! Você ganhou!"
    },
    "You %d — dealer %d. You win!": {
      "zh-CN": "你 %d — 庄家 %d。你赢了！",
      "it": "Tu %d — banco %d. Hai vinto!",
      "ru": "Вы %d — дилер %d. Вы победили!",
      "fr": "Vous %d — croupier %d. Vous gagnez !",
      "de": "Du %d — Dealer %d. Du gewinnst!",
      "es": "Tú %d — banca %d. ¡Ganaste!",
      "pt": "Você %d — banca %d. Você ganhou!"
    },
    "You %d — dealer %d. You lose.": {
      "zh-CN": "你 %d — 庄家 %d。你输了。",
      "it": "Tu %d — banco %d. Hai perso.",
      "ru": "Вы %d — дилер %d. Вы проиграли.",
      "fr": "Vous %d — croupier %d. Vous perdez.",
      "de": "Du %d — Dealer %d. Du verlierst.",
      "es": "Tú %d — banca %d. Pierdes.",
      "pt": "Você %d — banca %d. Você perdeu."
    },
    "Push at %d.": {
      "zh-CN": "%d 点平局。",
      "it": "Pareggio a %d.",
      "ru": "Ничья при %d.",
      "fr": "Égalité à %d.",
      "de": "Unentschieden bei %d.",
      "es": "Empate a %d.",
      "pt": "Empate em %d."
    },
    "Standing — dealer's turn. Dealer shows %d.": {
      "zh-CN": "你停牌——轮到庄家。庄家亮出 %d 点。",
      "it": "Stai — tocca al banco. Il banco mostra %d.",
      "ru": "Стоп — ход дилера. У дилера %d.",
      "fr": "Vous restez — au croupier. Il montre %d.",
      "de": "Du bleibst — der Dealer ist dran. Er zeigt %d.",
      "es": "Te plantas — turno de la banca. Muestra %d.",
      "pt": "Você parou — vez da banca. Ela mostra %d."
    },
    "You drew a %s — total %d. Bust! You lose.": {
      "zh-CN": "你抽到%s — 共 %d 点。爆了！你输了。",
      "it": "Hai pescato un %s — totale %d. Sballato! Hai perso.",
      "ru": "Вы взяли %s — сумма %d. Перебор! Вы проиграли.",
      "fr": "Vous avez tiré un %s — total %d. Bust ! Vous perdez.",
      "de": "Du zogst %s — Summe %d. Bust! Du verlierst.",
      "es": "Sacaste un %s — total %d. ¡Te pasaste! Pierdes.",
      "pt": "Você tirou um %s — total %d. Estourou! Você perdeu."
    },
    "Dealer draws a %s. Dealer total %d — bust!": {
      "zh-CN": "庄家抽到%s。庄家共 %d 点——爆了！",
      "it": "Il banco pesca un %s. Totale banco %d — sballa!",
      "ru": "Дилер берёт %s. Сумма дилера %d — перебор!",
      "fr": "Le croupier tire un %s. Total %d — bust !",
      "de": "Der Dealer zieht %s. Summe %d — Bust!",
      "es": "La banca saca un %s. Total %d — ¡se pasa!",
      "pt": "A banca tira um %s. Total %d — estourou!"
    },
    "Dealer draws a %s. Dealer total %d — stands.": {
      "zh-CN": "庄家抽到%s。庄家共 %d 点——停牌。",
      "it": "Il banco pesca un %s. Totale banco %d — sta.",
      "ru": "Дилер берёт %s. Сумма дилера %d — стоп.",
      "fr": "Le croupier tire un %s. Total %d — il reste.",
      "de": "Der Dealer zieht %s. Summe %d — bleibt.",
      "es": "La banca saca un %s. Total %d — se planta.",
      "pt": "A banca tira um %s. Total %d — para."
    },
    "Dealer draws a %s. Dealer total is %d — hits again.": {
      "zh-CN": "庄家抽到%s。庄家现为 %d 点——再要。",
      "it": "Il banco pesca un %s. Totale banco %d — pesca ancora.",
      "ru": "Дилер берёт %s. Сейчас у дилера %d — ещё карту.",
      "fr": "Le croupier tire un %s. Total %d — il reprend.",
      "de": "Der Dealer zieht %s. Stand %d — zieht nochmal.",
      "es": "La banca saca un %s. Total %d — pide otra.",
      "pt": "A banca tira um %s. Total %d — pede outra."
    },
    "Wrong! You lost at level %d.": {
      "zh-CN": "错了！你在第 %d 关输了。",
      "it": "Sbagliato! Hai perso al livello %d.",
      "ru": "Неверно! Вы проиграли на уровне %d.",
      "fr": "Faux ! Vous avez perdu au niveau %d.",
      "de": "Falsch! Du hast auf Stufe %d verloren.",
      "es": "¡Incorrecto! Perdiste en el nivel %d.",
      "pt": "Errado! Você perdeu no nível %d."
    },
    "Correct! New sequence has %d steps.": {
      "zh-CN": "对了！新序列有 %d 步。",
      "it": "Giusto! La nuova sequenza ha %d passi.",
      "ru": "Верно! Новая последовательность из %d шагов.",
      "fr": "Correct ! La nouvelle séquence a %d étapes.",
      "de": "Richtig! Die neue Folge hat %d Schritte.",
      "es": "¡Correcto! La nueva secuencia tiene %d pasos.",
      "pt": "Correto! A nova sequência tem %d passos."
    },
    "You lose! The word was %s.": {
      "zh-CN": "你输了！单词是 %s。",
      "it": "Hai perso! La parola era %s.",
      "ru": "Вы проиграли! Слово было %s.",
      "fr": "Vous perdez ! Le mot était %s.",
      "de": "Du verlierst! Das Wort war %s.",
      "es": "¡Perdiste! La palabra era %s.",
      "pt": "Você perdeu! A palavra era %s."
    },
    "New trivia round, %d questions. Answer with 0-3.": {
      "zh-CN": "新的问答，共 %d 题。用 0 到 3 作答。",
      "it": "Nuovo trivia, %d domande. Rispondi con 0-3.",
      "ru": "Новая викторина, %d вопросов. Отвечайте 0–3.",
      "fr": "Nouveau quiz, %d questions. Répondez avec 0-3.",
      "de": "Neues Quiz, %d Fragen. Antworte mit 0-3.",
      "es": "Nuevo trivia, %d preguntas. Responde con 0-3.",
      "pt": "Novo quiz, %d perguntas. Responda com 0-3."
    },
    "New trivia round, %d questions. Answer with 0, 1, 2 or 3.": {
      "zh-CN": "新的问答，共 %d 题。用 0、1、2 或 3 作答。",
      "it": "Nuovo trivia, %d domande. Rispondi con 0, 1, 2 o 3.",
      "ru": "Новая викторина, %d вопросов. Отвечайте 0, 1, 2 или 3.",
      "fr": "Nouveau quiz, %d questions. Répondez avec 0, 1, 2 ou 3.",
      "de": "Neues Quiz, %d Fragen. Antworte mit 0, 1, 2 oder 3.",
      "es": "Nuevo trivia, %d preguntas. Responde con 0, 1, 2 o 3.",
      "pt": "Novo quiz, %d perguntas. Responda com 0, 1, 2 ou 3."
    },
    "Wrong! The correct answer was: %s.": {
      "zh-CN": "错了！正确答案是：%s。",
      "it": "Sbagliato! La risposta corretta era: %s.",
      "ru": "Неверно! Правильный ответ: %s.",
      "fr": "Faux ! La bonne réponse était : %s.",
      "de": "Falsch! Die richtige Antwort war: %s.",
      "es": "¡Incorrecto! La respuesta correcta era: %s.",
      "pt": "Errado! A resposta certa era: %s."
    },
    "Done! You scored %d/%d.": {
      "zh-CN": "结束！你得了 %d/%d 分。",
      "it": "Finito! Hai fatto %d/%d.",
      "ru": "Готово! Счёт %d/%d.",
      "fr": "Terminé ! Score %d/%d.",
      "de": "Fertig! Du hast %d/%d Punkte.",
      "es": "¡Listo! Puntuaste %d/%d.",
      "pt": "Pronto! Você fez %d/%d."
    },
    "New Wordle. Guess the %d-letter Vietnamese word (no diacritics). You have %d guesses.": {
      "zh-CN": "新的 Wordle。猜 %d 个字母的越南语词（无声调）。你有 %d 次机会。",
      "it": "Nuovo Wordle. Indovina la parola vietnamita di %d lettere (senza segni). Hai %d tentativi.",
      "ru": "Новый Wordle. Угадайте вьетнамское слово из %d букв (без диакритики). У вас %d попыток.",
      "fr": "Nouveau Wordle. Devinez le mot vietnamien de %d lettres (sans accents). Vous avez %d essais.",
      "de": "Neues Wordle. Rate das vietnamesische Wort mit %d Buchstaben (ohne Akzente). Du hast %d Versuche.",
      "es": "Nuevo Wordle. Adivina la palabra vietnamita de %d letras (sin tildes). Tienes %d intentos.",
      "pt": "Novo Wordle. Adivinhe a palavra vietnamita de %d letras (sem acentos). Você tem %d tentativas."
    },
    "New Wordle. Guess the %d-letter Vietnamese word, no diacritics. You have %d guesses.": {
      "zh-CN": "新的 Wordle。猜 %d 个字母的越南语词，无声调。你有 %d 次机会。",
      "it": "Nuovo Wordle. Indovina la parola vietnamita di %d lettere, senza segni. Hai %d tentativi.",
      "ru": "Новый Wordle. Угадайте вьетнамское слово из %d букв без диакритики. У вас %d попыток.",
      "fr": "Nouveau Wordle. Devinez le mot vietnamien de %d lettres, sans accents. Vous avez %d essais.",
      "de": "Neues Wordle. Rate das vietnamesische Wort mit %d Buchstaben, ohne Akzente. Du hast %d Versuche.",
      "es": "Nuevo Wordle. Adivina la palabra vietnamita de %d letras, sin tildes. Tienes %d intentos.",
      "pt": "Novo Wordle. Adivinhe a palavra vietnamita de %d letras, sem acentos. Você tem %d tentativas."
    },
    "Out of guesses. The word was %s.": {
      "zh-CN": "次数用完。单词是 %s。",
      "it": "Tentativi finiti. La parola era %s.",
      "ru": "Попытки кончились. Слово было %s.",
      "fr": "Plus d'essais. Le mot était %s.",
      "de": "Keine Versuche mehr. Das Wort war %s.",
      "es": "Sin intentos. La palabra era %s.",
      "pt": "Sem tentativas. A palavra era %s."
    },
    "I picked a number from %d to %d. Guess it!": {
      "zh-CN": "我想了一个从 %d 到 %d 的数字。来猜吧！",
      "it": "Ho pensato un numero da %d a %d. Indovinalo!",
      "ru": "Я загадал число от %d до %d. Угадайте!",
      "fr": "J'ai choisi un nombre de %d à %d. Devinez-le !",
      "de": "Ich habe eine Zahl von %d bis %d gewählt. Rate sie!",
      "es": "Pensé un número del %d al %d. ¡Adivínalo!",
      "pt": "Pensei num número de %d a %d. Adivinhe!"
    },
    "%s The number was %d — you won in %d guesses.": {
      "zh-CN": "%s 数字是 %d — 你用 %d 次猜中了。",
      "it": "%s Il numero era %d — hai vinto in %d tentativi.",
      "ru": "%s Число было %d — вы угадали за %d попыток.",
      "fr": "%s Le nombre était %d — vous avez gagné en %d essais.",
      "de": "%s Die Zahl war %d — du hast in %d Versuchen gewonnen.",
      "es": "%s El número era %d — ganaste en %d intentos.",
      "pt": "%s O número era %d — você acertou em %d tentativas."
    },
    "Out of guesses! The number was %d.": {
      "zh-CN": "次数用完了！数字是 %d。",
      "it": "Tentativi finiti! Il numero era %d.",
      "ru": "Попытки кончились! Число было %d.",
      "fr": "Plus d'essais ! Le nombre était %d.",
      "de": "Keine Versuche mehr! Die Zahl war %d.",
      "es": "¡Sin intentos! El número era %d.",
      "pt": "Sem tentativas! O número era %d."
    },
    "Brilliant! You found the secret number.": {
      "zh-CN": "太棒了！你找到了秘密数字。",
      "it": "Brillante! Hai trovato il numero segreto.",
      "ru": "Отлично! Вы нашли загаданное число.",
      "fr": "Bravo ! Vous avez trouvé le nombre secret.",
      "de": "Brillant! Du hast die geheime Zahl gefunden.",
      "es": "¡Brillante! Encontraste el número secreto.",
      "pt": "Brilhante! Você achou o número secreto."
    },
    "I knew you could do it!": {
      "zh-CN": "我就知道你行！",
      "it": "Sapevo che ce l'avresti fatta!",
      "ru": "Я знал, что у вас получится!",
      "fr": "Je savais que vous y arriveriez !",
      "de": "Ich wusste, dass du es schaffst!",
      "es": "¡Sabía que podías!",
      "pt": "Eu sabia que você conseguia!"
    },
    "Victory!": {
      "zh-CN": "胜利！",
      "it": "Vittoria!",
      "ru": "Победа!",
      "fr": "Victoire !",
      "de": "Sieg!",
      "es": "¡Victoria!",
      "pt": "Vitória!"
    },
    "You're really good at this.": {
      "zh-CN": "你真的很擅长这个。",
      "it": "Sei davvero bravo.",
      "ru": "У вас отлично получается.",
      "fr": "Vous êtes vraiment doué.",
      "de": "Daran bist du wirklich gut.",
      "es": "Se te da muy bien.",
      "pt": "Você é muito bom nisso."
    },
    "Let's play another round!": {
      "zh-CN": "再来一局吧！",
      "it": "Facciamo un'altra partita!",
      "ru": "Сыграем ещё раунд!",
      "fr": "Encore une manche !",
      "de": "Noch eine Runde!",
      "es": "¡Otra ronda!",
      "pt": "Vamos jogar outra rodada!"
    },
    "Correct! That was a sharp guess.": {
      "zh-CN": "对了！这一猜很准。",
      "it": "Giusto! Bel tentativo.",
      "ru": "Верно! Отличная догадка.",
      "fr": "Correct ! Belle intuition.",
      "de": "Richtig! Das war ein scharfer Tipp.",
      "es": "¡Correcto! Buen tino.",
      "pt": "Correto! Foi um palpite afiado."
    },
    "Awesome! The secret is out.": {
      "zh-CN": "太好了！秘密揭晓了。",
      "it": "Ottimo! Il segreto è svelato.",
      "ru": "Супер! Секрет раскрыт.",
      "fr": "Génial ! Le secret est levé.",
      "de": "Super! Das Geheimnis ist raus.",
      "es": "¡Genial! El secreto salió.",
      "pt": "Ótimo! O segredo saiu."
    },
    "New guess-the-number round. I picked a number from %d to %d. You have %d guesses. Good luck!": {
      "zh-CN": "新的猜数字。我想了 %d 到 %d 之间的一个数。你有 %d 次机会。祝你好运！",
      "it": "Nuovo indovina il numero. Ho scelto un numero da %d a %d. Hai %d tentativi. Buona fortuna!",
      "ru": "Новая игра «угадай число». Я загадал число от %d до %d. У вас %d попыток. Удачи!",
      "fr": "Nouveau juste nombre. J'ai choisi un nombre de %d à %d. Vous avez %d essais. Bonne chance !",
      "de": "Neues Zahlenraten. Ich habe eine Zahl von %d bis %d gewählt. Du hast %d Versuche. Viel Glück!",
      "es": "Nueva adivina el número. Pensé un número del %d al %d. Tienes %d intentos. ¡Suerte!",
      "pt": "Novo adivinhe o número. Pensei num número de %d a %d. Você tem %d tentativas. Boa sorte!"
    },
    "Higher.": {
      "zh-CN": "再高一点。", "it": "Più alto.", "ru": "Выше.", "fr": "Plus haut.", "de": "Höher.", "es": "Más alto.", "pt": "Mais alto."
    },
    "Lower.": {
      "zh-CN": "再低一点。", "it": "Più basso.", "ru": "Ниже.", "fr": "Plus bas.", "de": "Niedriger.", "es": "Más bajo.", "pt": "Mais baixo."
    },
    "So close! Go a little higher.": {
      "zh-CN": "很接近！再高一点点。", "it": "Quasi! Un po' più alto.", "ru": "Почти! Чуть выше.", "fr": "Presque ! Un peu plus haut.", "de": "Ganz nah! Etwas höher.", "es": "¡Casi! Un poco más alto.", "pt": "Quase! Um pouco mais alto."
    },
    "So close! Go a little lower.": {
      "zh-CN": "很接近！再低一点点。", "it": "Quasi! Un po' più basso.", "ru": "Почти! Чуть ниже.", "fr": "Presque ! Un peu plus bas.", "de": "Ganz nah! Etwas niedriger.", "es": "¡Casi! Un poco más bajo.", "pt": "Quase! Um pouco mais baixo."
    },
    "Way too low — go higher!": {
      "zh-CN": "太低了——再高！", "it": "Troppo basso — più alto!", "ru": "Слишком низко — выше!", "fr": "Beaucoup trop bas — plus haut !", "de": "Viel zu niedrig — höher!", "es": "¡Muy bajo — más alto!", "pt": "Muito baixo — mais alto!"
    },
    "Too high — try a smaller number.": {
      "zh-CN": "太高了——试试更小的数。", "it": "Troppo alto — prova un numero più piccolo.", "ru": "Слишком высоко — меньше.", "fr": "Trop haut — un plus petit nombre.", "de": "Zu hoch — eine kleinere Zahl.", "es": "Demasiado alto — un número menor.", "pt": "Alto demais — tente um menor."
    },
    "%d guesses left.": {
      "zh-CN": "还剩 %d 次。",
      "it": "Ancora %d tentativi.",
      "ru": "Осталось %d попыток.",
      "fr": "Encore %d essais.",
      "de": "Noch %d Versuche.",
      "es": "Quedan %d intentos.",
      "pt": "Restam %d tentativas."
    }
  };

  var phraseYouMoved = L({
    "en": "You moved %s.",
    "vi": "Bạn đi %s.",
    "zh-CN": "你走了 %s。",
    "it": "Hai mosso %s.",
    "ru": "Вы походили %s.",
    "fr": "Vous avez joué %s.",
    "de": "Du hast %s gezogen.",
    "es": "Moviste %s.",
    "pt": "Você moveu %s."
  });
  var phraseIMoved = L({
    "en": "I moved %s.",
    "vi": "Tôi đi %s.",
    "zh-CN": "我走了 %s。",
    "it": "Ho mosso %s.",
    "ru": "Я походил %s.",
    "fr": "J'ai joué %s.",
    "de": "Ich habe %s gezogen.",
    "es": "Moví %s.",
    "pt": "Eu movi %s."
  });
  var phraseMoveFromTo = L({
    "en": "%s from %s to %s",
    "vi": "%s từ %s đến %s",
    "zh-CN": "%s从 %s 到 %s",
    "it": "%s da %s a %s",
    "ru": "%s с %s на %s",
    "fr": "%s de %s vers %s",
    "de": "%s von %s nach %s",
    "es": "%s de %s a %s",
    "pt": "%s de %s para %s"
  });
  var phrasePromote = L({
    "en": ", promote to %s",
    "vi": ", phong %s",
    "zh-CN": "，升变为%s",
    "it": ", promozione a %s",
    "ru": ", превращение в %s",
    "fr": ", promotion en %s",
    "de": ", Umwandlung in %s",
    "es": ", coronación a %s",
    "pt": ", promoção a %s"
  });
  var phraseYouPlayed = L({
    "en": "You played %s.",
    "vi": "Bạn đánh %s.",
    "zh-CN": "你下了 %s。",
    "it": "Hai giocato %s.",
    "ru": "Вы сыграли %s.",
    "fr": "Vous avez joué %s.",
    "de": "Du hast %s gespielt.",
    "es": "Jugaste %s.",
    "pt": "Você jogou %s."
  });
  var phraseIPlayed = L({
    "en": "I played %s.",
    "vi": "Bot đánh %s.",
    "zh-CN": "我下了 %s。",
    "it": "Ho giocato %s.",
    "ru": "Я сыграл %s.",
    "fr": "J'ai joué %s.",
    "de": "Ich habe %s gespielt.",
    "es": "Jugué %s.",
    "pt": "Eu joguei %s."
  });
  var phraseYouWin = L({
    "en": "You win!",
    "vi": "Bạn thắng!",
    "zh-CN": "你赢了！",
    "it": "Hai vinto!",
    "ru": "Вы победили!",
    "fr": "Vous gagnez !",
    "de": "Du gewinnst!",
    "es": "¡Ganaste!",
    "pt": "Você ganhou!"
  });
  var phraseIWin = L({
    "en": "I win.",
    "vi": "Bot thắng.",
    "zh-CN": "我赢了。",
    "it": "Ho vinto.",
    "ru": "Я победил.",
    "fr": "Je gagne.",
    "de": "Ich gewinne.",
    "es": "Gané.",
    "pt": "Eu ganhei."
  });
  var phraseGameOver = L({
    "en": "Game over.",
    "vi": "Hết ván.",
    "zh-CN": "对局结束。",
    "it": "Partita finita.",
    "ru": "Игра окончена.",
    "fr": "Partie terminée.",
    "de": "Spiel vorbei.",
    "es": "Fin de la partida.",
    "pt": "Fim de jogo."
  });
  var phraseDraw = L({
    "en": "Draw.",
    "vi": "Hoà.",
    "zh-CN": "和棋。",
    "it": "Patta.",
    "ru": "Ничья.",
    "fr": "Nulle.",
    "de": "Remis.",
    "es": "Tablas.",
    "pt": "Empate."
  });
  var phraseYourTurn = L({
    "en": "Your turn.",
    "vi": "Đến lượt bạn.",
    "zh-CN": "该你了。",
    "it": "Tocca a te.",
    "ru": "Ваш ход.",
    "fr": "À vous.",
    "de": "Du bist am Zug.",
    "es": "Te toca.",
    "pt": "Sua vez."
  });
  var phraseCheck = L({
    "en": "Check!",
    "vi": "Chiếu!",
    "zh-CN": "将军！",
    "it": "Scacco!",
    "ru": "Шах!",
    "fr": "Échec !",
    "de": "Schach!",
    "es": "¡Jaque!",
    "pt": "Xeque!"
  });
  var phraseCheckmate = L({
    "en": "Checkmate.",
    "vi": "Chiếu hết.",
    "zh-CN": "将死。",
    "it": "Scacco matto.",
    "ru": "Мат.",
    "fr": "Échec et mat.",
    "de": "Schachmatt.",
    "es": "Jaque mate.",
    "pt": "Xeque-mate."
  });
  var phraseCheckmateXQ = L({
    "en": "Checkmate.",
    "vi": "Chiếu bí.",
    "zh-CN": "将死。",
    "it": "Scacco matto.",
    "ru": "Мат.",
    "fr": "Échec et mat.",
    "de": "Schachmatt.",
    "es": "Jaque mate.",
    "pt": "Xeque-mate."
  });
  var phraseStalemate = L({
    "en": "Stalemate. Draw.",
    "vi": "Hết nước. Hòa.",
    "zh-CN": "逼和。和棋。",
    "it": "Stallo. Patta.",
    "ru": "Пат. Ничья.",
    "fr": "Pat. Nulle.",
    "de": "Patt. Remis.",
    "es": "Ahogado. Tablas.",
    "pt": "Afogamento. Empate."
  });
  var phraseStalemateXQ = L({
    "en": "Stalemate. Draw.",
    "vi": "Hết nước đi. Hòa.",
    "zh-CN": "困毙。和棋。",
    "it": "Stallo. Patta.",
    "ru": "Пат. Ничья.",
    "fr": "Pat. Nulle.",
    "de": "Patt. Remis.",
    "es": "Ahogado. Tablas.",
    "pt": "Afogamento. Empate."
  });

  var chessPieceL = lTable({
    "k": { "en": "king", "vi": "vua", "zh-CN": "王", "it": "re", "ru": "король", "fr": "roi", "de": "König", "es": "rey", "pt": "rei" },
    "q": { "en": "queen", "vi": "hậu", "zh-CN": "后", "it": "regina", "ru": "ферзь", "fr": "dame", "de": "Dame", "es": "dama", "pt": "dama" },
    "r": { "en": "rook", "vi": "xe", "zh-CN": "车", "it": "torre", "ru": "ладья", "fr": "tour", "de": "Turm", "es": "torre", "pt": "torre" },
    "b": { "en": "bishop", "vi": "tượng", "zh-CN": "象", "it": "alfiere", "ru": "слон", "fr": "fou", "de": "Läufer", "es": "alfil", "pt": "bispo" },
    "n": { "en": "knight", "vi": "mã", "zh-CN": "马", "it": "cavallo", "ru": "конь", "fr": "cavalier", "de": "Springer", "es": "caballo", "pt": "cavalo" },
    "p": { "en": "pawn", "vi": "tốt", "zh-CN": "兵", "it": "pedone", "ru": "пешка", "fr": "pion", "de": "Bauer", "es": "peón", "pt": "peão" }
  });
  var xiangqiPieceL = lTable({
    "k": { "en": "general", "vi": "tướng", "zh-CN": "将", "it": "generale", "ru": "генерал", "fr": "général", "de": "General", "es": "general", "pt": "general" },
    "a": { "en": "advisor", "vi": "sĩ", "zh-CN": "士", "it": "consigliere", "ru": "советник", "fr": "garde", "de": "Mandarin", "es": "guardia", "pt": "conselheiro" },
    "e": { "en": "elephant", "vi": "tượng", "zh-CN": "象", "it": "elefante", "ru": "слон", "fr": "éléphant", "de": "Elefant", "es": "elefante", "pt": "elefante" },
    "h": { "en": "horse", "vi": "mã", "zh-CN": "马", "it": "cavallo", "ru": "конь", "fr": "cavalier", "de": "Pferd", "es": "caballo", "pt": "cavalo" },
    "r": { "en": "chariot", "vi": "xe", "zh-CN": "车", "it": "carro", "ru": "колесница", "fr": "char", "de": "Wagen", "es": "carro", "pt": "carro" },
    "c": { "en": "cannon", "vi": "pháo", "zh-CN": "炮", "it": "cannone", "ru": "пушка", "fr": "canon", "de": "Kanone", "es": "cañón", "pt": "canhão" },
    "p": { "en": "soldier", "vi": "tốt", "zh-CN": "兵", "it": "soldato", "ru": "солдат", "fr": "soldat", "de": "Soldat", "es": "soldado", "pt": "soldado" }
  });
  var unoColorL = lTable({
    "r": { "en": "red", "vi": "đỏ", "zh-CN": "红", "it": "rosso", "ru": "красный", "fr": "rouge", "de": "rot", "es": "rojo", "pt": "vermelho" },
    "g": { "en": "green", "vi": "xanh lá", "zh-CN": "绿", "it": "verde", "ru": "зелёный", "fr": "vert", "de": "grün", "es": "verde", "pt": "verde" },
    "b": { "en": "blue", "vi": "xanh dương", "zh-CN": "蓝", "it": "blu", "ru": "синий", "fr": "bleu", "de": "blau", "es": "azul", "pt": "azul" },
    "y": { "en": "yellow", "vi": "vàng", "zh-CN": "黄", "it": "giallo", "ru": "жёлтый", "fr": "jaune", "de": "gelb", "es": "amarillo", "pt": "amarelo" }
  });
  var unoSpecialL = lTable({
    "wild": { "en": "wild", "vi": "đổi màu", "zh-CN": "变色", "it": "jolly", "ru": "цвет", "fr": "joker", "de": "Farbwahl", "es": "comodín", "pt": "coringas" },
    "wild4": { "en": "draw four", "vi": "cộng bốn", "zh-CN": "加四", "it": "pesca quattro", "ru": "плюс четыре", "fr": "plus quatre", "de": "plus vier", "es": "roba cuatro", "pt": "compra quatro" },
    "skip": { "en": "skip %s", "vi": "bỏ lượt %s", "zh-CN": "禁手%s", "it": "salta %s", "ru": "пропуск %s", "fr": "passe %s", "de": "Aussetzen %s", "es": "salta %s", "pt": "pula %s" },
    "draw2": { "en": "draw two %s", "vi": "cộng hai %s", "zh-CN": "加二%s", "it": "pesca due %s", "ru": "плюс два %s", "fr": "plus deux %s", "de": "plus zwei %s", "es": "roba dos %s", "pt": "compra dois %s" },
    "reverse": { "en": "reverse %s", "vi": "đảo chiều %s", "zh-CN": "反转%s", "it": "inverti %s", "ru": "реверс %s", "fr": "sens inverse %s", "de": "Richtungswechsel %s", "es": "reversa %s", "pt": "inverte %s" },
    "chose": { "en": ", chose %s", "vi": ", chọn %s", "zh-CN": "，选%s", "it": ", colore %s", "ru": ", цвет %s", "fr": ", couleur %s", "de": ", Farbe %s", "es": ", color %s", "pt": ", cor %s" },
    "num": { "en": "%s %s", "vi": "%s %s", "zh-CN": "%s%s", "it": "%s %s", "ru": "%s %s", "fr": "%s %s", "de": "%s %s", "es": "%s %s", "pt": "%s %s" }
  });
  var bjRankL = lTable({
    "A": { "en": "Ace", "vi": "Át", "zh-CN": "A", "it": "Asso", "ru": "Туз", "fr": "As", "de": "Ass", "es": "As", "pt": "Ás" },
    "J": { "en": "Jack", "vi": "J", "zh-CN": "J", "it": "Fante", "ru": "Валет", "fr": "Valet", "de": "Bube", "es": "Jota", "pt": "Valete" },
    "Q": { "en": "Queen", "vi": "Q", "zh-CN": "Q", "it": "Donna", "ru": "Дама", "fr": "Dame", "de": "Dame", "es": "Reina", "pt": "Dama" },
    "K": { "en": "King", "vi": "K", "zh-CN": "K", "it": "Re", "ru": "Король", "fr": "Roi", "de": "König", "es": "Rey", "pt": "Rei" }
  });
  var pokerHandL = lTable({
    "straight_flush": { "en": "STRAIGHT FLUSH", "vi": "THÙNG PHÁ SẢNH", "zh-CN": "同花顺", "it": "SCALA COLORE", "ru": "СТРИТ-ФЛЕШ", "fr": "QUINTE FLUSH", "de": "STRAIGHT FLUSH", "es": "ESCALERA DE COLOR", "pt": "SEQUÊNCIA DE COR" },
    "four": { "en": "FOUR OF A KIND", "vi": "TỨ QUÝ", "zh-CN": "四条", "it": "POKER", "ru": "КАРЕ", "fr": "CARRÉ", "de": "VIERLING", "es": "PÓKER", "pt": "QUADRA" },
    "full_house": { "en": "FULL HOUSE", "vi": "CÙ LŨ", "zh-CN": "葫芦", "it": "FULL", "ru": "ФУЛ-ХАУС", "fr": "FULL", "de": "FULL HOUSE", "es": "FULL", "pt": "FULL HOUSE" },
    "flush": { "en": "FLUSH", "vi": "THÙNG", "zh-CN": "同花", "it": "COLORE", "ru": "ФЛЕШ", "fr": "COULEUR", "de": "FLUSH", "es": "COLOR", "pt": "FLUSH" },
    "straight": { "en": "STRAIGHT", "vi": "SẢNH", "zh-CN": "顺子", "it": "SCALA", "ru": "СТРИТ", "fr": "QUINTE", "de": "STRASSE", "es": "ESCALERA", "pt": "SEQUÊNCIA" },
    "three": { "en": "THREE OF A KIND", "vi": "BỘ BA", "zh-CN": "三条", "it": "TRIS", "ru": "ТРОЙКА", "fr": "BRELAN", "de": "DRILLING", "es": "TRÍO", "pt": "TRINCA" },
    "two_pair": { "en": "TWO PAIR", "vi": "HAI ĐÔI", "zh-CN": "两对", "it": "DOPPIA COPPIA", "ru": "ДВЕ ПАРЫ", "fr": "DEUX PAIRES", "de": "ZWEI PAARE", "es": "DOBLES PAREJAS", "pt": "DOIS PARES" },
    "pair": { "en": "ONE PAIR", "vi": "MỘT ĐÔI", "zh-CN": "一对", "it": "COPPIA", "ru": "ПАРА", "de": "EIN PAAR", "fr": "UNE PAIRE", "es": "PAREJA", "pt": "UM PAR" },
    "high": { "en": "HIGH CARD", "vi": "MẬU THẦU", "zh-CN": "高牌", "it": "CARTA ALTA", "ru": "СТАРШАЯ КАРТА", "fr": "CARTE HAUTE", "de": "HOHE KARTE", "es": "CARTA ALTA", "pt": "CARTA ALTA" }
  });

  function chessPieceSpoken(p) {
    var s = wordL(chessPieceL, p, '');
    if (s === '') {
      return L({ "en": "piece", "vi": "quân", "zh-CN": "棋子", "it": "pezzo", "ru": "фигура", "fr": "pièce", "de": "Figur", "es": "pieza", "pt": "peça" }).get();
    }
    return s;
  }

  function xiangqiPieceSpoken(p) {
    var s = wordL(xiangqiPieceL, p, '');
    if (s === '') {
      return L({ "en": "piece", "vi": "quân cờ", "zh-CN": "棋子", "it": "pezzo", "ru": "фигура", "fr": "pièce", "de": "Figur", "es": "pieza", "pt": "peça" }).get();
    }
    return s;
  }

  function speakMoveLang(piece, uci) {
    uci = trimSpace(uci).toLowerCase();
    var name = chessPieceSpoken(piece);
    if (uci.length < 4) {
      return name;
    }
    var s = phraseMoveFromTo.sprintf(name, speakSq(uci.slice(0, 2)), speakSq(uci.slice(2, 4)));
    if (uci.length >= 5) {
      s += phrasePromote.sprintf(chessPieceSpoken(uci[4]));
    }
    return s;
  }

  function xiangqiSpeakMoveLang(piece, uci) {
    uci = trimSpace(uci).toLowerCase();
    var name = xiangqiPieceSpoken(piece);
    if (uci.length < 4) {
      return name;
    }
    return phraseMoveFromTo.sprintf(name, xiangqiSpeakSq(uci.slice(0, 2)), xiangqiSpeakSq(uci.slice(2, 4)));
  }

  function joinSpeak(parts) {
    return trimSpace((parts || []).join(' '));
  }

  function appendMate(parts, mate, winnerHuman, winnerKnown) {
    parts = parts || [];
    if (winnerKnown && winnerHuman) {
      parts.push(mate.get() + ' ' + phraseYouWin.get());
      return parts;
    }
    if (winnerKnown && !winnerHuman) {
      parts.push(mate.get() + ' ' + phraseIWin.get());
      return parts;
    }
    var m = mate.get();
    if (m.slice(-1) === '.') m = m.slice(0, -1);
    parts.push(m + '!');
    return parts;
  }

  function buildChessCommentForLang(youMove, botMove, youPiece, botPiece, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push(phraseYouMoved.sprintf(speakMoveLang(youPiece, youMove)));
    }
    if (botMove) {
      parts.push(phraseIMoved.sprintf(speakMoveLang(botPiece, botMove)));
    }
    switch (status) {
      case 'checkmate':
        parts = appendMate(parts, phraseCheckmate, winner === 'white', winner === 'white' || winner === 'black');
        break;
      case 'stalemate':
        parts.push(phraseStalemate.get());
        break;
      case 'check':
        parts.push(phraseCheck.get());
        break;
    }
    return joinSpeak(parts);
  }

  function buildXiangqiCommentForLang(youMove, botMove, youPiece, botPiece, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push(phraseYouMoved.sprintf(xiangqiSpeakMoveLang(youPiece, youMove)));
    }
    if (botMove) {
      parts.push(phraseIMoved.sprintf(xiangqiSpeakMoveLang(botPiece, botMove)));
    }
    switch (status) {
      case 'checkmate':
        parts = appendMate(parts, phraseCheckmateXQ, winner === 'red', winner === 'red' || winner === 'black');
        break;
      case 'stalemate':
        parts.push(phraseStalemateXQ.get());
        break;
      case 'check':
        parts.push(phraseCheck.get());
        break;
    }
    return joinSpeak(parts);
  }

  function buildPlaceCommentForLang(youMove, botMove, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push(phraseYouPlayed.sprintf(youMove));
    }
    if (botMove) {
      parts.push(phraseIPlayed.sprintf(botMove));
    }
    switch (status) {
      case 'win': case 'won': case 'checkmate':
        if (winner === 'bot' || winner === 'O') {
          parts.push(phraseIWin.get());
        } else if (winner) {
          parts.push(phraseYouWin.get());
        } else {
          parts.push(phraseGameOver.get());
        }
        break;
      case 'draw': case 'stalemate':
        parts.push(phraseDraw.get());
        break;
    }
    if (parts.length === 0) {
      return phraseYourTurn.get();
    }
    return parts.join(' ');
  }

  function unoColorSpoken(col) {
    var s = wordL(unoColorL, col, '');
    if (s !== '') {
      return s;
    }
    return trimSpace(col).toLowerCase();
  }

  function unoSpeakCardLang(card, chosenColor) {
    var c = trimSpace(card).toUpperCase();
    var s;
    if (c === 'W') {
      s = unoSpecialL.wild.get();
      if (chosenColor) {
        s += unoSpecialL.chose.sprintf(unoColorSpoken(chosenColor));
      }
      return s;
    }
    if (c === 'W4') {
      s = unoSpecialL.wild4.get();
      if (chosenColor) {
        s += unoSpecialL.chose.sprintf(unoColorSpoken(chosenColor));
      }
      return s;
    }
    if (c.length < 2) {
      return c;
    }
    var col = c.slice(0, 1);
    var rank = c.slice(1);
    var cv = unoColorSpoken(col);
    switch (rank) {
      case 'S':
        return unoSpecialL.skip.sprintf(cv);
      case 'D':
        return unoSpecialL.draw2.sprintf(cv);
      case 'V':
        return unoSpecialL.reverse.sprintf(cv);
      default:
        return unoSpecialL.num.sprintf(rank, cv);
    }
  }

  // bjCardRank is mg_blackjack.go bjCardRank (needed by bjRankSpoken).
  function bjCardRank(card) {
    card = card == null ? '' : String(card);
    if (card.length >= 3 && card[0] === '1' && card[1] === '0') {
      return '10';
    }
    if (card.length === 0) {
      return '';
    }
    return card.slice(0, 1);
  }

  function bjRankSpoken(card) {
    var r = bjCardRank(card);
    var s = wordL(bjRankL, r, '');
    if (s !== '') {
      return s;
    }
    if (hasOwn(bjRankL, r)) {
      s = bjRankL[r].get();
      if (s !== '') return s;
    }
    return r;
  }

  function pokerHandSpoken(key) {
    var s = wordL(pokerHandL, key, '');
    if (s !== '') {
      return s;
    }
    return key;
  }

  // ---------------------------------------------------------------------------
  // board_game_speak.go
  // ---------------------------------------------------------------------------

  function buildPlaceCommentEN(game, youMove, botMove, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push('You played ' + youMove + '.');
    }
    if (botMove) {
      parts.push('I played ' + botMove + '.');
    }
    switch (status) {
      case 'win': case 'won': case 'checkmate':
        if (winner === 'bot' || winner === 'O') {
          parts.push('I win.');
        } else if (winner) {
          parts.push('You win!');
        } else {
          parts.push('Game over.');
        }
        break;
      case 'draw': case 'stalemate':
        parts.push('Draw.');
        break;
    }
    if (parts.length === 0) {
      return 'Your turn.';
    }
    return parts.join(' ');
  }

  function buildPlaceCommentVI(game, youMove, botMove, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push('Bạn đánh ' + youMove + '.');
    }
    if (botMove) {
      parts.push('Bot đánh ' + botMove + '.');
    }
    switch (status) {
      case 'win': case 'won': case 'checkmate':
        if (winner === 'bot' || winner === 'O') {
          parts.push('Bot thắng.');
        } else if (winner) {
          parts.push('Bạn thắng!');
        } else {
          parts.push('Hết ván.');
        }
        break;
      case 'draw': case 'stalemate':
        parts.push('Hoà.');
        break;
    }
    if (parts.length === 0) {
      return 'Đến lượt bạn.';
    }
    return parts.join(' ');
  }

  function buildPlaceSpoken(game, youMove, botMove, status, winner) {
    var lang = gameSpeakLang();
    if (lang === 'en') {
      return buildPlaceCommentEN(game, youMove, botMove, status, winner);
    }
    if (lang === 'vi') {
      return buildPlaceCommentVI(game, youMove, botMove, status, winner);
    }
    return buildPlaceCommentForLang(youMove, botMove, status, winner);
  }

  function buildPlaceSpokenHumanOnly(game, youMove, status, winner) {
    // While bot still to move, don't claim win for bot side.
    return buildPlaceSpoken(game, youMove, '', status, winner);
  }

  function buildPlaceSpokenBotOnly(game, botMove, status, winner) {
    return buildPlaceSpoken(game, '', botMove, status, winner);
  }

  // buildXiaozhiGamePrompt routes conversation-mode comments to the right MCP summarize tool.
  function buildXiaozhiGamePrompt(gameID, facts, summary) {
    facts = facts == null ? '' : String(facts);
    summary = summary == null ? '' : String(summary);
    var gid = trimSpace(gameID).toLowerCase();
    if (gid === '') {
      var blob = (summary + ' ' + facts).toLowerCase();
      var has = function (s) { return blob.indexOf(s) >= 0; };
      if (has('cờ tướng') || has('xiangqi')) {
        gid = 'xiangqi';
      } else if (has('cờ vua') || (has('chess') && !has('chinese'))) {
        gid = 'chess';
      } else if (has('cờ caro') || has('gomoku') || has('caro')) {
        gid = 'caro';
      } else if (has('connect four') || has('connect4') || has('cờ thả')) {
        gid = 'connect4';
      } else if (has('reversi') || has('othello')) {
        gid = 'reversi';
      } else if (has('checkers') || has('cờ đam') || has('draughts')) {
        gid = 'checkers';
      } else if (has('cờ vây') || has(' go ') || blob.indexOf('go ') === 0 || has('go9') || has('weiqi')) {
        gid = 'go9';
      } else {
        gid = 'chess';
      }
    }

    var tool = 'self.' + gid + '.summarize';
    // chess/xiangqi keep historical tool names
    if (gid === 'chess') {
      tool = 'self.chess.summarize';
    }
    if (gid === 'xiangqi') {
      tool = 'self.xiangqi.summarize';
    }

    var labels = {
      'chess': 'cờ vua (chess)',
      'xiangqi': 'cờ tướng (xiangqi)',
      'caro': 'cờ caro / gomoku',
      'connect4': 'connect four / cờ thả cột',
      'reversi': 'reversi / othello',
      'checkers': 'cờ đam / checkers',
      'go9': 'cờ vây 9×9 (go)'
    };
    var label = hasOwn(labels, gid) ? labels[gid] : '';
    if (label === '') {
      label = gid;
    }

    var b = '';
    b += sprintf('Người chơi vừa đánh một nước %s trên bàn web Vector. ', label);
    b += sprintf('Hãy gọi %s rồi bình luận ngắn 1 hoặc 2 câu bằng tiếng Việt về nước đi và thế ván. Đừng dùng tool của game khác. Đừng hỏi lại.\n', tool);
    if (facts !== '') {
      b += 'Nước vừa rồi: ';
      b += facts;
      b += '\n';
    }
    if (summary !== '') {
      var s = summary;
      var rs = Array.from(s);
      if (rs.length > 400) {
        s = rs.slice(0, 400).join('') + '…';
      }
      b += 'Tóm tắt: ';
      b += s;
    }
    return trimSpace(b);
  }

  // ---------------------------------------------------------------------------
  // chess_speak.go — speak queue + transport (POST /api/game_speak)
  // ---------------------------------------------------------------------------

  var chessSpeakBusy = 0;
  var chessSpeakQueued = null;

  function queueChessSpeak(say, summary) {
    queueGameSpeak('', say, summary);
  }

  // queueGameSpeak is queueChessSpeak with explicit gameID.
  function queueGameSpeak(gameID, say, summary) {
    say = trimSpace(say);
    if (say === '' || !chessCommentEnabled()) {
      return;
    }
    var mode = getChessCommentMode();
    var lang = mode === chessModeGoogleVI ? chessGoogleTTSLang() : 'en';
    enqueueSpeak({
      text: say,
      lang: lang,
      mode: mode,
      game: gameID == null ? '' : String(gameID)
    });
  }

  // queueChessSpeakSayTextAlways forces SayText (e.g. exit game), even if
  // auto-comment is off.
  function queueChessSpeakSayTextAlways(say, gameID) {
    say = trimSpace(say);
    if (say === '') {
      return;
    }
    enqueueSpeak({
      text: say,
      lang: 'en',
      mode: chessModeSayText,
      game: gameID == null ? '' : String(gameID)
    });
  }

  function enqueueSpeak(payload) {
    chessSpeakQueued = payload;
    if (chessSpeakBusy) {
      return;
    }
    chessSpeakBusy = 1;
    drainChessSpeak();
  }

  async function drainChessSpeak() {
    try {
      for (;;) {
        var p = chessSpeakQueued;
        chessSpeakQueued = null;
        if (!p) {
          return;
        }
        try {
          await writeChessAnnounce(p);
        } catch (err) {
          console.warn('[WG] game_speak:', err);
        }
        await sleep(150);
      }
    } finally {
      chessSpeakBusy = 0;
    }
  }

  // The robot HTTP server treats Content-Length as characters, so the body
  // must be pure ASCII.
  function asciiJSON(obj) {
    return JSON.stringify(obj).replace(/[\u007f-\uffff]/g, function (ch) {
      return '\\u' + ('0000' + ch.charCodeAt(0).toString(16)).slice(-4);
    });
  }

  async function writeChessAnnounce(payload) {
    var detail = {
      text: payload.text,
      lang: payload.lang,
      mode: payload.mode,
      game: payload.game
    };
    try {
      if (typeof global.dispatchEvent === 'function' && typeof global.CustomEvent === 'function') {
        global.dispatchEvent(new global.CustomEvent('wg-speak', { detail: detail }));
      }
    } catch (e) {
      console.warn('[WG] wg-speak event:', e);
    }
    if (!_origFetch) {
      return;
    }
    var body = asciiJSON(detail);
    var ctrl = (typeof AbortController === 'function') ? new AbortController() : null;
    var timer = null;
    var timeout = new Promise(function (resolve) {
      timer = setTimeout(function () {
        if (ctrl) {
          try { ctrl.abort(); } catch (e) { /* ignore */ }
        }
        resolve(null);
      }, WG.speakTimeoutMs || 15000);
    });
    var req = _origFetch(WG.speakEndpoint || '/api/game_speak', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: body,
      signal: ctrl ? ctrl.signal : undefined
    }).then(function (res) {
      return res.text().catch(function () { return ''; });
    });
    try {
      await Promise.race([req, timeout]);
    } finally {
      clearTimeout(timer);
      req.catch(function () { /* already reported or aborted */ });
    }
  }

  function pieceName(p) {
    switch (trimSpace(p).toLowerCase()) {
      case 'k': return 'king';
      case 'q': return 'queen';
      case 'r': return 'rook';
      case 'b': return 'bishop';
      case 'n': return 'knight';
      case 'p': return 'pawn';
      default: return 'piece';
    }
  }

  function speakSq(sq) {
    sq = trimSpace(sq).toLowerCase();
    if (sq.length < 2) {
      return sq;
    }
    return sq[0] + ' ' + sq[1];
  }

  function speakMove(piece, uci) {
    uci = trimSpace(uci).toLowerCase();
    if (uci.length < 4) {
      return pieceName(piece);
    }
    var from = uci.slice(0, 2);
    var to = uci.slice(2, 4);
    var s = pieceName(piece) + ' from ' + speakSq(from) + ' to ' + speakSq(to);
    if (uci.length >= 5) {
      s += ', promote to ' + pieceName(uci[4]);
    }
    return s;
  }

  // buildChessComment returns English for SayText, with piece names.
  function buildChessComment(youMove, botMove, youPiece, botPiece, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push('You moved ' + speakMove(youPiece, youMove) + '.');
    }
    if (botMove) {
      parts.push('I moved ' + speakMove(botPiece, botMove) + '.');
    }
    switch (status) {
      case 'checkmate':
        if (winner === 'white') {
          parts.push('Checkmate. You win!');
        } else if (winner === 'black') {
          parts.push('Checkmate. I win!');
        } else {
          parts.push('Checkmate!');
        }
        break;
      case 'stalemate':
        parts.push('Stalemate. Draw.');
        break;
      case 'check':
        parts.push('Check!');
        break;
    }
    return trimSpace(parts.join(' '));
  }

  function pieceNameVI(p) {
    switch (trimSpace(p).toLowerCase()) {
      case 'k': return 'vua';
      case 'q': return 'hậu';
      case 'r': return 'xe';
      case 'b': return 'tượng';
      case 'n': return 'mã';
      case 'p': return 'tốt';
      default: return 'quân';
    }
  }

  function speakMoveVI(piece, uci) {
    uci = trimSpace(uci).toLowerCase();
    if (uci.length < 4) {
      return pieceNameVI(piece);
    }
    var from = uci.slice(0, 2);
    var to = uci.slice(2, 4);
    var s = pieceNameVI(piece) + ' từ ' + speakSq(from) + ' đến ' + speakSq(to);
    if (uci.length >= 5) {
      s += ', phong ' + pieceNameVI(uci[4]);
    }
    return s;
  }

  function buildChessCommentVI(youMove, botMove, youPiece, botPiece, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push('Bạn đi ' + speakMoveVI(youPiece, youMove) + '.');
    }
    if (botMove) {
      parts.push('Tôi đi ' + speakMoveVI(botPiece, botMove) + '.');
    }
    switch (status) {
      case 'checkmate':
        if (winner === 'white') {
          parts.push('Chiếu hết. Bạn thắng!');
        } else if (winner === 'black') {
          parts.push('Chiếu hết. Tôi thắng!');
        } else {
          parts.push('Chiếu hết!');
        }
        break;
      case 'stalemate':
        parts.push('Hết nước. Hòa.');
        break;
      case 'check':
        parts.push('Chiếu!');
        break;
    }
    return trimSpace(parts.join(' '));
  }

  // buildChessSpokenComment matches SayText (English) or the Google TTS language.
  function buildChessSpokenComment(youMove, botMove, youPiece, botPiece, status, winner) {
    var lang = gameSpeakLang();
    if (lang === 'en') {
      return buildChessComment(youMove, botMove, youPiece, botPiece, status, winner);
    }
    if (lang === 'vi') {
      return buildChessCommentVI(youMove, botMove, youPiece, botPiece, status, winner);
    }
    return buildChessCommentForLang(youMove, botMove, youPiece, botPiece, status, winner);
  }

  function speakGameName(id) {
    id = id == null ? '' : String(id);
    switch (trimSpace(id).toLowerCase()) {
      case 'chess': case 'co-vua': case 'cờ vua':
        return 'chess';
      case 'xiangqi': case 'co-tuong': case 'cờ tướng':
        return 'xiangqi';
      case 'caro': case 'gomoku': case 'cờ caro':
        return 'caro';
      case 'connect4': case 'connect-four': case 'cờ thả': case 'connect four':
        return 'connect four';
      case 'reversi': case 'othello':
        return 'reversi';
      case 'checkers': case 'draughts': case 'cờ đam':
        return 'checkers';
      case 'go9': case 'go': case 'cờ vây': case 'weiqi':
        return 'go';
      default:
        if (id === '') {
          return 'game';
        }
        return id.toLowerCase();
    }
  }

  // ---------------------------------------------------------------------------
  // xiangqi_speak.go
  // ---------------------------------------------------------------------------

  function xiangqiPieceNameEN(p) {
    switch (trimSpace(p).toLowerCase()) {
      case 'k': return 'general';
      case 'a': return 'advisor';
      case 'e': return 'elephant';
      case 'h': return 'horse';
      case 'r': return 'chariot';
      case 'c': return 'cannon';
      case 'p': return 'soldier';
      default: return 'piece';
    }
  }

  function xiangqiPieceNameVI(p) {
    switch (trimSpace(p).toLowerCase()) {
      case 'k': return 'tướng';
      case 'a': return 'sĩ';
      case 'e': return 'tượng';
      case 'h': return 'mã';
      case 'r': return 'xe';
      case 'c': return 'pháo';
      case 'p': return 'tốt';
      default: return 'quân cờ';
    }
  }

  function xiangqiSpeakSq(sq) {
    sq = trimSpace(sq).toLowerCase();
    if (sq.length < 2) {
      return sq;
    }
    return sq[0] + ' ' + sq[1];
  }

  function xiangqiSpeakMoveEN(piece, uci) {
    uci = trimSpace(uci).toLowerCase();
    if (uci.length < 4) {
      return xiangqiPieceNameEN(piece);
    }
    return xiangqiPieceNameEN(piece) + ' from ' + xiangqiSpeakSq(uci.slice(0, 2)) + ' to ' + xiangqiSpeakSq(uci.slice(2, 4));
  }

  function xiangqiSpeakMoveVI(piece, uci) {
    uci = trimSpace(uci).toLowerCase();
    if (uci.length < 4) {
      return xiangqiPieceNameVI(piece);
    }
    return xiangqiPieceNameVI(piece) + ' từ ' + xiangqiSpeakSq(uci.slice(0, 2)) + ' đến ' + xiangqiSpeakSq(uci.slice(2, 4));
  }

  // buildXiangqiCommentEN returns English for SayText, e.g.
  // "You moved chariot from a 0 to a 1. I moved horse from h 9 to g 7."
  function buildXiangqiCommentEN(youMove, botMove, youPiece, botPiece, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push('You moved ' + xiangqiSpeakMoveEN(youPiece, youMove) + '.');
    }
    if (botMove) {
      parts.push('I moved ' + xiangqiSpeakMoveEN(botPiece, botMove) + '.');
    }
    switch (status) {
      case 'checkmate':
        switch (winner) {
          case 'red':
            parts.push('Checkmate. You win!');
            break;
          case 'black':
            parts.push('Checkmate. I win!');
            break;
          default:
            parts.push('Checkmate!');
        }
        break;
      case 'stalemate':
        parts.push('Stalemate. Draw.');
        break;
      case 'check':
        parts.push('Check!');
        break;
    }
    return trimSpace(parts.join(' '));
  }

  // buildXiangqiCommentVI returns Vietnamese, e.g.
  // "Bạn đi xe từ a 0 đến a 1. Tôi đi mã từ h 9 đến g 7."
  function buildXiangqiCommentVI(youMove, botMove, youPiece, botPiece, status, winner) {
    var parts = [];
    if (youMove) {
      parts.push('Bạn đi ' + xiangqiSpeakMoveVI(youPiece, youMove) + '.');
    }
    if (botMove) {
      parts.push('Tôi đi ' + xiangqiSpeakMoveVI(botPiece, botMove) + '.');
    }
    switch (status) {
      case 'checkmate':
        switch (winner) {
          case 'red':
            parts.push('Chiếu bí. Bạn thắng!');
            break;
          case 'black':
            parts.push('Chiếu bí. Tôi thắng!');
            break;
          default:
            parts.push('Chiếu bí!');
        }
        break;
      case 'stalemate':
        parts.push('Hết nước đi. Hòa.');
        break;
      case 'check':
        parts.push('Chiếu!');
        break;
    }
    return trimSpace(parts.join(' '));
  }

  // buildXiangqiSpokenComment matches SayText (English) or the Google TTS language.
  function buildXiangqiSpokenComment(youMove, botMove, youPiece, botPiece, status, winner) {
    var lang = gameSpeakLang();
    if (lang === 'en') {
      return buildXiangqiCommentEN(youMove, botMove, youPiece, botPiece, status, winner);
    }
    if (lang === 'vi') {
      return buildXiangqiCommentVI(youMove, botMove, youPiece, botPiece, status, winner);
    }
    return buildXiangqiCommentForLang(youMove, botMove, youPiece, botPiece, status, winner);
  }

  function xiangqiNewGameSpeak() {
    return speakNew('New game. You are red. Your move.', 'Ván mới. Bạn cầm Đỏ. Đến lượt bạn.')[0];
  }

  // ---------------------------------------------------------------------------
  // minigame_util.go
  // ---------------------------------------------------------------------------

  function MiniCommon() {
    this.status = '';       // playing | win | lose | draw
    this.winner = '';       // human | bot | ""
    this.message = '';
    this.lastMove = '';
    this.history = [];
    this.difficulty = '';
    this.humanTurn = false;
    this.botThinking = false;
    this.thinkGen = 0;
    this.moves = 0;
  }
  MiniCommon.prototype.baseSnap = function (game, uiMode, extra) {
    var turn = 'bot';
    if (this.humanTurn) {
      turn = 'human';
    }
    var out = {
      turn: turn,
      status: this.status,
      winner: this.winner,
      lastMove: this.lastMove,
      history: (this.history || []).slice(),
      message: this.message,
      difficulty: normalizeGameDifficulty(this.difficulty),
      botThinking: this.botThinking,
      game: game,
      uiMode: uiMode,
      placeMode: uiMode === 'place' || uiMode === 'tictactoe' || uiMode === 'mines' || uiMode === 'memory' || uiMode === 'battleship'
    };
    if (extra) {
      for (var k in extra) {
        if (hasOwn(extra, k)) out[k] = extra[k];
      }
    }
    return out;
  };

  function viOrEN(vi, en) {
    var s = localizeSpeak(en, vi);
    return [s, s];
  }

  function miniSqName(file, rank) {
    return String.fromCharCode(97 + file) + sprintf('%d', rank);
  }

  function miniParseSq(s) {
    s = trimSpace(s).toLowerCase();
    if (s.length < 2 || s[0] < 'a' || s[0] > 'z') {
      return { file: 0, rank: 0, ok: false };
    }
    var file = s.charCodeAt(0) - 97;
    var rank = 0;
    for (var i = 1; i < s.length; i++) {
      if (s[i] < '0' || s[i] > '9') {
        return { file: 0, rank: 0, ok: false };
      }
      rank = rank * 10 + (s.charCodeAt(i) - 48);
    }
    return { file: file, rank: rank, ok: true };
  }

  // ---------------------------------------------------------------------------
  // board_game_http.go — registry + /api/mods router
  // ---------------------------------------------------------------------------

  var registry = {};

  function attachGameCaps(st, difficulty) {
    if (st == null) {
      return;
    }
    st.comment = chessCommentEnabled();
    st.commentMode = getChessCommentMode();
    st.xiaozhiAvailable = chessXiaozhiAvailable();
    st.googleVIAvailable = chessGoogleVIAvailable();
    st.googleTTSLang = chessGoogleTTSLang();
    st.difficulty = difficulty;
  }

  function registerBoardGame(spec) {
    if (!spec || !spec.name) {
      throw new Error('registerBoardGame: spec.name required');
    }
    registry[spec.name] = spec;
    return spec;
  }

  function newGenericBoardMod(apiName, exitID, desc, summary, snapshot, reset, setDiff, getDiff, play, legal, newSpeak) {
    return registerBoardGame({
      name: apiName, exitDefault: exitID, desc: desc,
      summary: summary, snapshot: snapshot, reset: reset,
      setDifficulty: setDiff, getDifficulty: getDiff,
      playUCI: play, legalUCIs: legal, newGameSpeak: newSpeak
    });
  }

  function lookupGame(name) {
    if (hasOwn(registry, name)) return registry[name];
    var lower = String(name).toLowerCase();
    for (var k in registry) {
      if (hasOwn(registry, k) && k.toLowerCase() === lower) return registry[k];
    }
    return null;
  }

  function jsonResponse(status, obj) {
    return new Response(JSON.stringify(obj === undefined ? null : obj), {
      status: status,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  function httpError(status, msg) {
    return { status: status, body: { status: 'error', message: msg } };
  }

  // Serve implements boardGameHTTP.Serve. req = {path, method, form: {get(k)}, json}.
  async function serveBoardGame(h, req) {
    var path = req.path;
    var form = req.form;
    var getDiff = async function () { return h.getDifficulty ? await h.getDifficulty() : diffMedium; };
    var v;
    switch (path) {
      case 'state':
      case 'summary': {
        if (path === 'summary') {
          return { status: 200, body: { text: h.summary ? toStr(await h.summary()) : '' } };
        }
        var st = await h.snapshot();
        attachGameCaps(st, await getDiff());
        return { status: 200, body: st };
      }
      case 'comment':
        v = form.get('enable');
        if (v !== '') {
          var on = v === '1' || v.toLowerCase() === 'true' || v === 'on';
          setChessCommentEnabled(on);
        }
        return {
          status: 200,
          body: {
            comment: chessCommentEnabled(),
            commentMode: getChessCommentMode(),
            xiaozhiAvailable: chessXiaozhiAvailable(),
            googleVIAvailable: chessGoogleVIAvailable(),
            googleTTSLang: chessGoogleTTSLang(),
            difficulty: await getDiff()
          }
        };
      case 'comment_mode':
        v = form.get('mode');
        if (v !== '') {
          setChessCommentMode(v);
        }
        v = form.get('lang');
        if (v !== '') {
          setChessGoogleTTSLang(v);
        }
        return {
          status: 200,
          body: {
            commentMode: getChessCommentMode(),
            xiaozhiAvailable: chessXiaozhiAvailable(),
            googleVIAvailable: chessGoogleVIAvailable(),
            googleTTSLang: chessGoogleTTSLang(),
            comment: chessCommentEnabled(),
            difficulty: await getDiff()
          }
        };
      case 'difficulty': {
        v = form.get('level');
        if (v !== '' && h.setDifficulty) {
          await h.setDifficulty(v);
        }
        var d = await getDiff();
        return { status: 200, body: { difficulty: d, label: difficultyLabelVI(d) } };
      }
      case 'new': {
        if (req.method !== 'POST' && req.method !== 'GET') {
          return httpError(500, 'POST required');
        }
        v = form.get('level');
        if (v !== '' && h.setDifficulty) {
          await h.setDifficulty(v);
        }
        var st2 = await h.reset();
        attachGameCaps(st2, await getDiff());
        var after = null;
        if (h.newGameSpeak) {
          after = async function () {
            var r = await h.newGameSpeak();
            var say = '';
            var sum = '';
            if (Array.isArray(r)) {
              say = toStr(r[0]);
              sum = toStr(r[1]);
            } else if (typeof r === 'string') {
              say = r;
            }
            if (say !== '') {
              queueChessSpeak(say, sum);
            }
          };
        }
        return { status: 200, body: st2, after: after };
      }
      case 'move': {
        if (req.method !== 'POST') {
          return httpError(500, 'POST required');
        }
        var uci = form.get('uci');
        if (uci === '' && req.json && typeof req.json === 'object' && req.json.uci != null) {
          uci = toStr(req.json.uci);
        }
        var st3;
        try {
          st3 = await h.playUCI(uci);
        } catch (err) {
          return { status: 400, body: { status: 'error', message: (err && err.message !== undefined) ? String(err.message) : toStr(err) } };
        }
        attachGameCaps(st3, await getDiff());
        return { status: 200, body: st3 };
      }
      case 'legal': {
        var moves = h.legalUCIs ? await h.legalUCIs() : null;
        return { status: 200, body: { moves: moves === undefined ? null : moves } };
      }
      case 'exit': {
        var name = trimSpace(form.get('game'));
        if (name === '') {
          name = h.exitDefault || '';
        }
        var msg = 'Exiting ' + speakGameName(name) + '.';
        queueChessSpeakSayTextAlways(msg, name);
        return { status: 200, body: { status: 'ok', message: msg, game: name } };
      }
      default:
        return httpError(404, 'not found');
    }
  }

  // boardGameHTTP returns an object with serve(req) bound to spec (Go type boardGameHTTP).
  function boardGameHTTP(spec) {
    return {
      spec: spec,
      serve: function (req) { return serveBoardGame(spec, req); }
    };
  }

  // --- request parsing -------------------------------------------------------

  function makeForm() {
    var vals = {};
    return {
      vals: vals,
      add: function (k, v) {
        if (!hasOwn(vals, k)) vals[k] = [];
        vals[k].push(v == null ? '' : String(v));
      },
      // Go r.FormValue: first value, body values take precedence over query.
      get: function (k) {
        return hasOwn(vals, k) && vals[k].length ? vals[k][0] : '';
      }
    };
  }

  function addSearchParams(form, sp) {
    sp.forEach(function (v, k) { form.add(k, v); });
  }

  async function readBody(input, init) {
    // Returns {params: URLSearchParams|FormData|null, json: any}
    var body = init && hasOwn(init, 'body') ? init.body : undefined;
    var ctype = '';
    var hdrs = init && init.headers;
    if (hdrs) {
      try {
        ctype = new Headers(hdrs).get('Content-Type') || '';
      } catch (e) { ctype = ''; }
    }
    if (body === undefined && typeof Request !== 'undefined' && input instanceof Request) {
      if (!ctype) ctype = input.headers.get('Content-Type') || '';
      var m = (input.method || 'GET').toUpperCase();
      if (m !== 'GET' && m !== 'HEAD') {
        try {
          if (/multipart\/form-data/i.test(ctype)) {
            body = await input.clone().formData();
          } else {
            body = await input.clone().text();
          }
        } catch (e) { body = undefined; }
      }
    }
    if (body == null) return { params: null, json: null };
    if (typeof FormData !== 'undefined' && body instanceof FormData) return { params: body, json: null };
    if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) return { params: body, json: null };
    var text = null;
    if (typeof body === 'string') {
      text = body;
    } else if (typeof Blob !== 'undefined' && body instanceof Blob) {
      text = await body.text();
    } else if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
      text = new TextDecoder().decode(body);
    } else {
      text = String(body);
    }
    var t = text.trim();
    if (/json/i.test(ctype) || t.charAt(0) === '{' || t.charAt(0) === '[') {
      try {
        return { params: null, json: JSON.parse(t) };
      } catch (e) {
        return { params: null, json: null };
      }
    }
    return { params: new URLSearchParams(text), json: null };
  }

  async function handleModsRequest(input, init, url) {
    var rest = url.pathname.slice('/api/mods/'.length);
    var slash = rest.indexOf('/');
    var name = slash >= 0 ? rest.slice(0, slash) : rest;
    try { name = decodeURIComponent(name); } catch (e) { /* keep raw */ }
    var h = lookupGame(name);
    if (!h || slash < 0) {
      return jsonResponse(404, { status: 'error', message: 'not found' });
    }
    var path = rest.slice(slash + 1);
    var method = ((init && init.method) || (typeof Request !== 'undefined' && input instanceof Request ? input.method : '') || 'GET').toUpperCase();

    var form = makeForm();
    var parsed = { params: null, json: null };
    try {
      parsed = await readBody(input, init);
    } catch (e) { /* ignore body errors */ }
    if (parsed.params && method !== 'GET' && method !== 'HEAD') {
      parsed.params.forEach(function (v, k) {
        form.add(k, typeof v === 'string' ? v : (v && v.name) || '');
      });
    }
    addSearchParams(form, url.searchParams);

    var res;
    try {
      res = await serveBoardGame(h, { path: path, method: method, form: form, json: parsed.json });
    } catch (err) {
      console.warn('[WG] ' + name + '/' + path + ':', err);
      return jsonResponse(500, { status: 'error', message: (err && err.message) || String(err) });
    }
    var response = jsonResponse(res.status, res.body);
    if (res.after) {
      Promise.resolve().then(res.after).catch(function (e) { console.warn('[WG] newGameSpeak:', e); });
    }
    if (res.status === 200) {
      if (path === 'exit') {
        pushGameContext(null);
      } else if (path === 'new' || path === 'move' || path === 'state') {
        pushGameContext(h);
        // Bot đi sau vài giây (async) — đẩy lại để Xiaozhi thấy cả nước của bot.
        if (path === 'move') {
          setTimeout(function () { pushGameContext(h); }, 2600);
          setTimeout(function () { pushGameContext(h); }, 5200);
        }
      }
    }
    return response;
  }

  // Xiaozhi trên robot đọc thế ván qua MCP self.*.summarize → robot cần summary mới nhất.
  var gameContextEndpoint = '/api/game_context';
  var ctxLastKey = null;
  var ctxPending = undefined;
  var ctxTimer = null;

  function pushGameContext(h) {
    ctxPending = h;
    if (ctxTimer) return;
    ctxTimer = setTimeout(flushGameContext, 300);
  }

  async function flushGameContext() {
    ctxTimer = null;
    var h = ctxPending;
    ctxPending = undefined;
    if (h === undefined || !_origFetch) return;
    var body = { game: '', exitId: '', summary: '' };
    if (h) {
      var text = '';
      try {
        text = h.summary ? toStr(await h.summary()) : '';
      } catch (e) {
        return;
      }
      body = { game: h.name || '', exitId: h.exitDefault || '', summary: text };
    }
    var key = body.game + '\n' + body.summary;
    if (key === ctxLastKey) return;
    ctxLastKey = key;
    try {
      await _origFetch(gameContextEndpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: asciiJSON(body)
      });
    } catch (e) {
      ctxLastKey = null;
    }
  }

  function requestURL(input) {
    var raw;
    if (typeof Request !== 'undefined' && input instanceof Request) raw = input.url;
    else if (input && typeof input === 'object' && typeof input.href === 'string') raw = input.href;
    else raw = String(input);
    try {
      return new URL(raw, global.location ? global.location.href : 'http://localhost/');
    } catch (e) {
      return null;
    }
  }

  function wgFetch(input, init) {
    var url = requestURL(input);
    if (url && url.pathname.indexOf('/api/mods/') === 0) {
      return handleModsRequest(input, init, url);
    }
    if (!_origFetch) {
      return Promise.reject(new TypeError('fetch unavailable'));
    }
    return _origFetch(input, init);
  }

  if (_origFetch && !global.__wgFetchWrapped) {
    global.fetch = wgFetch;
    global.__wgFetchWrapped = true;
  }

  // ---------------------------------------------------------------------------
  // Exports
  // ---------------------------------------------------------------------------

  var exportsObj = {
    // runtime helpers
    sleep: sleep,
    sprintf: sprintf,
    randInt: randInt,
    randFloat: randFloat,
    shuffle: shuffle,
    toStr: toStr,
    originalFetch: _origFetch,
    speakEndpoint: '/api/game_speak',
    speakTimeoutMs: 15000,

    // caro_engine.go caroCell
    caroEmpty: 0,
    caroX: 1,
    caroO: 2,

    // game_difficulty.go
    diffEasy: diffEasy,
    diffMedium: diffMedium,
    diffHard: diffHard,
    normalizeGameDifficulty: normalizeGameDifficulty,
    difficultyLabelVI: difficultyLabelVI,

    // game_speak_i18n.go
    chessSpeakLangOverride: '',
    gameSpeakLang: gameSpeakLang,
    L: L,
    localizeSpeak: localizeSpeak,
    speakNew: speakNew,
    speakf: speakf,
    wordL: wordL,
    phraseByEN: phraseByEN,
    phraseYouMoved: phraseYouMoved,
    phraseIMoved: phraseIMoved,
    phraseMoveFromTo: phraseMoveFromTo,
    phrasePromote: phrasePromote,
    phraseYouPlayed: phraseYouPlayed,
    phraseIPlayed: phraseIPlayed,
    phraseYouWin: phraseYouWin,
    phraseIWin: phraseIWin,
    phraseGameOver: phraseGameOver,
    phraseDraw: phraseDraw,
    phraseYourTurn: phraseYourTurn,
    phraseCheck: phraseCheck,
    phraseCheckmate: phraseCheckmate,
    phraseCheckmateXQ: phraseCheckmateXQ,
    phraseStalemate: phraseStalemate,
    phraseStalemateXQ: phraseStalemateXQ,
    chessPieceL: chessPieceL,
    xiangqiPieceL: xiangqiPieceL,
    unoColorL: unoColorL,
    unoSpecialL: unoSpecialL,
    bjRankL: bjRankL,
    pokerHandL: pokerHandL,
    chessPieceSpoken: chessPieceSpoken,
    xiangqiPieceSpoken: xiangqiPieceSpoken,
    speakMoveLang: speakMoveLang,
    xiangqiSpeakMoveLang: xiangqiSpeakMoveLang,
    joinSpeak: joinSpeak,
    appendMate: appendMate,
    buildChessCommentForLang: buildChessCommentForLang,
    buildXiangqiCommentForLang: buildXiangqiCommentForLang,
    buildPlaceCommentForLang: buildPlaceCommentForLang,
    unoColorSpoken: unoColorSpoken,
    unoSpeakCardLang: unoSpeakCardLang,
    bjCardRank: bjCardRank,
    bjRankSpoken: bjRankSpoken,
    pokerHandSpoken: pokerHandSpoken,

    // board_game_speak.go
    buildPlaceCommentEN: buildPlaceCommentEN,
    buildPlaceCommentVI: buildPlaceCommentVI,
    buildPlaceSpoken: buildPlaceSpoken,
    buildPlaceSpokenHumanOnly: buildPlaceSpokenHumanOnly,
    buildPlaceSpokenBotOnly: buildPlaceSpokenBotOnly,
    buildXiaozhiGamePrompt: buildXiaozhiGamePrompt,

    // chess_speak.go
    chessAnnouncePath: chessAnnouncePath,
    chessModeSayText: chessModeSayText,
    chessModeXiaozhi: chessModeXiaozhi,
    chessModeGoogleVI: chessModeGoogleVI,
    chessSpeakMu: null,
    chessCommentEnabled: chessCommentEnabled,
    setChessCommentEnabled: setChessCommentEnabled,
    chessXiaozhiAvailable: chessXiaozhiAvailable,
    chessGoogleVIAvailable: chessGoogleVIAvailable,
    chessGoogleTTSLang: chessGoogleTTSLang,
    chessPreferVIText: chessPreferVIText,
    normalizeGoogleTTSLang: normalizeGoogleTTSLang,
    setChessGoogleTTSLang: setChessGoogleTTSLang,
    getChessCommentMode: getChessCommentMode,
    setChessCommentMode: setChessCommentMode,
    queueChessSpeak: queueChessSpeak,
    queueGameSpeak: queueGameSpeak,
    queueChessSpeakSayTextAlways: queueChessSpeakSayTextAlways,
    drainChessSpeak: drainChessSpeak,
    writeChessAnnounce: writeChessAnnounce,
    asciiJSON: asciiJSON,
    pieceName: pieceName,
    speakSq: speakSq,
    speakMove: speakMove,
    buildChessComment: buildChessComment,
    pieceNameVI: pieceNameVI,
    speakMoveVI: speakMoveVI,
    buildChessCommentVI: buildChessCommentVI,
    buildChessSpokenComment: buildChessSpokenComment,
    speakGameName: speakGameName,

    // xiangqi_speak.go
    xiangqiPieceNameEN: xiangqiPieceNameEN,
    xiangqiPieceNameVI: xiangqiPieceNameVI,
    xiangqiSpeakSq: xiangqiSpeakSq,
    xiangqiSpeakMoveEN: xiangqiSpeakMoveEN,
    xiangqiSpeakMoveVI: xiangqiSpeakMoveVI,
    buildXiangqiCommentEN: buildXiangqiCommentEN,
    buildXiangqiCommentVI: buildXiangqiCommentVI,
    buildXiangqiSpokenComment: buildXiangqiSpokenComment,
    xiangqiNewGameSpeak: xiangqiNewGameSpeak,

    // minigame_util.go
    MiniCommon: MiniCommon,
    miniCommon: MiniCommon,
    viOrEN: viOrEN,
    miniSqName: miniSqName,
    miniParseSq: miniParseSq,
    newGenericBoardMod: newGenericBoardMod,

    // board_game_http.go
    attachGameCaps: attachGameCaps,
    boardGameHTTP: boardGameHTTP,
    serveBoardGame: serveBoardGame,
    registerBoardGame: registerBoardGame,
    registry: registry,
    handleModsRequest: handleModsRequest,
    jsonResponse: jsonResponse,
    fetch: wgFetch
  };

  for (var key in exportsObj) {
    if (hasOwn(exportsObj, key)) WG[key] = exportsObj[key];
  }

  // Package-level mutable state (read-only views; mutate via the setters).
  Object.defineProperty(WG, 'chessCommentOn', { get: function () { return chessCommentOn; }, enumerable: true, configurable: true });
  Object.defineProperty(WG, 'chessCommentMode', { get: function () { return chessCommentMode; }, enumerable: true, configurable: true });
  Object.defineProperty(WG, 'chessSpeakBusy', { get: function () { return chessSpeakBusy; }, enumerable: true, configurable: true });
  Object.defineProperty(WG, 'chessSpeakQueued', { get: function () { return chessSpeakQueued; }, enumerable: true, configurable: true });
})(typeof window !== 'undefined' ? window : globalThis);
