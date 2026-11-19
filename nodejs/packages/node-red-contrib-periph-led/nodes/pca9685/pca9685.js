'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { PCA9685Full }   = require('periph/src/chips/led/pca9685');

    function PCA9685DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new PCA9685Full(connection);
            node.connection = connection;
            node.driver.ready
                .then(function() {
                    const hz = parseFloat(config.frequency);
                    if (hz > 0) return node.driver.setFrequency(hz);
                })
                .catch(function(e) { node.error('PCA9685 init failed: ' + e.message); });
        } catch (e) {
            node.error('PCA9685 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('pca9685-device', PCA9685DeviceNode);

    function PCA9685Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No PCA9685 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = msg.payload || {};
                const out = {};
                if (typeof p.frequency === 'number') {
                    await d.setFrequency(p.frequency);
                    out.frequency = p.frequency;
                }
                if (p.channel !== undefined && typeof p.duty === 'number') {
                    if (p.channel === 'all') await d.setAllDuty(p.duty);
                    else await d.setDuty(p.channel, p.duty);
                    out.channel = p.channel;
                    out.duty = p.duty;
                }
                if (p.channel !== undefined && typeof p.pulse_width === 'number') {
                    if (p.channel === 'all') {
                        for (let ch = 0; ch < 16; ch++) await d.setPulseWidth(ch, p.pulse_width);
                    } else {
                        await d.setPulseWidth(p.channel, p.pulse_width);
                    }
                    out.channel = p.channel;
                    out.pulse_width = p.pulse_width;
                }
                if (Object.keys(out).length === 0) {
                    node.error('Payload must have { channel, duty }, { channel, pulse_width } or { frequency }', msg);
                } else {
                    msg.payload = out;
                    send(msg);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-pca9685', PCA9685Node);
};
