'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AD5243Full } = require('periph/src/chips/adc_dac/ad5243');

    function AD5243Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const rAb = parseFloat(config.rab) || 10000.0;
            const rWiper = config.rwiper === '' || config.rwiper === undefined ? 160.0 : parseFloat(config.rwiper);
            const connection = new I2CConnection(parseInt(config.bus, 10), AD5243Full.I2C_ADDRESS);
            node.driver = new AD5243Full(connection, rAb, rWiper);
            node.connection = connection;
        } catch (e) {
            node.error('AD5243 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                const p = msg.payload;
                if (p && typeof p === 'object' && (p.channel === 0 || p.channel === 1)) {
                    if (typeof p.code === 'number') {
                        await node.driver.setWiper(p.channel, p.code);
                    } else if (typeof p.ohms === 'number') {
                        await node.driver.setResistance(p.channel, p.ohms);
                    } else {
                        done(new Error('payload needs code or ohms'));
                        return;
                    }
                    const code = await node.driver.getWiper(p.channel);
                    const resistance = await node.driver.getResistance(p.channel);
                    msg.payload = { channel: p.channel, code, resistance };
                } else {
                    const c1 = await node.driver.getWiper(0);
                    const c2 = await node.driver.getWiper(1);
                    const r1 = await node.driver.getResistance(0);
                    const r2 = await node.driver.getResistance(1);
                    msg.payload = { wiper: [c1, c2], resistance: [r1, r2] };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-ad5243', AD5243Node);
};
