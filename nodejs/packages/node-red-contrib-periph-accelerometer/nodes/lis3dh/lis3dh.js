'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LIS3DHFull }   = require('periph/src/chips/accelerometer/lis3dh');

    function LIS3DHDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x18;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.driver     = new LIS3DHFull(connection);
            node.connection = connection;
            // The driver initialises itself asynchronously. The mode has to be
            // selected before the data rate, because the legal rates depend on
            // the operating mode (1.62/5.376 kHz are low-power only, 1.344 kHz
            // is not available in low-power mode).
            node.ready = (async () => {
                await node.driver.init();
                if (config.mode !== undefined && config.mode !== '') {
                    await node.driver.setMode(parseInt(config.mode));
                }
                if (config.range) {
                    await node.driver.setRange(parseInt(config.range));
                }
                if (config.dataRate) {
                    await node.driver.setDataRate(parseInt(config.dataRate));
                }
            })();
            node.ready.catch(e => {
                node.error('LIS3DH init failed: ' + e.message);
            });
        } catch (e) {
            node.error('LIS3DH init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('lis3dh-device', LIS3DHDeviceNode);

    function LIS3DHReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LIS3DH device configured', msg);
                done();
                return;
            }
            try {
                if (node.device.ready) await node.device.ready;
                const d = node.device.driver;
                const { x, y, z } = await d.read();
                let temperatureDelta = null;
                try { temperatureDelta = await d.readTemperatureDelta(); } catch (e) { temperatureDelta = null; }
                msg.payload = { x, y, z, temperature_delta: temperatureDelta };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-lis3dh', LIS3DHReadNode);
};
