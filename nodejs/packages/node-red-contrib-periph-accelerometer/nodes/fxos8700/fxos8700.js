'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { FXOS8700Full }  = require('periph/src/chips/accelerometer/fxos8700');

    function FXOS8700DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x1E;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.driver     = new FXOS8700Full(connection);
            node.connection = connection;
            node.declination = parseFloat(config.declination) || 0;
            // The driver initialises itself asynchronously; apply the
            // configured range and system data rate once it is ready.
            node.ready = (async () => {
                await node.driver.init();
                if (config.range) {
                    await node.driver.setRange(parseInt(config.range));
                }
                if (config.dataRate) {
                    await node.driver.setDataRate(parseFloat(config.dataRate));
                }
            })();
            node.ready.catch(e => {
                node.error('FXOS8700 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('FXOS8700 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('fxos8700-device', FXOS8700DeviceNode);

    function FXOS8700ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No FXOS8700 device configured', msg);
                done();
                return;
            }
            try {
                if (node.device.ready) await node.device.ready;
                const d = node.device.driver;
                const { accel, mag } = await d.readHybrid();
                let heading = null;
                try { heading = await d.heading(node.device.declination); } catch (e) { heading = null; }
                let temperature = null;
                try { temperature = await d.readTemperature(); } catch (e) { temperature = null; }
                msg.payload = {
                    accel: { x: accel.x, y: accel.y, z: accel.z },
                    mag: { x: mag.x, y: mag.y, z: mag.z },
                    heading: heading,
                    temperature: temperature
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-fxos8700', FXOS8700ReadNode);
};
