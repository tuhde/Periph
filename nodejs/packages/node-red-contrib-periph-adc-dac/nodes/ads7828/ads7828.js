'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ADS7828Full } = require('periph/src/chips/adc_dac/ads7828');

    function ADS7828Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const address = parseInt(config.address, 16) || ADS7828Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            const vref = config.reference === 'external' ? parseFloat(config.vref) : null;
            node.driver = new ADS7828Full(connection, vref);
            node.connection = connection;
            node.ready = node.driver.init();
        } catch (e) {
            node.error('ADS7828 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                await node.ready;
                const d = node.driver;
                const diff = typeof msg.differential === 'string' ? /^([0-7])-([0-7])$/.exec(msg.differential) : null;
                if (diff) {
                    const a = parseInt(diff[1], 10);
                    const b = parseInt(diff[2], 10);
                    if ((a >> 1) !== (b >> 1) || a === b) throw new Error('differential must pair 0-1, 2-3, 4-5 or 6-7');
                    const pair = a >> 1;
                    const inverted = (a & 1) === 1;
                    const raw = await d.readDifferentialRaw(pair, inverted);
                    msg.payload = { pair, inverted, raw, voltage: raw / 4096 * d.getReference() };
                } else if (typeof msg.channel === 'number') {
                    const raw = await d.readRaw(msg.channel);
                    msg.payload = { channel: msg.channel, raw, voltage: raw / 4096 * d.getReference() };
                } else {
                    const raw = await d.readScan([0, 1, 2, 3, 4, 5, 6, 7]);
                    msg.payload = { channels: raw.map((r) => r / 4096 * d.getReference()), raw };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.powerDown();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-ads7828', ADS7828Node);
};
