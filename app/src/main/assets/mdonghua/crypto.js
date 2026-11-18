// MODULO CRIPTO MDONGHUA
(function (global) {
  'use strict';

  // PARAMETROS PRECALCULADOS ENMASCARADOS
  const _K_MASK = [179, 170, 334, 289, 351, 276, 156, 149, 379, 264, 225, 233, 237, 283, 198, 297, 330, 214, 317, 440, 48, 32, 61, 482, 15, 402, 395, 25, 99, 108, 118, 111];
  const _IV_MASK = [526, 296, 536, 309, 575, 569, 342, 369, 356, 624, 608, 365, 665, 651, 670, 397];

  // RECONSTRUCCION DE BYTES EN MEMORIA VOLATIL
  function _reconstruct(mask, mul, add, step) {
    const bytes = new Uint8Array(mask.length);
    for (let i = 0; i < mask.length; i++) {
      bytes[i] = (((mask[i] ^ ((i * step) & 0xff)) - add) / mul) | 0;
    }
    return bytes;
  }

  const LOG = '[MDonghua][crypto]';
  const HEX_RE = /^[0-9a-f]+$/i;
  const ID_RE = /^[A-Za-z0-9]{15,22}$/;
  const B64_RE = /^[A-Za-z0-9+/=_-]+$/;

  const log = (...args) => console.log(LOG, ...args);
  const warn = (...args) => console.warn(LOG, ...args);

  // VALIDAR DISPONIBILIDAD DE WEB CRYPTO
  function requireSubtle() {
    const c = global.crypto || global.msCrypto;
    if (!c || !c.subtle) throw new Error('Web Crypto no disponible');
    return c.subtle;
  }

  // CONVERTIR BASE64 DECODIFICANDO QUERYS Y PADDING
  function base64ToBytes(raw) {
    if (typeof raw !== 'string' || !raw.trim()) throw new Error('Key vacia');
    let value = raw.trim();
    if (/%[0-9A-Fa-f]{2}/.test(value)) {
      try { value = decodeURIComponent(value); } catch (_e) {}
    }
    value = value.replace(/\s+/g, '+').replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
    if (!B64_RE.test(value)) throw new Error('Base64 invalido');
    const padded = value + '='.repeat((4 - (value.length % 4)) % 4);
    const binary = global.atob(padded);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return bytes;
  }

  function latin1(bytes) {
    let out = '';
    for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
    return out;
  }

  function isBase64Text(value) {
    return typeof value === 'string' && value.trim().length >= 16 && /^[A-Za-z0-9+/=_-]+$/.test(value.trim());
  }

  // MANEJO DE CAPAS ANIDADAS BASE64
  function buildCipherCandidates(raw) {
    const list = [];
    let current = raw;
    for (let depth = 0; depth < 3; depth++) {
      let bytes;
      try { bytes = base64ToBytes(current); } catch (_e) { break; }
      list.push({ depth, bytes });
      const asText = latin1(bytes);
      if (!isBase64Text(asText)) break;
      current = asText;
    }
    return list.reverse();
  }

  // CARGAR CLAVE E IV RECONSTRUIDOS
  function buildCandidates() {
    return [{ name: 'default', key: _reconstruct(_K_MASK, 3, 17, 7), iv: _reconstruct(_IV_MASK, 5, 31, 11) }];
  }

  // DESCIFRADO AES-CBC POR HARDWARE NATIVO
  async function aesCbcDecrypt(cipherBytes, keyBytes, ivBytes) {
    if (cipherBytes.length === 0 || cipherBytes.length % 16 !== 0) throw new Error('Ciphertext invalido');
    const subtle = requireSubtle();
    const cryptoKey = await subtle.importKey('raw', keyBytes, { name: 'AES-CBC' }, false, ['decrypt']);
    const plain = await subtle.decrypt({ name: 'AES-CBC', iv: ivBytes }, cryptoKey, cipherBytes);
    return new Uint8Array(plain);
  }

  // VALIDAR FORMATO DE ID DE DAILYMOTION
  function looksLikeVideoId(token) {
    if (typeof token !== 'string') return false;
    const value = token.trim();
    return ID_RE.test(value) && /[0-9]/.test(value) && /[A-Za-z]/.test(value);
  }

  // RECORRER ESTRUCTURA JSON EN BUSCA DEL ID
  function walkJson(node, depth) {
    if (depth > 12 || !node) return null;
    if (typeof node === 'string') {
      if (looksLikeVideoId(node)) return node.trim();
      const nested = node.match(/[A-Za-z0-9]{15,22}/g);
      if (nested) {
        for (const token of nested) if (looksLikeVideoId(token)) return token;
      }
      return null;
    }
    if (Array.isArray(node)) {
      for (const item of node) {
        const found = walkJson(item, depth + 1);
        if (found) return found;
      }
      return null;
    }
    if (typeof node === 'object') {
      const preferred = ['id', 'videoId', 'video_id', 'key', 'token'];
      for (const field of preferred) {
        if (Object.prototype.hasOwnProperty.call(node, field)) {
          const found = walkJson(node[field], depth + 1);
          if (found) return found;
        }
      }
      for (const val of Object.values(node)) {
        const found = walkJson(val, depth + 1);
        if (found) return found;
      }
    }
    return null;
  }

  // EXTRAER EL VIDEO ID DEL TEXTO PLANO
  function extractVideoId(plaintext) {
    if (typeof plaintext !== 'string') return null;
    const text = plaintext.replace(/^\uFEFF/, '').replace(/\u0000+$/g, '').trim();
    if (!text) return null;

    // PARSEO JSON
    if (/^[[{]/.test(text)) {
      try {
        const found = walkJson(JSON.parse(text), 0);
        if (found) return found;
      } catch (_e) {}
    }

    // REGEX URL DAILYMOTION
    const urlMatch = text.match(/(?:dailymotion\.com\/(?:embed\/)?video\/|\/video\/|\/embed\/video\/)([A-Za-z0-9]{6,24})/i);
    if (urlMatch && looksLikeVideoId(urlMatch[1])) return urlMatch[1];

    // SEGMENTOS DELIMITADOS POR TUBERIA O COMA
    const segments = text.split(/[|;,\s]+/).map(p => p.trim().replace(/^["'[{]+/, '').replace(/["'\]},]+$/, '')).filter(Boolean);
    for (const segment of segments) {
      if (looksLikeVideoId(segment)) return segment;
    }

    // CANDIDATOS POR LONGITUD
    const tokens = text.match(/[A-Za-z0-9]{15,22}/g) || [];
    const candidates = tokens.filter(looksLikeVideoId);
    if (candidates.length) {
      candidates.sort((a, b) => b.length - a.length);
      return candidates[0];
    }
    return null;
  }

  function looksStructured(text) {
    if (typeof text !== 'string') return false;
    const v = text.trim();
    return /^[[{]/.test(v) || /^https?:\/\//i.test(v) || /^\d{9,13}\s*[|;,:\s]/.test(v);
  }

  // DESCIFRAR LA KEY CAPTURADA DEL IFRAME
  async function decryptNemonicKey(base64Ciphertext) {
    const cipherCandidates = buildCipherCandidates(base64Ciphertext);
    if (!cipherCandidates.length) throw new Error('Key Base64 invalida');

    const derivations = buildCandidates();
    let loose = null;

    for (const cipher of cipherCandidates) {
      for (const derivation of derivations) {
        try {
          const plainBytes = await aesCbcDecrypt(cipher.bytes, derivation.key, derivation.iv);
          const plaintext = new TextDecoder('utf-8', { fatal: false }).decode(plainBytes);
          const cleaned = plaintext.replace(/\u0000+$/g, '').trim();
          const videoId = extractVideoId(cleaned);
          if (videoId) {
            if (looksStructured(cleaned)) return videoId;
            if (!loose) loose = videoId;
          }
        } catch (_e) {}
      }
    }

    if (loose) return loose;
    throw new Error('Fallo al descifrar key');
  }

  // EXPORTAR API CRIPTO
  global.MDonghuaCrypto = {
    decryptNemonicKey,
    extractVideoId,
    base64ToBytes
  };

  log('modulo de descifrado cargado');
})(window);
