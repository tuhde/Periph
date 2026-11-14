'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { VL6180XFull } = require('periph/src/chips/tof/vl6180x');

    function VL6180XNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const gainCode = parseInt(config.gain, 10);
        const integrationMs = parseInt(config.integrationMs, 10) || 100;

        // Every driver call goes through one queue so input messages never interleave their I²C sequences.
        let queue = Promise.resolve();
        const serial = (fn) => {
            const run = queue.then(fn);
            queue = run.catch(() => {});
            return run;
        };

        try {
            const address = parseInt(config.address, 16) || VL6180XFull.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address, null, null, 2);
            node.driver = new VL6180XFull(connection);
            node.connection = connection;

            serial(async () => {
                await node.driver.init();
                await node.driver.setAlsGain(isNaN(gainCode) ? 6 : gainCode);
                await node.driver.setAlsIntegrationTime(integrationMs);
                node.status({ fill: 'green', shape: 'dot', text: 'ready' });
            }).catch((e) => {
                node.status({ fill: 'red', shape: 'ring', text: 'init failed' });
                node.error('VL6180X init failed: ' + e.message);
            });
        } catch (e) {
            node.error('VL6180X init failed: ' + e.message);
        }

        node.on('input', function(msg, send, done) {
            if (!node.driver) { done(); return; }
            serial(async () => {
                const { mm, error } = await node.driver.readRangeStatus();
                const lux = await node.driver.readLux();
                msg.payload = { range_mm: mm, range_error: error, valid: error === 0, lux };
                send(msg);
            }).then(() => done(), (e) => done(e));
        });

        node.on('close', function(removed, done) {
            serial(async () => {
                if (node.connection) await node.connection.close();
            }).catch(() => {
                // bus may already be gone; nothing more to do on close
            }).finally(() => done());
        });
    }
    RED.nodes.registerType('periph-vl6180x', VL6180XNode);
};
