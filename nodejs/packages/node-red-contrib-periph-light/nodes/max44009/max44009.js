'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MAX44009Full } = require('periph/src/chips/light/max44009');

    function MAX44009DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address || '0x4A', 16);
            node.connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new MAX44009Full(node.connection);
            node.ready = (async () => {
                await node.driver.init();
                await node.driver.setContinuous(config.mode === 'continuous');
                if (config.range === 'manual') {
                    await node.driver.setManual(parseFloat(config.integration) || 100, config.divide8 === true || config.divide8 === 'true');
                } else {
                    await node.driver.setAutomatic();
                }
            })();
            node.ready.then(() => node.status({ fill: 'green', shape: 'dot', text: 'I2C' }))
                .catch((e) => node.error('MAX44009 init failed: ' + e.message));
        } catch (e) {
            node.error('MAX44009 init failed: ' + e.message);
        }

        // Optional INT line: emit { lux, interrupt: true } when the light leaves the window.
        if (node.driver && config.intPin !== '' && config.intPin !== undefined) {
            (async () => {
                try {
                    const { Default } = require('opengpio');
                    const { PollingInputPin } = require('periph/src/connection/input_pin');
                    const line = Default.input({ chip: parseInt(config.gpioChip || 0, 10), line: parseInt(config.intPin, 10) });
                    const intPin = new PollingInputPin(() => (line.value ? 1 : 0), 20);
                    await node.ready;
                    await node.driver.onInterrupt(async () => {
                        const lux = await node.driver.lux();
                        node.emit('periph-data', { payload: { lux, interrupt: true } });
                    }, { intPin });
                } catch (e) {
                    node.error('MAX44009 INT setup failed: ' + e.message);
                }
            })();
        }

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.offInterrupt();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('max44009-device', MAX44009DeviceNode);

    function MAX44009ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};
        if (deviceNode.on) {
            const onData = (m) => node.send(m);
            deviceNode.on('periph-data', onData);
            node.on('close', () => deviceNode.removeListener('periph-data', onData));
        }

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(new Error('MAX44009 not initialised')); return; }
            try {
                await deviceNode.ready;
                const p = msg.payload;
                if (p && typeof p === 'object' && typeof p.low === 'number' && typeof p.high === 'number') {
                    await deviceNode.driver.setThresholds(p.low, p.high);
                    if (typeof p.delay === 'number') await deviceNode.driver.setThresholdDelay(p.delay);
                    await deviceNode.driver.enableInterrupt();
                    done();
                    return;
                }
                const lux = await deviceNode.driver.lux();
                msg.payload = { lux, overrange: deviceNode.driver.isOverrange() };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('max44009', MAX44009ReadNode);
};
