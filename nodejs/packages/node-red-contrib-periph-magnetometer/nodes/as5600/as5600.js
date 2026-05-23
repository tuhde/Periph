'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { AS5600Full }   = require('periph/src/chips/magnetometer/as5600');

    function AS5600DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new AS5600Full(transport);
            node.transport = transport;
        } catch (e) {
            node.error('AS5600 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('as5600-device', AS5600DeviceNode);

    function AS5600ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AS5600 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    angle:           d.angle(),
                    raw:             d.angleRaw(),
                    magnet_detected: d.isMagnetDetected()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-as5600', AS5600ReadNode);
};
