// CONTROLADOR PRINCIPAL CONTENT SCRIPT MDONGHUA
(function () {
  'use strict';

  if (window.__MDONGHUA_LOADED__) return;
  window.__MDONGHUA_LOADED__ = true;

  const LOG = '[MDonghua]';
  const HIDDEN_ATTR = 'data-mdonghua-hidden';
  const SCAN_DEBOUNCE_MS = 300;
  const SCAN_INTERVAL_MS = 2000;
  const READY_TIMEOUT_MS = 45000;

  const state = {
    container: null,
    iframe: null,
    shell: null,
    player: null,
    hls: null,
    key: null,
    videoId: null,
    generation: 0,
    debounceId: 0,
    paused: false
  };

  // LOGS Y MENSAJES DE CONSOLA

  const log = (...args) => console.log(LOG, ...args);
  const warn = (...args) => console.warn(LOG, ...args);
  const fail = (...args) => console.error(LOG, ...args);

  // DETECCION DEL IFRAME Y EXTRACCION DE KEY

  function safeDecode(value) {
    try {
      return decodeURIComponent(value);
    } catch (_err) {
      return value;
    }
  }

  // EXTRAER PARAMETRO KEY EN CRUDO DE LA URL
  function rawKeyFromSrc(src) {
    if (typeof src !== 'string' || !src.trim()) return null;

    const match = src.match(/[?&]key=([^&#]*)/);
    if (match && match[1]) {
      const value = safeDecode(match[1]).trim();
      return value || null;
    }

    try {
      const value = new URL(src, location.href).searchParams.get('key');
      return value && value.trim() ? value.trim() : null;
    } catch (_err) {
      return null;
    }
  }

  function findPlayerFrame() {
    const direct = document.getElementById('tamamo_player');
    if (direct && rawKeyFromSrc(direct.getAttribute('src'))) return direct;

    const frames = document.querySelectorAll(
      '.md-player-container iframe, iframe[src*="nemonicplayer"], iframe[src*="dmplayer"], iframe[src*="key="]'
    );

    for (const frame of frames) {
      if (rawKeyFromSrc(frame.getAttribute('src'))) return frame;
    }
    return null;
  }

  function findContainer(frame) {
    return (
      frame.closest('.md-player-container') ||
      document.querySelector('.md-player-container') ||
      frame.parentElement
    );
  }

  // CONSTRUCCION DE LA INTERFAZ Y CONTENEDORES

  function buildShell(container) {
    const shell = document.createElement('div');
    shell.className = 'mdonghua-shell';
    shell.setAttribute('data-mdonghua', '1');

    const frameBox = document.createElement('div');
    frameBox.className = 'mdonghua-frame';

    const video = document.createElement('video');
    video.id = 'mdonghua-player';
    video.className = 'video-js vjs-big-play-centered vjs-16-9 mdonghua-video';
    video.setAttribute('controls', 'controls');
    video.setAttribute('playsinline', 'playsinline');
    video.setAttribute('preload', 'auto');
    video.setAttribute('title', 'Reproductor MDonghua');
    frameBox.appendChild(video);

    const loader = document.createElement('div');
    loader.className = 'mdonghua-loader';
    loader.setAttribute('role', 'status');
    loader.setAttribute('aria-live', 'polite');

    const spinner = document.createElement('span');
    spinner.className = 'mdonghua-spinner';
    spinner.setAttribute('aria-hidden', 'true');

    const loaderText = document.createElement('span');
    loaderText.className = 'mdonghua-loader-text';
    loaderText.textContent = 'Cargando reproducción…';

    const loaderHint = document.createElement('span');
    loaderHint.className = 'mdonghua-loader-hint';
    loaderHint.textContent = 'Descifrando la key del reproductor y preparando el stream.';

    loader.append(spinner, loaderText, loaderHint);

    const panel = document.createElement('div');
    panel.className = 'mdonghua-error';
    panel.hidden = true;

    const title = document.createElement('strong');
    title.textContent = 'No se pudo iniciar el reproductor';

    const message = document.createElement('p');
    message.className = 'mdonghua-error-msg';
    message.textContent = '';

    const actions = document.createElement('div');
    actions.className = 'mdonghua-error-actions';

    const retry = document.createElement('button');
    retry.type = 'button';
    retry.textContent = 'Reintentar';
    retry.addEventListener('click', () => {
      log('reintento manual solicitado');
      forceRescan();
    });

    const original = document.createElement('button');
    original.type = 'button';
    original.textContent = 'Usar reproductor original';
    original.addEventListener('click', () => {
      log('restaurando el iframe original');
      restoreOriginal();
    });

    actions.append(retry, original);
    panel.append(title, message, actions);

    shell.append(frameBox, loader, panel);
    container.appendChild(shell);
    return shell;
  }

  function hideFrame(frame) {
    frame.setAttribute(HIDDEN_ATTR, '1');
    frame.style.setProperty('display', 'none', 'important');
  }

  function revealFrame(frame) {
    if (!frame) return;
    frame.removeAttribute(HIDDEN_ATTR);
    frame.style.removeProperty('display');
  }

  function showError(shell, message) {
    if (!shell || !shell.isConnected) return;
    const panel = shell.querySelector('.mdonghua-error');
    const text = shell.querySelector('.mdonghua-error-msg');
    if (text) text.textContent = message;
    if (panel) panel.hidden = false;
    shell.setAttribute('data-mdonghua-error', '1');
  }

  function clearReadyTimeout(shell) {
    if (shell && shell.__mdonghuaTimeout) {
      clearTimeout(shell.__mdonghuaTimeout);
      shell.__mdonghuaTimeout = 0;
    }
  }

  // LIMPIEZA Y LIBERACION DE MEMORIA DEL REPRODUCTOR

  function dispose() {
    if (state.shell) clearReadyTimeout(state.shell);

    if (state.hls) {
      try {
        state.hls.destroy();
      } catch (err) {
        warn('error al destruir Hls.js:', err);
      }
      state.hls = null;
    }

    if (state.player) {
      try {
        state.player.dispose();
      } catch (err) {
        warn('error al destruir Video.js:', err);
      }
      state.player = null;
    }

    if (state.shell) {
      state.shell.remove();
      state.shell = null;
    }

    if (state.iframe) {
      revealFrame(state.iframe);
      state.iframe = null;
    }

    state.container = null;
    state.videoId = null;
    state.key = null;
  }

  function restoreOriginal() {
    state.paused = true;
    state.generation += 1;
    dispose();
    log('el reproductor original vuelve a estar visible');
  }

  function forceRescan() {
    state.paused = false;
    state.generation += 1;
    dispose();
    scan(true);
    scheduleScan();
  }

  // RESOLUCION Y OBTENCION DEL STREAM HLS

  const API_ENDPOINTS = [
    (id) => 'https://geo.dailymotion.com/videos/' + encodeURIComponent(id),
    (id) => 'https://geo.dailymotion.com/player/metadata/video/' + encodeURIComponent(id)
  ];

  async function fetchJson(url) {
    const response = await fetch(url, {
      method: 'GET',
      credentials: 'omit',
      cache: 'no-store',
      headers: { accept: 'application/json, text/plain, */*' }
    });

    if (!response.ok) {
      throw new Error('HTTP ' + response.status + ' al consultar ' + url);
    }

    const body = await response.text();
    try {
      return JSON.parse(body);
    } catch (_err) {
      throw new Error('respuesta no JSON desde ' + url);
    }
  }

  function collectStrings(node, out, depth) {
    if (depth > 14 || node === null || node === undefined) return out;

    if (typeof node === 'string') {
      const value = node.replace(/\\\//g, '/');
      if (/^https?:\/\//i.test(value)) out.push(value);
      return out;
    }

    if (Array.isArray(node)) {
      for (const item of node) collectStrings(item, out, depth + 1);
      return out;
    }

    if (typeof node === 'object') {
      for (const value of Object.values(node)) collectStrings(value, out, depth + 1);
    }
    return out;
  }

  function pickStreamUrl(payload, kind) {
    const pattern = kind === 'hls' ? /\.m3u8(\?|#|$)/i : /\.mp4(\?|#|$)/i;
    const matches = collectStrings(payload, [], 0).filter((url) => pattern.test(url));
    if (!matches.length) return null;

    const scored = matches.map((url) => {
      let score = 0;
      if (/master\.m3u8/i.test(url)) score += 50;
      if (/playlist\.m3u8/i.test(url)) score += 20;
      if (/index\.m3u8/i.test(url)) score += 10;
      if (/mp4-hls/i.test(url)) score += 8;
      if (/\(worst\)/i.test(url)) score -= 5;
      return { url, score };
    });

    scored.sort((a, b) => b.score - a.score);
    return scored[0].url;
  }

  async function resolveStream(videoId) {
    const errors = [];
    const seen = new Set();

    for (const buildUrl of API_ENDPOINTS) {
      const endpoint = buildUrl(videoId);
      if (seen.has(endpoint)) continue;
      seen.add(endpoint);

      try {
        const payload = await fetchJson(endpoint);

        // STREAM DIRECTO EN DATA STREAM URL
        const direct = payload && payload.data && payload.data.stream && payload.data.stream.url;
        if (typeof direct === 'string' && /\.m3u8/i.test(direct)) {
          return { url: direct, kind: 'hls' };
        }

        const hls = pickStreamUrl(payload, 'hls');
        if (hls) return { url: hls, kind: 'hls' };

        const mp4 = pickStreamUrl(payload, 'mp4');
        if (mp4) {
          warn('no hay manifiesto HLS; se reproduce el MP4 progresivo');
          return { url: mp4, kind: 'mp4' };
        }

        errors.push('sin manifiesto en ' + endpoint);
      } catch (err) {
        errors.push((err && err.message) || String(err));
      }
    }

    throw new Error('No se pudo obtener el stream: ' + errors.join(' | '));
  }

  // MONTAJE E INICIALIZACION DE VIDEOJS Y HLS

  function attemptAutoplay() {
    try {
      if (state.player && typeof state.player.play === 'function') {
        const result = state.player.play();
        if (result && typeof result.catch === 'function') {
          result.catch(() => log('autoplay bloqueado, esperando acción del usuario'));
        }
        return;
      }
      if (state.shell) {
        const video = state.shell.querySelector('video');
        if (video) {
          const result = video.play();
          if (result && typeof result.catch === 'function') {
            result.catch(() => log('autoplay bloqueado, esperando acción del usuario'));
          }
        }
      }
    } catch (err) {
      warn('no se pudo iniciar la reproducción automática:', err);
    }
  }

  function useNativeFallback(shell, source) {
    if (state.hls) {
      try {
        state.hls.destroy();
      } catch (_err) {
        // SILENCIAR ERROR NO CRITICO
      }
      state.hls = null;
    }

    const type = source.kind === 'hls' ? 'application/x-mpegURL' : 'video/mp4';
    log('usando playback nativo / VHS:', type);

    if (state.player) {
      state.player.src({ src: source.url, type });
    } else if (shell) {
      const video = shell.querySelector('video');
      if (video) video.src = source.url;
    }

    attemptAutoplay();
  }

  // SELECTOR DE RESOLUCIONES VIDEOJS

  function setupQualityMenu(player, hls) {
    if (!player || !hls || !player.controlBar) return;

    // LIMPIAR BOTON PREVIO DE CALIDAD
    const oldBtn = player.controlBar.getChild('QualityMenuButton');
    if (oldBtn) {
      player.controlBar.removeChild(oldBtn);
      oldBtn.dispose();
    }

    const VjsMenuButton = window.videojs.getComponent('MenuButton');
    const VjsMenuItem = window.videojs.getComponent('MenuItem');

    class QualityMenuItem extends VjsMenuItem {
      constructor(p, options) {
        super(p, options);
        this.levelIndex = options.levelIndex;
        this.hlsInstance = options.hlsInstance;
      }

      handleClick() {
        this.hlsInstance.currentLevel = this.levelIndex;
        this.updateSelected();
      }

      updateSelected() {
        const isCurrent = this.hlsInstance.currentLevel === this.levelIndex;
        this.selected(isCurrent);
      }
    }

    class QualityMenuButton extends VjsMenuButton {
      constructor(p, options) {
        super(p, options);
        this.addClass('vjs-quality-menu-button');
        this.controlText('Calidad');
      }

      buildCSSClass() {
        return 'vjs-quality-menu-button vjs-menu-button vjs-menu-button-popup vjs-control vjs-button';
      }

      createItems() {
        const items = [];
        const levels = hls.levels || [];

        // GENERAR OPCIONES DE CALIDAD DESCENDENTES
        const sorted = levels.map((lvl, index) => ({ lvl, index })).sort((a, b) => (b.lvl.height || 0) - (a.lvl.height || 0));

        sorted.forEach(({ lvl, index }) => {
          const height = lvl.height || Math.round((lvl.bitrate || 0) / 1000) + 'k';
          const label = height ? `${height}p` : `Nivel ${index + 1}`;
          const item = new QualityMenuItem(this.player_, {
            label,
            selectable: true,
            levelIndex: index,
            hlsInstance: hls
          });
          item.selected(hls.currentLevel === index);
          items.push(item);
        });

        // OPCION AUTOMATICA POR DEFECTO
        const autoItem = new QualityMenuItem(this.player_, {
          label: 'Auto',
          selectable: true,
          levelIndex: -1,
          hlsInstance: hls
        });
        autoItem.selected(hls.currentLevel === -1);
        items.push(autoItem);

        // ACTUALIZAR ESTADO AL CAMBIAR DE NIVEL
        hls.on(window.Hls.Events.LEVEL_SWITCHED, (_e, data) => {
          items.forEach(it => {
            if (typeof it.updateSelected === 'function') it.updateSelected();
          });
        });

        return items;
      }
    }

    try {
      window.videojs.registerComponent('QualityMenuButton', QualityMenuButton);
    } catch (_e) {
      // COMPONENTE YA REGISTRADO
    }

    // INSERTAR SELECTOR ANTES DE PANTALLA COMPLETA
    const fullscreenIndex = player.controlBar.children_.findIndex(c => c && c.name_ === 'FullscreenToggle');
    const insertIndex = fullscreenIndex > 0 ? fullscreenIndex : undefined;
    player.controlBar.addChild('QualityMenuButton', {}, insertIndex);
  }

  function attachSource(shell, source) {
    const Hls = window.Hls;
    const isHls = source.kind === 'hls';
    const canUseHlsJs = isHls && Hls && typeof Hls.isSupported === 'function' && Hls.isSupported();

    if (!canUseHlsJs) {
      useNativeFallback(shell, source);
      return;
    }

    const videoEl = shell.querySelector('video');
    if (!videoEl) return;

    const hls = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      backBufferLength: 90,
      maxBufferLength: 40,
      manifestLoadingMaxRetry: 2,
      levelLoadingMaxRetry: 3,
      fragLoadingMaxRetry: 4
    });
    state.hls = hls;

    hls.on(Hls.Events.MANIFEST_PARSED, (_evt, data) => {
      const levels = data && data.levels ? data.levels.length : 0;
      log('manifiesto HLS parseado (' + levels + ' niveles)');
      if (state.player && levels > 1) {
        setupQualityMenu(state.player, hls);
      }
      attemptAutoplay();
    });

    let networkRetries = 0;
    let mediaRetries = 0;

    hls.on(Hls.Events.ERROR, (_evt, data) => {
      if (!data || !data.fatal) return;
      warn('Hls.js error fatal:', data.type, data.details);

      if (data.type === Hls.ErrorTypes.NETWORK_ERROR && networkRetries < 2) {
        networkRetries += 1;
        log('reintentando red (' + networkRetries + '/2)');
        hls.startLoad();
        return;
      }

      if (data.type === Hls.ErrorTypes.MEDIA_ERROR && mediaRetries < 2) {
        mediaRetries += 1;
        log('recuperando error de media (' + mediaRetries + '/2)');
        hls.recoverMediaError();
        return;
      }

      fail('stream no recuperable:', data.details);
      useNativeFallback(shell, source);
    });

    hls.attachMedia(videoEl);
    hls.loadSource(source.url);
    log('Hls.js cargando manifiesto');
  }

  function mountPlayer(shell, source, generation) {
    const videoEl = shell.querySelector('video');
    if (!videoEl) return;

    if (typeof window.videojs === 'function') {
      try {
        state.player = window.videojs(videoEl, {
          controls: true,
          autoplay: false,
          preload: 'auto',
          liveui: false,
          playbackRates: [0.5, 0.75, 1, 1.25, 1.5, 2]
        });

        // FORZAR AUDIO Y VOLUMEN ACTIVO
        try {
          state.player.volume(1);
          state.player.muted(false);
        } catch (_v) {}

        log('Video.js ' + (window.videojs.VERSION || '') + ' inicializado');
        state.player.on('error', () => {
          warn('error del reproductor:', state.player && state.player.error && state.player.error());
        });
      } catch (err) {
        warn('Video.js no pudo inicializarse, se usa <video> nativo:', err);
        state.player = null;
      }
    } else {
      warn('video.js no está disponible, se usa <video> nativo');
    }

    const markReady = (reason) => {
      if (generation !== state.generation) return;
      if (shell.classList.contains('is-ready')) return;

      clearReadyTimeout(shell);
      shell.classList.add('is-ready');
      log('reproducción lista (' + reason + ')');

      if (state.player) {
        try {
          state.player.controls(true);
        } catch (_err) {
          // SILENCIAR ERROR NO CRITICO
        }
      }
    };

    ['loadedmetadata', 'canplay', 'playing'].forEach((eventName) => {
      videoEl.addEventListener(eventName, () => markReady(eventName), { once: true });
    });

    shell.__mdonghuaTimeout = setTimeout(() => {
      if (generation !== state.generation) return;
      if (shell.classList.contains('is-ready')) return;
      warn('el stream no alcanzó canplay en ' + READY_TIMEOUT_MS / 1000 + ' s');
      showError(
        shell,
        'El CDN no entregó el stream a tiempo. Revisa la consola con el filtro [MDonghua] y pulsa Reintentar.'
      );
    }, READY_TIMEOUT_MS);

    attachSource(shell, source);
  }

  // GESTION DE CICLO DE VIDA DEL IFRAME Y STREAM

  async function handleFrame(frame) {
    const rawKey = rawKeyFromSrc(frame.getAttribute('src'));
    if (!rawKey) return;

    if (state.shell && state.shell.isConnected && state.key === rawKey) return;

    const container = findContainer(frame);
    if (!container) {
      warn('no se encontró el contenedor .md-player-container');
      return;
    }

    const generation = ++state.generation;
    dispose();

    state.key = rawKey;
    state.iframe = frame;
    state.container = container;

    hideFrame(frame);
    const shell = buildShell(container);
    state.shell = shell;

    log('iframe detectado; key capturada (' + rawKey.length + ' caracteres base64)');

    try {
      if (!window.MDonghuaCrypto) {
        throw new Error('crypto.js no está disponible en la página.');
      }

      const videoId = await window.MDonghuaCrypto.decryptNemonicKey(rawKey);
      if (generation !== state.generation) return;
      if (!videoId) throw new Error('La key no contiene un ID de video reconocible.');

      state.videoId = videoId;

      const source = await resolveStream(videoId);
      if (generation !== state.generation) return;

      log('stream resuelto:', source.url);
      mountPlayer(shell, source, generation);
    } catch (err) {
      if (generation !== state.generation) return;
      fail('no se pudo preparar el reproductor:', err);
      showError(shell, (err && err.message) || String(err));
    }
  }

  function scan(force) {
    if (state.paused && !force) return;

    const frame = findPlayerFrame();
    if (!frame) {
      if (state.shell && !state.shell.isConnected) dispose();
      return;
    }

    const key = rawKeyFromSrc(frame.getAttribute('src'));
    if (!key) return;

    // PREVENIR DUPLICACION SI LA KEY YA ESTA ACTIVA
    if (state.shell && state.shell.isConnected && state.key === key) return;

    handleFrame(frame);
  }

  function scheduleScan() {
    if (state.debounceId) return;
    state.debounceId = setTimeout(() => {
      state.debounceId = 0;
      scan(false);
    }, SCAN_DEBOUNCE_MS);
  }

  // INICIALIZACION Y OBSERVADOR MUTATION OBSERVER

  function start() {
    log('content script activo en', location.href);
    if (!window.MDonghuaCrypto) warn('crypto.js no se cargó: el descifrado no estará disponible.');
    if (typeof window.videojs !== 'function') warn('Video.js no se cargó: se usará <video> nativo.');
    if (!window.Hls) warn('Hls.js no se cargó: se usará VHS / playback nativo.');

    const observer = new MutationObserver(scheduleScan);
    observer.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['src', 'id', 'class']
    });

    window.addEventListener('hashchange', scheduleScan);
    window.addEventListener('popstate', scheduleScan);

    // INTERVALO PERIODICO DE SEGURIDAD PARA SPA
    setInterval(() => scan(false), SCAN_INTERVAL_MS);

    scan(false);
    scheduleScan();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }

  // EXPONER OBJETO GLOBAL PARA DEPURACION
  window.MDonghua = { state, scan: () => forceRescan(), dispose, restoreOriginal };
})();
