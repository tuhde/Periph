'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ADE7953Full }   = require('periph/src/chips/power/ade7953');

    function ADE7953Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(
                parseInt(config.bus, 10) || 1,
                parseInt(config.addr, 16) || 0x38
            );
            node.driver = new ADE7953Full(
                connection,
                parseFloat(config.voltageGain) || 251.0,
                parseFloat(config.currentGain) || 30.0
            );
            node.connection = connection;
        } catch (e) {
            node.error('ADE7953 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('No ADE7953 driver', msg);
                done();
                return;
            }
            try {
                const d = node.driver;
                const voltage = await d.voltage();
                const current = await d.current();
                const activePower = await d.activePower();
                const activeEnergy = await d.activeEnergy();
                msg.payload = {
                    voltage: voltage,
                    current: current,
                    active_power: activePower,
                    active_energy: activeEnergy,
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-ade7953', ADE7953Node);
};