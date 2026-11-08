'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AXP2101Full }   = require('periph/src/chips/power/axp2101');

    const CHARGE_STATES = ['trickle', 'pre', 'cc', 'cv', 'done', 'none'];

    function AXP2101DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.connection = connection;
            node.driver     = new AXP2101Full(connection);
            // Optional charge profile, written once on deploy (after the init sequence has finished).
            node.driver.ready.then(async function() {
                if (config.chargeVoltage) await node.driver.setChargeVoltage(parseFloat(config.chargeVoltage));
                if (config.chargeCurrent) await node.driver.setChargeCurrent(parseFloat(config.chargeCurrent));
            }).catch(function(e) {
                node.error('AXP2101 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('AXP2101 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('axp2101-device', AXP2101DeviceNode);

    function AXP2101ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AXP2101 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const r = await d.readAll();
                msg.payload = {
                    battery_voltage: r.batteryVoltage,
                    battery_percent: r.batteryPercent,
                    charge_state:    CHARGE_STATES[r.chargeState] || 'none',
                    vbus_present:    r.vbusPresent,
                    vbus_voltage:    r.vbusVoltage,
                    system_voltage:  r.systemVoltage,
                    die_temperature: r.dieTemperature
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('axp2101', AXP2101ReadNode);
};
