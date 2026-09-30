/* Mini Self-Control i18n — vi | en | zh | it (theo wire-os wired) */
(function (global) {
  'use strict';
  var SUPPORTED = ['vi', 'en', 'zh', 'it'];
  var STORAGE_KEY = 'mini.ui.lang';
  var STRINGS = {
    vi: {
      'doc.title': 'Mini Self Control',
      'header.title': 'Cài đặt robot',
      'lang.label': 'Ngôn ngữ',
      'nav.botsettings': 'Cài đặt bot',
      'nav.live': 'Live',
      'nav.actions': 'Hành động',
      'nav.express': 'Biểu cảm',
      'bot.h2': 'Cài đặt bot',
      'bot.intro': 'Chọn một mục bên dưới.',
      'bot.back': '← Quay lại',
      'bot.student': 'Học viên',
      'bot.course': 'Cấp độ học',
      'bot.mac': 'MAC / Device-Id',
      'bot.voice': 'Giọng',
      'bot.unit': 'Unit',
      'bot.music': 'Server nhạc',
      'bot.save': 'Lưu cấu hình',
      'bot.qr': 'Tắt mã QR',
      'live.h2': 'Điều khiển',
      'live.tunnel': 'Tunnel relay (nâng cao)',
      'ctrl.help1': 'Bấm Chiếm quyền trước — lúc đó mới lái / micro được.',
      'ctrl.help2': 'Dùng pad / camera khi đang chiếm quyền. Bật Micro để nói qua loa robot; Nghe robot để nghe mic robot.',
      'ctrl.help3': 'Xong thì tắt micro và bấm Nhả quyền.',
      'ctrl.help4': 'Muốn điều khiển khác mạng: bật Ngoài mạng → Copy link trycloudflare.',
      'ctrl.remote_warn': 'Link ngoài mạng chỉ tắt khi bạn tắt — ai có link đều điều khiển được.',
      'ctrl.assume': 'Chiếm quyền',
      'ctrl.release': 'Nhả quyền',
      'ctrl.remote': 'Ngoài mạng',
      'ctrl.remote_sub': 'Tạo link HTTPS Cloudflare (giống wired) — mở thẳng từ 4G, không hỏi IP.',
      'ctrl.remote_off': 'Tắt',
      'ctrl.remote_on': 'Bật link',
      'ctrl.remote_link': 'Link điều khiển',
      'ctrl.remote_copy': 'Copy',
      'ctrl.cam_off': 'Camera tắt — bật switch bên dưới hoặc Chiếm quyền.',
      'ctrl.mic': 'Micro',
      'ctrl.listen': 'Nghe robot',
      'ctrl.lift_up': 'Giơ tay',
      'ctrl.fwd': 'Tiến',
      'ctrl.head_up': 'Ngẩng',
      'ctrl.left': 'Trái',
      'ctrl.stop': 'Dừng',
      'ctrl.right': 'Phải',
      'ctrl.lift_dn': 'Vẫy tay',
      'ctrl.back': 'Lùi',
      'ctrl.head_dn': 'Lắc đầu',
      'ctrl.pad_hint': 'Giữ để chạy — giữa = dừng hết.',
      'actions.h2': 'Hành động & bài múa',
      'express.h2': 'Biểu cảm mắt',
      'common.howto': 'Cách dùng',
      'common.stop': 'Dừng',
      'lesson.prefix': 'Đang học:'
    },
    en: {
      'doc.title': 'Mini Self Control',
      'header.title': 'Robot settings',
      'lang.label': 'Language',
      'nav.botsettings': 'Bot settings',
      'nav.live': 'Live',
      'nav.actions': 'Actions',
      'nav.express': 'Expressions',
      'bot.h2': 'Bot settings',
      'bot.intro': 'Pick a section below.',
      'bot.back': '← Back',
      'bot.student': 'Student',
      'bot.course': 'Course level',
      'bot.mac': 'MAC / Device-Id',
      'bot.voice': 'Voice',
      'bot.unit': 'Unit',
      'bot.music': 'Music server',
      'bot.save': 'Save settings',
      'bot.qr': 'Hide QR',
      'live.h2': 'Control',
      'live.tunnel': 'Relay tunnel (advanced)',
      'ctrl.help1': 'Tap Assume first — then drive / mic work.',
      'ctrl.help2': 'Use pad / camera while assumed. Mic speaks via robot; Listen hears robot mic.',
      'ctrl.help3': 'When done, turn off mic and Release.',
      'ctrl.help4': 'Off-LAN control: enable Remote → Copy HTTPS link.',
      'ctrl.remote_warn': 'Remote link stays until you turn it off — anyone with the link can control.',
      'ctrl.assume': 'Assume',
      'ctrl.release': 'Release',
      'ctrl.remote': 'Remote',
      'ctrl.remote_sub': 'Create an HTTPS link to control from another network. Stays until you stop it.',
      'ctrl.remote_off': 'Off',
      'ctrl.remote_on': 'Enable link',
      'ctrl.remote_link': 'Control link',
      'ctrl.remote_copy': 'Copy',
      'ctrl.cam_off': 'Camera off — enable the switch below or Assume.',
      'ctrl.mic': 'Mic',
      'ctrl.listen': 'Listen',
      'ctrl.lift_up': 'Hands up',
      'ctrl.fwd': 'Forward',
      'ctrl.head_up': 'Head up',
      'ctrl.left': 'Left',
      'ctrl.stop': 'Stop',
      'ctrl.right': 'Right',
      'ctrl.lift_dn': 'Wave',
      'ctrl.back': 'Back',
      'ctrl.head_dn': 'Shake head',
      'ctrl.pad_hint': 'Hold to move — center stops all.',
      'actions.h2': 'Actions & dances',
      'express.h2': 'Eye expressions',
      'common.howto': 'How to use',
      'common.stop': 'Stop',
      'lesson.prefix': 'Learning:'
    },
    zh: {
      'doc.title': 'Mini Self Control',
      'header.title': '机器人设置',
      'lang.label': '语言',
      'nav.botsettings': '机器人设置',
      'nav.live': '直播',
      'nav.actions': '动作',
      'nav.express': '表情',
      'bot.h2': '机器人设置',
      'bot.intro': '请选择下方项目。',
      'bot.back': '← 返回',
      'bot.student': '学员',
      'bot.course': '课程级别',
      'bot.mac': 'MAC / Device-Id',
      'bot.voice': '语音',
      'bot.unit': '单元',
      'bot.music': '音乐服务器',
      'bot.save': '保存设置',
      'bot.qr': '关闭二维码',
      'live.h2': '控制',
      'live.tunnel': '中继隧道（高级）',
      'ctrl.help1': '先点击「占据」——之后才能驾驶/麦克风。',
      'ctrl.help2': '占据后使用方向键/摄像头。开麦克风可对机器人说话；听机器人可听机器人麦克风。',
      'ctrl.help3': '结束后关闭麦克风并点击「释放」。',
      'ctrl.help4': '跨网控制：开启「外网」→ 复制 HTTPS 链接。',
      'ctrl.remote_warn': '外网链接需手动关闭——持有链接者均可控制。',
      'ctrl.assume': '占据',
      'ctrl.release': '释放',
      'ctrl.remote': '外网',
      'ctrl.remote_sub': '创建 HTTPS 链接以便从其他网络控制。关闭前一直有效。',
      'ctrl.remote_off': '关',
      'ctrl.remote_on': '开链接',
      'ctrl.remote_link': '控制链接',
      'ctrl.remote_copy': '复制',
      'ctrl.cam_off': '摄像头关闭 — 打开下方开关或占据。',
      'ctrl.mic': '麦克风',
      'ctrl.listen': '听机器人',
      'ctrl.lift_up': '举手',
      'ctrl.fwd': '前进',
      'ctrl.head_up': '抬头',
      'ctrl.left': '左转',
      'ctrl.stop': '停止',
      'ctrl.right': '右转',
      'ctrl.lift_dn': '挥手',
      'ctrl.back': '后退',
      'ctrl.head_dn': '摇头',
      'ctrl.pad_hint': '按住移动 — 中间停止全部。',
      'actions.h2': '动作与舞蹈',
      'express.h2': '眼睛表情',
      'common.howto': '使用方法',
      'common.stop': '停止',
      'lesson.prefix': '正在学习：'
    },
    it: {
      'doc.title': 'Mini Self Control',
      'header.title': 'Impostazioni robot',
      'lang.label': 'Lingua',
      'nav.botsettings': 'Impostazioni bot',
      'nav.live': 'Live',
      'nav.actions': 'Azioni',
      'nav.express': 'Espressioni',
      'bot.h2': 'Impostazioni bot',
      'bot.intro': 'Scegli una voce sotto.',
      'bot.back': '← Indietro',
      'bot.student': 'Studente',
      'bot.course': 'Livello corso',
      'bot.mac': 'MAC / Device-Id',
      'bot.voice': 'Voce',
      'bot.unit': 'Unit',
      'bot.music': 'Server musica',
      'bot.save': 'Salva',
      'bot.qr': 'Nascondi QR',
      'live.h2': 'Controllo',
      'live.tunnel': 'Tunnel relay (avanzato)',
      'ctrl.help1': 'Tocca Assumi prima — poi guida / microfono.',
      'ctrl.help2': 'Usa pad / camera quando assunti. Micro parla dal robot; Ascolta sente il mic robot.',
      'ctrl.help3': 'A fine spegni micro e Rilascia.',
      'ctrl.help4': 'Fuori rete: attiva Remoto → Copia link HTTPS.',
      'ctrl.remote_warn': 'Il link remoto resta finché non lo spegni — chi ha il link può controllare.',
      'ctrl.assume': 'Assumi',
      'ctrl.release': 'Rilascia',
      'ctrl.remote': 'Remoto',
      'ctrl.remote_sub': 'Crea un link HTTPS per controllare da un’altra rete. Resta finché non lo spegni.',
      'ctrl.remote_off': 'Off',
      'ctrl.remote_on': 'Attiva link',
      'ctrl.remote_link': 'Link controllo',
      'ctrl.remote_copy': 'Copia',
      'ctrl.cam_off': 'Camera off — attiva lo switch sotto o Assumi.',
      'ctrl.mic': 'Micro',
      'ctrl.listen': 'Ascolta',
      'ctrl.lift_up': 'Mani su',
      'ctrl.fwd': 'Avanti',
      'ctrl.head_up': 'Testa su',
      'ctrl.left': 'Sinistra',
      'ctrl.stop': 'Stop',
      'ctrl.right': 'Destra',
      'ctrl.lift_dn': 'Saluta',
      'ctrl.back': 'Indietro',
      'ctrl.head_dn': 'Scuoti',
      'ctrl.pad_hint': 'Tieni premuto — centro ferma tutto.',
      'actions.h2': 'Azioni e balli',
      'express.h2': 'Espressioni occhi',
      'common.howto': 'Come usare',
      'common.stop': 'Stop',
      'lesson.prefix': 'In studio:'
    }
  };

  var lang = 'vi';

  function t(key) {
    var pack = STRINGS[lang] || STRINGS.vi;
    return pack[key] || (STRINGS.vi[key] || key);
  }

  function apply() {
    document.querySelectorAll('[data-i18n]').forEach(function (el) {
      var key = el.getAttribute('data-i18n');
      if (!key) return;
      var val = t(key);
      if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
        if (el.getAttribute('data-i18n-placeholder') === '1') el.placeholder = val;
        else el.value = val;
      } else {
        el.textContent = val;
      }
    });
    document.title = t('doc.title');
    var sel = document.getElementById('uiLangSelect');
    if (sel) sel.value = lang;
  }

  function setLang(next) {
    if (SUPPORTED.indexOf(next) < 0) next = 'vi';
    lang = next;
    try { localStorage.setItem(STORAGE_KEY, lang); } catch (e) {}
    apply();
  }

  function init() {
    try {
      var saved = localStorage.getItem(STORAGE_KEY);
      if (saved && SUPPORTED.indexOf(saved) >= 0) lang = saved;
    } catch (e) {}
    var sel = document.getElementById('uiLangSelect');
    if (sel) {
      sel.value = lang;
      sel.addEventListener('change', function () { setLang(sel.value); });
    }
    apply();
  }

  global.MiniI18n = { t: t, setLang: setLang, apply: apply, init: init, SUPPORTED: SUPPORTED };
})(window);
