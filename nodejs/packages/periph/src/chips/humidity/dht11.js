'use strict';

const { DHTxxTransport, TransportError } = require('../../transport/dhtxx');

class DHT11Minimal {
  constructor(transport) {
    this._transport = transport;
  }

  read() {
    const frame = this._transport.read();

    const humInt = frame[0];
    const humDec = frame[1];
    const tempInt = frame[2];
    const tempDec = frame[3];
    const checksum = frame[4];

    if ((humInt + humDec + tempInt + tempDec) & 0xFF !== checksum) {
      throw new Error('checksum mismatch');
    }

    const humidity = humInt + humDec / 10.0;
    const sign = (tempDec & 0x80) ? -1 : 1;
    const tempDecValue = tempDec & 0x7F;
    const temperature = sign * (tempInt + tempDecValue / 10.0);

    return [temperature, humidity];
  }
}

class DHT11Full extends DHT11Minimal {
  constructor(transport) {
    super(transport);
  }

  readTemperature() {
    return this.read()[0];
  }

  readHumidity() {
    return this.read()[1];
  }

  readRetry(maxRetries = 3) {
    for (let i = 0; i < maxRetries; i++) {
      try {
        return this.read();
      } catch (e) {}
    }
    throw new Error('all retries exhausted');
  }

  readRaw() {
    const frame = this._transport.read();

    const humInt = frame[0];
    const humDec = frame[1];
    const tempInt = frame[2];
    const tempDec = frame[3];
    const checksum = frame[4];

    if ((humInt + humDec + tempInt + tempDec) & 0xFF !== checksum) {
      throw new Error('checksum mismatch');
    }

    return frame;
  }
}

module.exports = { DHT11Minimal, DHT11Full };
