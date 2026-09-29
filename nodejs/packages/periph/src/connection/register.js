'use strict';

/**
 * Interpret the low `bits` bits of `value` as two's-complement signed.
 * @param {number} value - Unsigned raw register value.
 * @param {number} bits  - Width of the value in bits (e.g. 16, 24).
 * @returns {number} The signed interpretation of the low `bits` bits of `value`.
 */
function toSigned(value, bits) {
    const signBit = 1 << (bits - 1);
    return (value & (signBit - 1)) - (value & signBit);
}

module.exports = { toSigned };
