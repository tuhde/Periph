'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { CAT5171Full } = require('periph/src/chips/adc_dac/cat5171');

    function CAT5171Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const rAb = parseFloat(config.rab) || 100000.0;
            const rWiper = config.rwiper === '' || config.rwiper === undefined ? 50.0 : parseFloat(config.rwiper);
            const address = config.address === '0x2D' ? CAT5171Full.I2C_ADDRESS_AD0_HIGH : CAT5171Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new CAT5171Full(connection, rAb, rWiper);
            node.connection = connection;
        } catch (e) {
            node.error('CAT5171 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                const p = msg.payload;
                if (p && typeof p === 'object' && typeof p.shutdown === 'boolean') {
                    if (p.shutdown) await node.driver.shutdown();
                    else await node.driver.resume();
                    done();
                    return;
                }
                if (p && typeof p === 'object' && (typeof p.code === 'number' || typeof p.ohms === 'number')) {
                    if (typeof p.code === 'number') await node.driver.setWiper(p.code);
                    else await node.driver.setResistance(p.ohms);
                    const code = await node.driver.getWiper();
                    const resistance = await node.driver.getResistance();
                    msg.payload = { code, resistance };
                } else {
                    const wiper = await node.driver.getWiper();
                    const resistance = await node.driver.getResistance();
                    msg.payload = { wiper, resistance };
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
    RED.nodes.registerType('periph-cat5171', CAT5171Node);
};
