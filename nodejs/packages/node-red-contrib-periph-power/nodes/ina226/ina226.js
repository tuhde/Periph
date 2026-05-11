'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { INA226Full }   = require('periph/src/chips/power/ina226');

    function INA226DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new INA226Full(transport, parseFloat(config.rShunt), parseFloat(config.maxCurrent));
            node.transport = transport;
        } catch (e) {
            node.error('INA226 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('ina226-device', INA226DeviceNode);

    function INA226ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No INA226 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    voltage:      d.voltage(),
                    current:      d.current(),
                    power:        d.power(),
                    shuntVoltage: d.shuntVoltage()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ina226', INA226ReadNode);
};
