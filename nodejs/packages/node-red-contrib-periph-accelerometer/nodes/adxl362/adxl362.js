'use strict';

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { ADXL362Full }   = require('periph/src/chips/accelerometer/adxl362');

    function ADXL362DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new SPIConnection(
                parseInt(config.bus),
                parseInt(config.device),
                { mode: 0, maxSpeedHz: 8_000_000 },
            );
            node.driver     = new ADXL362Full(connection);
            node.connection = connection;
            if (config.range)    node.driver.setRange(parseInt(config.range));
            if (config.dataRate) node.driver.setOdr(parseFloat(config.dataRate));
            if (config.noiseMode) node.driver.setNoiseMode(parseInt(config.noiseMode));
            if (config.halfBw)    node.driver.setHalfBandwidth(config.halfBw === 'true');
        } catch (e) {
            node.error('ADXL362 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('adxl362-device', ADXL362DeviceNode);

    function ADXL362ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No ADXL362 device configured', msg);
                done();
                return;
            }
            try {
                const r = await node.device.driver.read();
                const awakeFlag = await node.device.driver.awake();
                const t = await node.device.driver.temperature();
                msg.payload = { x: r.x, y: r.y, z: r.z, temperature: t, awake: awakeFlag };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('adxl362', ADXL362ReadNode);
};