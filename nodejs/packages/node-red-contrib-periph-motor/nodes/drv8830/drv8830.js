'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { DRV8830Full } = require('periph/src/chips/motor/drv8830');

    function DRV8830DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || DRV8830Full.I2C_ADDRESS;
            const connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new DRV8830Full(connection);
            node.connection = connection;
            node.driver.init().catch((e) => node.error('DRV8830 init failed: ' + e.message));
        } catch (e) {
            node.error('DRV8830 init failed: ' + e.message);
        }

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.stop();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('drv8830-device', DRV8830DeviceNode);

    function DRV8830ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(); return; }
            try {
                const p = msg.payload;
                if (p && typeof p === 'object') {
                    if (p.clear_fault === true) { await deviceNode.driver.clearFault(); done(); return; }
                    if (p.brake === true) { await deviceNode.driver.brake(); done(); return; }
                    if (p.stop === true) { await deviceNode.driver.stop(); done(); return; }
                    if (typeof p.voltage === 'number') { await deviceNode.driver.drive(p.voltage); done(); return; }
                }
                msg.payload = await deviceNode.driver.readFault();
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('drv8830', DRV8830ReadNode);
};
