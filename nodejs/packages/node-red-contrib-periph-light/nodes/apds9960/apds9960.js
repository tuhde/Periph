'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { APDS9960Minimal } = require('periph/src/chips/light/apds9960');

    function APDS9960DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const bus = parseInt(config.bus);
            const addr = parseInt(config.address, 16);
            const transport = new I2CTransport(bus, addr);
            node.driver    = new APDS9960Minimal(transport);
            node.transport = transport;
        } catch (e) {
            node.error('APDS-9960 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('apds9960-device', APDS9960DeviceNode);

    function APDS9960ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No APDS-9960 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const { clear, red, green, blue } = d.color();
                msg.payload = { clear, red, green, blue };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('apds9960', APDS9960ReadNode);
};
