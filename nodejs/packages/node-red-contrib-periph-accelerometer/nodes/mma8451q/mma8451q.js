'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MMA8451QFull }   = require('periph/src/chips/accelerometer/mma8451q');

    function MMA8451QDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.orientation = config.orientation === 'true';
        try {
            const addr = parseInt(config.address, 16) || 0x1C;
            const connection = new I2CConnection(parseInt(config.bus), addr);
            node.driver     = new MMA8451QFull(connection);
            node.connection = connection;

            // The init sequence is async; the constructor starts it
            // unawaited. Await it here so the configuration below runs
            // against a chip that is already in ACTIVE mode.
            node.ready = node.driver.init().then(async () => {
                if (config.range)    await node.driver.setRange(parseInt(config.range, 10));
                if (config.rate)     await node.driver.setDataRate(parseFloat(config.rate));
                if (config.oversampling !== undefined && config.oversampling !== '') {
                    await node.driver.setOversampling(parseInt(config.oversampling, 10));
                }
                if (node.orientation) {
                    await node.driver.setOrientation(true, 100);
                }
            }).catch(e => {
                node.error('MMA8451Q init failed: ' + e.message);
            });
        } catch (e) {
            node.error('MMA8451Q init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mma8451q-device', MMA8451QDeviceNode);

    function MMA8451QReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);
        // The orientation detector is enabled in the device config node.
        const withOrientation = node.device && node.device.orientation === true;

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MMA8451Q device configured', msg);
                done();
                return;
            }
            try {
                await node.device.ready;
                const [x, y, z] = await node.device.driver.read();
                const payload = { x, y, z };
                if (withOrientation) {
                    const o = await node.device.driver.readOrientation();
                    payload.orientation = {
                        portrait_landscape: o.portraitLandscape,
                        back: o.back,
                    };
                }
                msg.payload = payload;
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-mma8451q', MMA8451QReadNode);
};
