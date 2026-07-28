'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { INA3221Full }   = require('periph/src/chips/power/ina3221');

    function INA3221DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            const rShunt = config.rShunt || 0.1;
            node.driver     = new INA3221Full(connection, rShunt);
            node.connection = connection;
        } catch (e) {
            node.error('INA3221 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ina3221-device', INA3221DeviceNode);

    function INA3221ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No INA3221 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const payload = {};
                for (const ch of [1, 2, 3]) {
                    payload['ch' + ch] = {
                        voltage:      await d.voltage(ch),
                        shuntVoltage: await d.shuntVoltage(ch),
                        current:      await d.current(ch),
                        power:        await d.power(ch)
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
    RED.nodes.registerType('periph-ina3221', INA3221ReadNode);
};
