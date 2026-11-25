'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AD5251Full } = require('periph/src/chips/adc_dac/ad5251');

    function AD5251DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const rAb = parseFloat(config.rab) || 50000.0;
            const address = config.address ? parseInt(config.address, 16) : AD5251Full.I2C_ADDRESS;
            const rWiper = config.rwiper === '' || config.rwiper === undefined ? 75.0 : parseFloat(config.rwiper);
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new AD5251Full(connection, rAb, rWiper);
            node.connection = connection;
        } catch (e) {
            node.error('AD5251 init failed: ' + e.message);
        }

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
    RED.nodes.registerType('ad5251-device', AD5251DeviceNode);

    function AD5251ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                const p = msg.payload;
                if (p && typeof p === 'object' && p.store === true) {
                    await deviceNode.driver.store();
                    msg.payload = { stored: true };
                } else if (p && typeof p === 'object' && (p.channel === 0 || p.channel === 1)) {
                    if (typeof p.code === 'number') {
                        await deviceNode.driver.setWiper(p.channel, p.code);
                    } else if (typeof p.ohms === 'number') {
                        await deviceNode.driver.setResistance(p.channel, p.ohms);
                    } else {
                        done(new Error('payload needs code or ohms'));
                        return;
                    }
                    const code = await deviceNode.driver.getWiper(p.channel);
                    const resistance = await deviceNode.driver.getResistance(p.channel);
                    msg.payload = { channel: p.channel, code, resistance };
                } else {
                    const c1 = await deviceNode.driver.getWiper(0);
                    const c2 = await deviceNode.driver.getWiper(1);
                    const r1 = await deviceNode.driver.getResistance(0);
                    const r2 = await deviceNode.driver.getResistance(1);
                    msg.payload = { wiper: [c1, c2], resistance: [r1, r2] };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ad5251', AD5251ReadNode);
};
