'use strict';

/** Convert HSV (each 0.0–1.0) to [r, g, b] integers 0–255. */
function _hsvToRgb(h, s, v) {
    if (s === 0) {
        const c = Math.round(v * 255);
        return [c, c, c];
    }
    const i  = Math.floor(h * 6);
    const f  = h * 6 - i;
    const p  = Math.round(v * (1 - s) * 255);
    const q  = Math.round(v * (1 - s * f) * 255);
    const t  = Math.round(v * (1 - s * (1 - f)) * 255);
    const vv = Math.round(v * 255);
    switch (i % 6) {
        case 0: return [vv, t,  p];
        case 1: return [q,  vv, p];
        case 2: return [p,  vv, t];
        case 3: return [p,  q,  vv];
        case 4: return [t,  p,  vv];
        default: return [vv, p, q];
    }
}

module.exports = { _hsvToRgb };
