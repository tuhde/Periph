'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AD5248Full }    = require('periph/src/chips/adc_dac/ad5248');

    function AD5248DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new AD5248Full(connection, parseFloat(config.rab || '10000'), parseFloat(config.rwiper || '160'));
            node.connection = connection;
        } catch (e) {
            node.error('AD5248 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ad5248-device', AD5248DeviceNode);

    function AD5248Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AD5248 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = msg.payload;
                if (p && typeof p === 'object' && p.channel !== undefined &&
                    (p.code !== undefined || p.ohms !== undefined)) {
                    const channel = parseInt(p.channel);
                    if (p.code !== undefined) {
                        await d.setWiper(channel, parseInt(p.code));
                    } else {
                        await d.setResistance(channel, parseFloat(p.ohms));
                    }
                    msg.payload = {
                        channel:    channel,
                        code:       await d.getWiper(channel),
                        resistance: await d.getResistance(channel)
                    };
                } else {
                    msg.payload = {
                        wiper:      [await d.getWiper(0), await d.getWiper(1)],
                        resistance: [await d.getResistance(0), await d.getResistance(1)]
                    };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-ad5248', AD5248Node);
};
