'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LM25066Minimal } = require('periph/src/chips/power/lm25066');

    function LM25066DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new LM25066Minimal(connection, parseFloat(config.rsense), config.climit === '46');
            node.connection = connection;
        } catch (e) {
            node.error('LM25066 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('lm25066-device', LM25066DeviceNode);

    function LM25066ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LM25066 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                msg.payload = {
                    voltage_in_v:  await d.voltageIn(),
                    voltage_out_v: await d.voltageOut(),
                    current_a:     await d.current(),
                    power_w:       await d.power(),
                    temperature_c: await d.temperature()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('lm25066', LM25066ReadNode);
};
