'use strict';

module.exports = function(RED) {
    const { DHTxxTransport }   = require('periph/src/transport/dhtxx');
    const { DHT11Full }        = require('periph/src/chips/humidity/dht11');

    function DHT11DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new DHTxxTransport(parseInt(config.dataPin, 10));
            const retries   = parseInt(config.retries, 10) || 3;
            node.driver     = new DHT11Full(transport, retries);
            node.transport  = transport;
        } catch (e) {
            node.error('DHT11 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('dht11-device', DHT11DeviceNode);

    function DHT11ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No DHT11 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const r = d.read();
                msg.payload = {
                    temperature_c: r.temperature,
                    humidity_rh:   r.humidity
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('dht11', DHT11ReadNode);
};
