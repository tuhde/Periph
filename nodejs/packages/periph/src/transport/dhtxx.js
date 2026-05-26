'use strict';

const { Gpio } = require('onoff');

class DHTxxTransport {
  constructor(dataPin) {
    this._pin = dataPin;
    this._T_HOST_LOW = 20000;
    this._T_GO = 20;
    this._T_THRESHOLD = 40;
  }

  read() {
    const self = this;

    this._pin.setDirection('out');
    this._pin.write(0);
    sleep_us(this._T_HOST_LOW);

    this._pin.setDirection('in');
    sleep_us(this._T_GO);

    if (this._waitLow(1000) < 0) {
      throw new TransportError('timeout');
    }
    if (this._waitHigh(1000) < 0) {
      throw new TransportError('timeout');
    }

    const bits = [];
    for (let i = 0; i < 40; i++) {
      if (this._waitLow(1000) < 0) {
        throw new TransportError('framing');
      }
      const width = this._waitHigh(1000);
      if (width < 0) {
        throw new TransportError('framing');
      }
      bits.push(width >= this._T_THRESHOLD ? 1 : 0);
    }

    let result = 0;
    for (const b of bits) {
      result = (result << 1) | b;
    }

    return Buffer.from([
      (result >> 32) & 0xFF,
      (result >> 24) & 0xFF,
      (result >> 16) & 0xFF,
      (result >> 8) & 0xFF,
      result & 0xFF,
    ]);
  }

  close() {
    this._pin.unexport();
  }

  _waitLow(timeoutUs) {
    const start = hrtime();
    while (this._pin.readSync() === 1) {
      if (hrtimeDiffUs(start) > timeoutUs) {
        return -1;
      }
    }
    return hrtimeDiffUs(start);
  }

  _waitHigh(timeoutUs) {
    const start = hrtime();
    while (this._pin.readSync() === 0) {
      if (hrtimeDiffUs(start) > timeoutUs) {
        return -1;
      }
    }
    return hrtimeDiffUs(start);
  }
}

function sleep_us(us) {
  const end = Date.now() + us / 1000;
  while (Date.now() < end) {}
}

function hrtime() {
  const ns = process.hrtime();
  return ns[0] * 1e9 + ns[1];
}

function hrtimeDiffUs(start) {
  const ns = process.hrtime();
  return (ns[0] * 1e9 + ns[1] - start) / 1000;
}

class TransportError extends Error {
  constructor(msg) {
    super(msg);
    this.name = 'TransportError';
  }
}

module.exports = { DHTxxTransport, TransportError };
