'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ADS1115Full }       = require('periph/src/chips/adc_dac/ads1115');

    function ADS1115Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new ADS1115Full(connection);
            node.connection = connection;
            node.ready = (async () => {
                await node.driver.setGain(parseInt(config.gain));
                await node.driver.setDataRate(parseInt(config.dataRate));
            })();
        } catch (e) {
            node.error('ADS1115 init failed: ' + e.message);
        }
        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                await node.ready;
                const driver = node.driver;
                const pairs = { '0-1': 0, '0-3': 1, '1-3': 2, '2-3': 3 };
                if (msg.differential !== undefined) {
                    const pair = pairs[msg.differential];
                    if (pair === undefined) throw new Error('msg.differential must be 0-1, 0-3, 1-3 or 2-3');
                    const raw = await driver.readDifferentialRaw(pair);
                    msg.payload = { pair: msg.differential, raw, voltage: raw * driver.lsb() };
                } else {
                    const channel = msg.channel !== undefined ? parseInt(msg.channel) : parseInt(config.channel);
                    const raw = await driver.readRaw(channel);
                    msg.payload = { channel: Math.max(0, Math.min(3, channel)), raw, voltage: raw * driver.lsb() };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-ads1115', ADS1115Node);
};
