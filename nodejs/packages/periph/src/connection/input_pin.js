'use strict';

/**
 * Delivers edge notifications from a chip's INT line.
 *
 * Intentionally minimal: it only signals that *an* edge occurred. The chip
 * driver always calls pollInterrupt() to determine the cause. Multiple
 * handlers may be registered on the same InputPin instance — the common
 * pattern for chips with open-drain INT outputs wired to a single GPIO.
 */
class InputPin {
    /**
     * Register handler to run on each edge matching trigger.
     * @param {function} callback - Zero-argument function invoked on each matching edge.
     * @param {string} [trigger='falling'] - One of 'rising', 'falling', 'change'.
     * @returns {Promise<void>}
     * @abstract
     */
    async onEdge(_callback, _trigger = 'falling') { throw new Error('abstract'); }

    /**
     * Remove a specific handler. No-op if not registered.
     * @param {function} callback - The exact function previously passed to onEdge().
     * @returns {Promise<void>}
     * @abstract
     */
    async offEdge(_callback) { throw new Error('abstract'); }
}

/**
 * InputPin backed by the `epoll` npm package watching a sysfs GPIO value file.
 *
 * Requires the GPIO to already be exported (e.g. via the `onoff` package or
 * `gpio export N`). Sets the sysfs `edge` attribute to 'both' on first use so
 * a single instance can fan out to handlers registered with different
 * triggers; each handler's own trigger is filtered against the detected
 * transition direction.
 *
 * `epoll` is an optional peer dependency — require() is deferred to onEdge()
 * so consumers who never wire an INT pin never need it installed.
 */
class EpollInputPin extends InputPin {
    /**
     * @param {string} valuePath - Sysfs GPIO value file (e.g. '/sys/class/gpio/gpio17/value').
     */
    constructor(valuePath) {
        super();
        this._valuePath = valuePath;
        this._handlers = [];
        this._trigger = 'falling';
        this._armed = false;
        this._fd = null;
        this._poll = null;
        this._last = null;
    }

    async onEdge(callback, trigger = 'falling') {
        this._handlers.push(callback);
        this._trigger = trigger;
        if (this._armed) return;

        const fs = require('fs');
        const { Epoll } = require('epoll');

        const edgePath = this._valuePath.replace(/\/value$/, '/edge');
        try { fs.writeFileSync(edgePath, 'both'); } catch (_) { /* already set, or unwritable */ }

        this._fd = fs.openSync(this._valuePath, 'r');
        this._last = this._readValue();
        this._poll = new Epoll(() => {
            const current = this._readValue();
            const rising = this._last === 0 && current === 1;
            const falling = this._last === 1 && current === 0;
            this._last = current;
            if (this._trigger === 'change'
                || (this._trigger === 'rising' && rising)
                || (this._trigger === 'falling' && falling)) {
                for (const handler of this._handlers) handler();
            }
        });
        this._poll.add(this._fd, Epoll.EPOLLPRI);
        this._armed = true;
    }

    _readValue() {
        const fs = require('fs');
        const buf = Buffer.alloc(1);
        fs.readSync(this._fd, buf, 0, 1, 0);
        return buf[0] === 0x31 ? 1 : 0; // ASCII '1' vs '0'
    }

    async offEdge(callback) {
        this._handlers = this._handlers.filter(h => h !== callback);
        if (this._handlers.length === 0 && this._armed) {
            this._poll.remove(this._fd);
            const fs = require('fs');
            fs.closeSync(this._fd);
            this._fd = null;
            this._poll = null;
            this._armed = false;
        }
    }
}

/**
 * InputPin fallback: a 5 ms `setInterval` sampling loop.
 *
 * Used automatically when `epoll` is not installed, or explicitly when no
 * hardware edge-detection is available.
 */
class PollingInputPin extends InputPin {
    /**
     * @param {function} readValue - Zero-argument function returning the current pin state (0 or 1).
     * @param {number} [pollIntervalMs=5] - Polling period in milliseconds.
     */
    constructor(readValue, pollIntervalMs = 5) {
        super();
        this._readValue = readValue;
        this._pollIntervalMs = pollIntervalMs;
        this._handlers = [];
        this._trigger = 'falling';
        this._last = readValue();
        this._timer = null;
    }

    async onEdge(callback, trigger = 'falling') {
        this._handlers.push(callback);
        this._trigger = trigger;
        if (!this._timer) {
            this._timer = setInterval(() => this._tick(), this._pollIntervalMs);
        }
    }

    async offEdge(callback) {
        this._handlers = this._handlers.filter(h => h !== callback);
        if (this._handlers.length === 0 && this._timer) {
            clearInterval(this._timer);
            this._timer = null;
        }
    }

    _tick() {
        const value = this._readValue();
        if (value === this._last) return;
        const rising = value === 1 && this._last === 0;
        const falling = value === 0 && this._last === 1;
        this._last = value;
        if (this._trigger === 'change'
            || (this._trigger === 'rising' && rising)
            || (this._trigger === 'falling' && falling)) {
            for (const handler of this._handlers) handler();
        }
    }
}

module.exports = { InputPin, EpollInputPin, PollingInputPin };
