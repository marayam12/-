/*
 * RenexaMC - rewarded ad viewer.
 * Looks like a store-style app ad: icon + name, developer / category / rating row,
 * video or picture, description and an "Install" button that opens the game on Google Play.
 *
 * Files (all in this folder):
 *   ad-viewer.js / ad-viewer.css  - this viewer
 *   ads.json                      - settings + the ads list (edit this to add / change ads)
 *   ads/<game>/icon.png, shot.png, video.mp4 - the pictures and the video of each ad
 *   omsdk-v1.js, omid-session-client-v1.js    - IAB Open Measurement SDK files (not used by the viewer)
 *
 * "source" in ads.json:
 *   "house"  - the ads from ads.json (default)
 *   "auto"   - real AdMob ads when the app can show one, otherwise ads.json
 *   "native" - only real AdMob ads
 */
(function () {
  'use strict';

  var BASE = 'https://appassets.androidplatform.net/assets/ad-viewer/';

  var DEFAULTS = {
    enabled: true, source: 'house', duration: 15, cooldownSeconds: 30,
    rewards: { coins: { amount: 10, dailyLimit: 10 }, gold: { amount: 1, dailyLimit: 5 } },
    ads: []
  };

  var STR = {
    ar: {
      watch: 'شاهد إعلان واكسب +{n} {u}', coins: 'كوينز', gold: 'جولد',
      login: 'سجّل الدخول أولاً', left: 'المتبقي اليوم: {l}/{m}', next: 'الإعلان التالي بعد {s} ث',
      limit: 'وصلت للحد اليومي من الإعلانات!', cooldown: 'انتظر {s} ث قبل الإعلان التالي',
      timer: 'المكافأة بعد {s} ث', done: 'اكتملت المشاهدة ✓', claim: 'استلم +{n} {u}', wait: 'شاهد الإعلان حتى النهاية',
      early: 'أغلقت الإعلان قبل النهاية - لا مكافأة', got: '+{n} {u}!',
      install: 'تثبيت', ad: 'إعلان', developer: 'المطوّر', category: 'الفئة', rating: 'التقييم',
      store: 'Google Play', pause: 'إيقاف', mute: 'كتم',
      notready: 'الإعلان غير جاهز، حاول بعد قليل', aderror: 'تعذّر عرض الإعلان، حاول لاحقًا', nonative: 'الإعلانات الحقيقية تعمل داخل التطبيق فقط',
      noads: 'لا توجد إعلانات الآن'
    },
    en: {
      watch: 'Watch an ad, earn +{n} {u}', coins: 'Coins', gold: 'Gold',
      login: 'Log in first', left: 'Left today: {l}/{m}', next: 'Next ad in {s}s',
      limit: 'You reached the daily ad limit!', cooldown: 'Wait {s}s before the next ad',
      timer: 'Reward in {s}s', done: 'Finished ✓', claim: 'Claim +{n} {u}', wait: 'Watch the ad to the end',
      early: 'You closed the ad early - no reward', got: '+{n} {u}!',
      install: 'Install', ad: 'Ad', developer: 'DEVELOPER', category: 'CATEGORY', rating: 'RATINGS',
      store: 'Google Play', pause: 'Pause', mute: 'Mute',
      notready: 'The ad is not ready, try again shortly', aderror: 'Could not show the ad, try later', nonative: 'Real ads only work inside the app',
      noads: 'No ads right now'
    }
  };

  var cfg = DEFAULTS, cfgPromise = null;
  var overlay = null, state = null, nativePending = null;
  var bag = [], lastAdIndex = -1;

  function bridge() { return window.__adBridge || null; }
  function lang() { var b = bridge(); return (b && b.lang() === 'ar') ? 'ar' : 'en'; }
  function t(key, vars) {
    var s = STR[lang()][key];
    if (vars) { for (var k in vars) { s = s.split('{' + k + '}').join(vars[k]); } }
    return s;
  }
  function unit(kind) { return t(kind === 'gold' ? 'gold' : 'coins'); }
  function pick(ad, key) { return (lang() === 'ar' && ad[key + 'Ar']) ? ad[key + 'Ar'] : ad[key]; }
  // the official picture of a YouTube video (a real picture of the game)
  function ytThumb(id) { return 'https://i.ytimg.com/vi/' + encodeURIComponent(id) + '/hqdefault.jpg'; }
  function fileUrl(p) { return /^(https?:|data:)/.test(p) ? p : BASE + p; }

  // ---------- settings ----------
  function loadConfig() {
    if (cfgPromise) return cfgPromise;
    cfgPromise = fetch(BASE + 'ads.json', { cache: 'no-store' })
      .then(function (r) { return r.ok ? r.json() : {}; })
      .catch(function () { return {}; })
      .then(function (j) {
        var src = (j.source === 'native' || j.source === 'auto') ? j.source : 'house';
        cfg = {
          enabled: j.enabled !== false,
          source: src,
          duration: Math.max(5, Number(j.duration) || DEFAULTS.duration),
          cooldownSeconds: j.cooldownSeconds >= 0 ? Number(j.cooldownSeconds) : DEFAULTS.cooldownSeconds,
          rewards: {
            coins: Object.assign({}, DEFAULTS.rewards.coins, (j.rewards || {}).coins),
            gold: Object.assign({}, DEFAULTS.rewards.gold, (j.rewards || {}).gold)
          },
          ads: (j.ads || []).filter(function (a) { return a && a.title; })
        };
        return cfg;
      });
    return cfgPromise;
  }

  // ---------- limits ----------
  function today() { var d = new Date(); return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate(); }
  function usedKey(kind) { return bridge().key('ads_' + kind + '_' + today()); }
  function lastKey() { return bridge().key('ads_last'); }
  function getInt(k) { try { return parseInt(localStorage.getItem(k) || '0', 10) || 0; } catch (e) { return 0; } }
  function setInt(k, v) { try { localStorage.setItem(k, String(v)); } catch (e) {} }
  function usedToday(kind) { return getInt(usedKey(kind)); }
  function cooldownLeft() {
    var left = Math.ceil((getInt(lastKey()) + cfg.cooldownSeconds * 1000 - Date.now()) / 1000);
    return left > 0 ? left : 0;
  }

  // ---------- cards on the Coins / Gold pages ----------
  function refresh() {
    var b = bridge();
    ['coins', 'gold'].forEach(function (kind) {
      var btn = document.getElementById('ad-btn-' + kind);
      if (!btn) return;
      var card = btn.closest ? btn.closest('.ad-card') : null;
      if (card) card.style.display = cfg.enabled ? '' : 'none';
      var rw = cfg.rewards[kind];
      var text = document.getElementById('ad-btn-text-' + kind);
      var info = document.getElementById('ad-info-' + kind);
      if (text) text.textContent = t('watch', { n: rw.amount, u: unit(kind) });
      var blocked = false, msg = '';
      if (!b || !b.isLoggedIn()) { msg = t('login'); }
      else {
        var left = rw.dailyLimit - usedToday(kind), wait = cooldownLeft();
        msg = t('left', { l: Math.max(0, left), m: rw.dailyLimit });
        if (left <= 0) blocked = true;
        else if (wait > 0) { blocked = true; msg += ' • ' + t('next', { s: wait }); }
      }
      if (info) info.textContent = msg;
      btn.classList.toggle('av-disabled', blocked);
    });
  }

  // ---------- reward ----------
  function grant(kind) {
    var b = bridge(), rw = cfg.rewards[kind];
    if (!b || !rw) return false;
    if (usedToday(kind) >= rw.dailyLimit) { b.notify(t('limit'), 'error'); return false; }
    setInt(usedKey(kind), usedToday(kind) + 1);
    setInt(lastKey(), Date.now());
    b.reward(kind, rw.amount);
    b.notify(t('got', { n: rw.amount, u: unit(kind) }), 'success');
    return true;
  }

  // ---------- the ad screen ----------
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }
  function $(id) { return document.getElementById(id); }

  function buildOverlay() {
    overlay = el('div', 'av-overlay');
    overlay.id = 'ad-viewer-overlay';
    overlay.setAttribute('role', 'dialog');
    var screen = el('div', 'av-screen');

    // No close/skip button on purpose: the ad cannot be dismissed before it finishes.
    var bar = el('div', 'av-progress'); var fill = el('i'); fill.id = 'av-progress-bar'; bar.appendChild(fill);
    var tag = el('div', 'av-adtag'); tag.id = 'av-adtag';
    var timer = el('div', 'av-timer'); timer.id = 'av-timer';

    var head = el('div', 'av-head'); head.id = 'av-head';
    var meta = el('div', 'av-meta'); meta.id = 'av-meta';
    var media = el('div', 'av-media'); media.id = 'av-media';
    var desc = el('p', 'av-desc'); desc.id = 'av-desc';
    var install = el('button', 'av-install'); install.id = 'av-install'; install.type = 'button';
    var claim = el('button', 'av-claim'); claim.id = 'av-claim'; claim.type = 'button';
    claim.addEventListener('click', claimReward);

    [bar, tag, timer, head, meta, media, desc, install, claim].forEach(function (n) { screen.appendChild(n); });
    overlay.appendChild(screen);
    document.body.appendChild(overlay);
  }

  function pickAd() {
    var list = cfg.ads;
    if (!list.length) return null;
    if (bag.length === 0) {                       // shuffle bag: no repeats until all were shown
      for (var n = 0; n < list.length; n++) bag.push(n);
      for (var i = bag.length - 1; i > 0; i--) {
        var j = Math.floor(Math.random() * (i + 1)); var tmp = bag[i]; bag[i] = bag[j]; bag[j] = tmp;
      }
      if (list.length > 1 && bag[bag.length - 1] === lastAdIndex) { var x = bag[0]; bag[0] = bag[bag.length - 1]; bag[bag.length - 1] = x; }
    }
    lastAdIndex = bag.pop();
    return list[lastAdIndex];
  }

  function openLink(url) {
    try {
      if (typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.openExternalUrl === 'function') { AndroidBridge.openExternalUrl(url); return; }
    } catch (e) {}
    window.open(url, '_blank');
  }
  function playUrl(ad) {
    if (ad.link) return ad.link;
    return ad['package'] ? 'https://play.google.com/store/apps/details?id=' + encodeURIComponent(ad['package']) : '';
  }

  function stars(r) {
    var n = Math.max(0, Math.min(5, Math.round(Number(r) || 0)));
    return new Array(n + 1).join('★') + new Array(6 - n).join('☆');
  }

  function renderHead(ad) {
    var head = $('av-head'); head.textContent = '';
    var icon;
    var first = ad.icon ? fileUrl(ad.icon) : (ad.youtube ? ytThumb(ad.youtube) : '');
    if (first) {
      icon = document.createElement('img'); icon.className = 'av-icon'; icon.alt = '';
      var triedYoutube = !ad.icon;                       // first source already is the youtube picture
      icon.onerror = function () {
        if (!triedYoutube && ad.youtube) { triedYoutube = true; icon.src = ytThumb(ad.youtube); return; }
        var f = el('div', 'av-icon av-icon-fallback', ad.emoji || ad.title.charAt(0));
        if (icon.parentNode) icon.parentNode.replaceChild(f, icon);
      };
      icon.src = first;
    } else {
      icon = el('div', 'av-icon av-icon-fallback', ad.emoji || ad.title.charAt(0));
    }
    var text = el('div', 'av-headtext');
    text.appendChild(el('div', 'av-name', pick(ad, 'title')));
    var tag = pick(ad, 'tagline');
    if (tag) text.appendChild(el('div', 'av-tagline', tag));
    text.appendChild(el('div', 'av-store', '▶ ' + t('store')));
    head.appendChild(icon); head.appendChild(text);
  }

  function renderMeta(ad) {
    var meta = $('av-meta'); meta.textContent = '';
    function col(label, value) {
      var c = el('div', 'av-col');
      c.appendChild(el('div', 'av-label', label));
      c.appendChild(el('div', 'av-value', value));
      meta.appendChild(c);
    }
    col(t('developer'), pick(ad, 'developer') || '-');
    col(t('category'), pick(ad, 'category') || '-');
    if (ad.rating) col(t('rating'), ad.rating + ' ' + stars(ad.rating));
  }

  function fallbackMedia(ad) {
    var m = $('av-media'); m.textContent = '';
    m.appendChild(el('div', 'av-media-fallback', ad.emoji || ad.title.charAt(0)));
  }

  function icon(cls, label, src) {
    var b = el('button', 'av-ibtn ' + cls); b.type = 'button'; b.setAttribute('aria-label', label);
    var img = document.createElement('img'); img.className = 'av-ibtn-img'; img.alt = '';
    img.src = BASE + 'icons/' + src; b.appendChild(img);
    return { btn: b, img: img };
  }

  function renderMedia(ad) {
    var m = $('av-media'); m.textContent = ''; m.style.backgroundImage = '';
    if (ad.video) {
      var v = document.createElement('video');
      // Sound plays from the very first moment - no need to tap an "unmute" button.
      // (Needs mediaPlaybackRequiresUserGesture=false in the app / autoplay-policy in the
      // PC build; outside of those, unmuted autoplay can be blocked, so the catch below
      // falls back to a muted autoplay rather than not playing at all.)
      v.className = 'av-video'; v.muted = false; v.loop = false; v.autoplay = true; v.playsInline = true;
      v.setAttribute('playsinline', ''); v.setAttribute('data-av', '');
      if (ad.image) v.poster = fileUrl(ad.image);
      v.onerror = function () { renderStill(ad); };
      // the ad lasts exactly as long as its video
      v.addEventListener('loadedmetadata', function () { syncDuration(v.duration); });
      v.src = fileUrl(ad.video);
      m.appendChild(v);

      var play = icon('av-mbtn-1', t('pause'), 'stop.png');
      play.btn.addEventListener('click', function () {
        if (v.paused) { v.play(); play.img.src = BASE + 'icons/stop.png'; }
        else { v.pause(); play.img.src = BASE + 'icons/play.png'; }
      });
      var vol = icon('av-mbtn-2', t('mute'), 'volume.png');
      vol.btn.addEventListener('click', function () {
        v.muted = !v.muted;
        vol.img.src = BASE + 'icons/' + (v.muted ? 'mute.png' : 'volume.png');
      });
      m.appendChild(play.btn); m.appendChild(vol.btn);

      try {
        var p = v.play();
        if (p && p.catch) p.catch(function () {
          // unmuted autoplay was blocked here - fall back to muted so the video still plays
          v.muted = true; vol.img.src = BASE + 'icons/mute.png';
          var p2 = v.play(); if (p2 && p2.catch) p2.catch(function () {});
        });
      } catch (e) {}
    } else if (ad.youtube) {
      var f = document.createElement('iframe');
      f.className = 'av-frame';
      f.setAttribute('allow', 'autoplay; encrypted-media');
      f.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-presentation');
      f.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
      m.style.backgroundImage = 'url(' + ytThumb(ad.youtube) + ')';   // the video's own picture shows while it loads
      m.style.backgroundSize = 'cover';
      m.style.backgroundPosition = 'center';
      f.src = 'https://www.youtube.com/embed/' + encodeURIComponent(ad.youtube) + '?autoplay=1&mute=1&controls=0&playsinline=1&rel=0&iv_load_policy=3&disablekb=1&loop=1&playlist=' + encodeURIComponent(ad.youtube);
      m.appendChild(f);
    } else {
      renderStill(ad);
    }
  }

  // makes the countdown as long as the video (rounded up to a whole second)
  function syncDuration(d) {
    if (!state || !isFinite(d) || d < 3) return;
    var total = Math.min(300, Math.ceil(d)) * 1000;
    var elapsed = state.total - state.remain;
    state.total = total;
    state.remain = Math.max(0, total - elapsed);
  }

  function renderStill(ad) {
    var m = $('av-media'); m.textContent = '';
    if (ad.image) {
      var img = document.createElement('img'); img.className = 'av-shot'; img.alt = '';
      img.onerror = function () { fallbackMedia(ad); };
      img.src = fileUrl(ad.image);
      m.appendChild(img);
    } else {
      fallbackMedia(ad);
    }
  }

  function show(kind, ad) {
    if (!overlay) buildOverlay();
    overlay.setAttribute('dir', lang() === 'ar' ? 'rtl' : 'ltr');
    var secs = Math.min(300, Math.max(5, Number(ad.duration) || cfg.duration));
    state = { kind: kind, total: secs * 1000, remain: secs * 1000, last: performance.now(), done: false, claimed: false, iv: null, url: playUrl(ad) };
    $('av-adtag').textContent = t('ad');
    renderHead(ad); renderMeta(ad); renderMedia(ad);
    $('av-desc').textContent = pick(ad, 'text') || '';
    var install = $('av-install');
    install.textContent = t('install');
    install.style.display = state.url ? '' : 'none';
    install.onclick = function () { if (state && state.url) openLink(state.url); };
    var claim = $('av-claim'); claim.disabled = true; claim.textContent = t('wait');
    $('av-progress-bar').style.width = '0%';
    overlay.classList.add('av-open');
    tick();
    state.iv = setInterval(tick, 250);
  }

  function tick() {
    if (!state) return;
    var now = performance.now();
    var delta = Math.min(now - state.last, 1000);       // a big jump cannot skip the ad
    state.last = now;
    if (!document.hidden && !state.done) state.remain -= delta;   // time stops while the app is hidden
    var remain = Math.max(0, state.remain);
    $('av-progress-bar').style.width = Math.min(100, (1 - remain / state.total) * 100) + '%';
    if (remain <= 0 && !state.done) {
      state.done = true;
      var c = $('av-claim'); c.disabled = false;
      c.textContent = t('claim', { n: cfg.rewards[state.kind].amount, u: unit(state.kind) });
    }
    $('av-timer').textContent = state.done ? t('done') : t('timer', { s: Math.ceil(remain / 1000) });
  }

  function closeViewer(afterClaim) {
    if (!state) return;
    var wasDone = state.done;
    clearInterval(state.iv);
    state = null;
    overlay.classList.remove('av-open');
    $('av-media').textContent = '';                      // stops the video / iframe
    if (!afterClaim && !wasDone) { var b = bridge(); if (b) b.notify(t('early'), 'error'); }
    refresh();
  }

  function claimReward() {
    if (!state || !state.done || state.claimed || !bridge()) return;
    state.claimed = true;
    grant(state.kind);
    closeViewer(true);
  }

  // ---------- real ads (AdMob, from the Android app) ----------
  function hasNative() {
    try { return typeof AndroidBridge !== 'undefined' && typeof AndroidBridge.showRewardedAd === 'function'; } catch (e) { return false; }
  }
  function nativeReady() {
    try { return !!AndroidBridge.isRewardedAdReady(); } catch (e) { return false; }
  }
  function showNative(kind) {
    var b = bridge();
    nativePending = kind;
    try { AndroidBridge.showRewardedAd(kind); } catch (e) { nativePending = null; b.notify(t('aderror'), 'error'); }
  }
  function onNativeResult(kind, outcome) {
    var b = bridge();
    if (!b || nativePending !== kind) return;
    nativePending = null;
    if (outcome === 'earned') grant(kind);
    else if (outcome === 'closed') b.notify(t('early'), 'error');
    else if (outcome === 'not_ready') b.notify(t('notready'), 'error');
    else b.notify(t('aderror'), 'error');
    refresh();
  }

  // ---------- open ----------
  function open(kind) {
    var b = bridge();
    if (!b) return;
    if (!b.isLoggedIn()) { b.login(); return; }
    if (state || nativePending) return;
    loadConfig().then(function () {
      var rw = cfg.rewards[kind];
      if (!cfg.enabled || !rw) return;
      if (usedToday(kind) >= rw.dailyLimit) { b.notify(t('limit'), 'error'); refresh(); return; }
      var wait = cooldownLeft();
      if (wait > 0) { b.notify(t('cooldown', { s: wait }), 'error'); refresh(); return; }
      var useNative = cfg.source === 'native' || (cfg.source === 'auto' && hasNative() && nativeReady());
      if (useNative) {
        if (!hasNative()) { b.notify(t('nonative'), 'error'); return; }
        if (!nativeReady()) { b.notify(t('notready'), 'error'); return; }
        showNative(kind);
        return;
      }
      var ad = pickAd();
      if (!ad) { b.notify(t('noads'), 'error'); return; }
      show(kind, ad);
    });
  }

  window.AdViewer = { open: open, refresh: refresh, onNativeResult: onNativeResult };

  loadConfig().then(refresh);
  setInterval(function () {
    var c = document.getElementById('ad-btn-coins'), g = document.getElementById('ad-btn-gold');
    if ((c && c.offsetParent) || (g && g.offsetParent)) refresh();
  }, 1000);
})();
