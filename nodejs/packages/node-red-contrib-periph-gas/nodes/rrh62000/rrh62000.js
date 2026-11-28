'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { RRH62000Full } = require('periph/src/chips/gas/rrh62000');

    function RRH62000DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), 0x69);
            node.connection = connection;
            node.driver = new RRH62000Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const average = parseInt(config.movingAverage);
                if (!isNaN(average) && average !== 10) await node.driver.setMovingAverage(average);
                const fan = parseInt(config.fanSpeed);
                if (!isNaN(fan) && fan !== 86) await node.driver.setFanSpeed(fan);
            })();
            node.ready.catch((e) => {
                node.error('RRH62000 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('RRH62000 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('rrh62000-device', RRH62000DeviceNode);

    function RRH62000ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};
        let busy = Promise.resolve();

        node.on('input', function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('RRH62000 not initialised', msg);
                done();
                return;
            }
            // The module allows one access per second; serialise overlapping messages.
            busy = busy.then(async () => {
                try {
                    await deviceNode.ready;
                    msg.payload = await deviceNode.driver.read();
                    send(msg);
                    done();
                } catch (e) {
                    done(e);
                }
            });
        });
    }
    RED.nodes.registerType('rrh62000', RRH62000ReadNode);
};
