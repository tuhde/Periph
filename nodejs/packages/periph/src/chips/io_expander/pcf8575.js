'use strict';

class Pcf8575Minimal {
    constructor(transport, addr = 0x20) {
        this._transport = transport;
        this._addr = addr;
        this._shadow = [0xFF, 0xFF];
        this._writeBoth();
    }

    _writeBoth() {
        this._transport.write(Buffer.from([this._shadow[0], this._shadow[1]]));
    }

    _readPort() {
        return this._transport.read(2);
    }

    _setPin(n, value) {
        const portIdx = Math.floor(n / 8);
        const bit = n % 8;
        if (value) this._shadow[portIdx] |=  (1 << bit);
        else       this._shadow[portIdx] &= ~(1 << bit);
        this._writeBoth();
    }

    pin(n, direction = 'in') {
        const p = new _Pin(this, n, direction);
        if (direction === 'out') this._setPin(n, 0);
        else                     this._setPin(n, 1);
        return p;
    }

    readPort(port = 0) {
        return this._readPort()[port];
    }

    writePort(port = 0, mask = 0xFF) {
        this._shadow[port] = mask & 0xFF;
        this._writeBoth();
    }
}

class _Pin {
    constructor(chip, n, direction) {
        this._chip = chip;
        this._n = n;
        this._direction = direction;
    }

    get direction() { return this._direction; }

    readSync() {
        const port = Math.floor(this._n / 8);
        const bit = this._n % 8;
        return (this._chip._readPort()[port] >> bit) & 1;
    }

    writeSync(value) {
        this._chip._setPin(this._n, value ? 1 : 0);
    }

    read(callback) {
        try { callback(null, this.readSync()); } catch (e) { callback(e); }
    }

    write(value, callback) {
        try { this.writeSync(value); callback(null); } catch (e) { callback(e); }
    }

    setDirection(direction, callback) {
        try {
            this._direction = direction;
            this._chip._setPin(this._n, direction === 'in' ? 1 : 0);
            if (callback) callback(null);
        } catch (e) { if (callback) callback(e); }
    }

    unexport() {}
}

class Pcf8575Full extends Pcf8575Minimal {
    constructor(transport, addr = 0x20) {
        super(transport, addr);
        const raw = this._readPort();
        this._prev = [raw[0], raw[1]];
        this._callback = null;
        this._pollTimer = null;
        this._watchers = {};
    }

    pin(n, direction = 'in') {
        const p = new _FullPin(this, n, direction);
        if (direction === 'out') this._setPin(n, 0);
        else                     this._setPin(n, 1);
        return p;
    }

    configureInterrupt(intGpioPath, callback) {
        this._callback = callback;
        if (this._pollTimer) { clearInterval(this._pollTimer); this._pollTimer = null; }
        if (intGpioPath) {
            try {
                const fs = require('fs');
                const ep = require('epoll').Epoll;
                const fd = fs.openSync(intGpioPath, 'r');
                const poll = new ep((err, fd2) => {
                    fs.readSync(fd2, Buffer.alloc(1), 0, 1, 0);
                    const changed = this.clearInterrupt();
                    if (changed) this._dispatch(changed);
                });
                poll.add(fd, ep.EPOLLPRI);
            } catch (_) {
                this._startPolling();
            }
        } else {
            this._startPolling();
        }
    }

    _startPolling() {
        this._pollTimer = setInterval(() => {
            const changed = this.clearInterrupt();
            if (changed) this._dispatch(changed);
        }, 5);
    }

    _dispatch(changed) {
        if (this._callback) this._callback(changed);
        for (const [n, handlers] of Object.entries(this._watchers)) {
            if ((changed >> n) & 1) {
                handlers.forEach(h => h(null, this.readSync ? undefined : 0));
            }
        }
    }

    clearInterrupt() {
        const current = this._readPort();
        const changed0 = current[0] ^ this._prev[0];
        const changed1 = current[1] ^ this._prev[1];
        this._prev = [current[0], current[1]];
        return changed0 | (changed1 << 8);
    }
}

class _FullPin extends _Pin {
    constructor(chip, n, direction) {
        super(chip, n, direction);
    }

    watch(handler) {
        const n = this._n;
        if (!this._chip._watchers[n]) this._chip._watchers[n] = [];
        this._chip._watchers[n].push((err) => {
            handler(err, this.readSync());
        });
    }

    unwatch(handler) {
        const n = this._n;
        if (this._chip._watchers[n]) {
            this._chip._watchers[n] = this._chip._watchers[n].filter(h => h !== handler);
        }
    }

    unwatchAll() {
        this._chip._watchers[this._n] = [];
    }

    setActiveLow(invert) {
        this._activeLow = !!invert;
    }
}

module.exports = { Pcf8575Minimal, Pcf8575Full };