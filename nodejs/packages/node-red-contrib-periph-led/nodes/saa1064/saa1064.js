'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { SAA1064Full }   = require('periph/src/chips/led/saa1064');

    function SAA1064DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new SAA1064Full(connection);
            node.connection = connection;
            node.driver.ready
                .then(function() {
                    const ma = parseInt(config.current, 10);
                    if (!isNaN(ma) && ma !== 12) return node.driver.setCurrent(ma);
                })
                .then(function() {
                    if (config.mode === 'static') return node.driver.setMode(false);
                })
                .catch(function(e) { node.error('SAA1064 init failed: ' + e.message); });
        } catch (e) {
            node.error('SAA1064 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('saa1064-device', SAA1064DeviceNode);

    function SAA1064Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No SAA1064 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = msg.payload;
                const out = {};
                if (typeof p === 'number') {
                    await d.showNumber(p);
                    out.number = p;
                } else if (typeof p === 'string') {
                    if (p.length !== 4) throw new RangeError('string payload must have 4 characters');
                    const digits = Array.from(p).map(function(ch) { return ch === ' ' ? null : parseInt(ch, 10); });
                    await d.showDigits(digits);
                    out.digits = p;
                } else if (p && typeof p === 'object') {
                    if (Array.isArray(p.raw)) { await d.writeRaw(p.raw); out.raw = p.raw; }
                    if (typeof p.current === 'number') { await d.setCurrent(p.current); out.current = p.current; }
                    if (typeof p.test === 'boolean') { await d.segmentTest(p.test); out.test = p.test; }
                }
                if (Object.keys(out).length === 0) {
                    node.error('Payload must be a number 0-9999, a 4-character string, { raw }, or { current, test }', msg);
                } else {
                    msg.payload = out;
                    send(msg);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('saa1064', SAA1064Node);
};
