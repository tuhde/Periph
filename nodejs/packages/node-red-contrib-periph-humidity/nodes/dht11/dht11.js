'use strict';

module.exports = function(RED) {
    const { DHT11Pin } = require('periph/src/transport/dht11');
    const { DHT11Full } = require('periph/src/chips/humidity/dht11');

    function DHT11Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const pin = parseInt(config.dataPin);
            const retries = parseInt(config.retries || 3);
            node.transport = new DHT11Pin(pin);
            node.driver    = new DHT11Full(node.transport);
            node._retries  = retries;
        } catch (e) {
            node.error('DHT11 init failed: ' + e.message);
        }

        node.on('input', function(msg, send, done) {
            if (!node.driver) { done(); return; }
            try {
                const r = node.driver.readRetry(node._retries);
                msg.payload = {
                    temperature_c: r.temperature_c,
                    humidity_rh:   r.humidity_rh
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }

    RED.nodes.registerType('periph-dht11', DHT11Node);
};
