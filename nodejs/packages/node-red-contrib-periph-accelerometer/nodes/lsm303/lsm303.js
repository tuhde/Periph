'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LSM303Full } = require('periph/src/chips/accelerometer/lsm303');

    function LSM303DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const bus = parseInt(config.bus, 10);
            node.accelConnection = new I2CConnection(bus, 0x19);
            node.magConnection = new I2CConnection(bus, 0x1E);
            node.driver = new LSM303Full(node.accelConnection, node.magConnection);
            node.ready = (async () => {
                await node.driver.init();
                if (config.accelRange) await node.driver.setRange(parseInt(config.accelRange, 10));
                if (config.accelDataRate) await node.driver.setDataRate(parseInt(config.accelDataRate, 10));
                if (config.magRange) await node.driver.setMagRange(parseInt(config.magRange, 10));
                if (config.magDataRate) await node.driver.setMagDataRate(parseFloat(config.magDataRate));
            })();
            node.ready.catch(e => node.error('LSM303 init failed: ' + e.message));
        } catch (e) {
            node.error('LSM303 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.accelConnection) node.accelConnection.close();
            if (node.magConnection) node.magConnection.close();
        });
    }
    RED.nodes.registerType('lsm303-device', LSM303DeviceNode);

    function LSM303ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LSM303 device configured', msg);
                done();
                return;
            }
            try {
                if (node.device.ready) await node.device.ready;
                const d = node.device.driver;
                const acceleration = await d.readAcceleration();
                const magnetic = await d.readMagnetic();
                const heading = await d.heading(true);
                let temperatureDelta = null;
                try { temperatureDelta = await d.readTemperatureDelta(); } catch (e) { temperatureDelta = null; }
                msg.payload = { acceleration, magnetic, heading, temperature_delta: temperatureDelta };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-lsm303', LSM303ReadNode);
};
