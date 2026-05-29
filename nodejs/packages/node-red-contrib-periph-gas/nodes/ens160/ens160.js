'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { ENS160Full }   = require('periph/src/chips/gas/ens160');

    function ENS160DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const addr = parseInt(config.addr) || 0x52;
            const transport = new I2CTransport(parseInt(config.bus), addr);
            node.driver    = new ENS160Full(transport);
            node.transport = transport;
        } catch (e) {
            node.error('ENS160 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('ens160-device', ENS160DeviceNode);

    function ENS160ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No ENS160 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const status = d.status();
                if (status !== 0) {
                    node.warn('ENS160 data not valid (status=' + status + ')');
                    msg.payload = { status: status };
                    send(msg);
                    done();
                    return;
                }
                const data = d.readAirQuality();
                msg.payload = {
                    aqi:       data.aqi,
                    tvocPpb:   data.tvocPpb,
                    eco2Ppm:   data.eco2Ppm,
                    status:    0
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-ens160', ENS160ReadNode);
};
