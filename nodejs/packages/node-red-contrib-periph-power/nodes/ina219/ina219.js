'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { INA219Full }   = require('periph/src/chips/power/ina219');

    function INA219DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new INA219Full(connection, parseFloat(config.rShunt), parseFloat(config.maxCurrent));
            node.connection = connection;
        } catch (e) {
            node.error('INA219 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ina219-device', INA219DeviceNode);

    function INA219ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No INA219 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    voltage:      await d.voltage(),
                    current:      await d.current(),
                    power:        await d.power(),
                    shuntVoltage: await d.shuntVoltage()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ina219', INA219ReadNode);
};
