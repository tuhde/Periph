'use strict';

module.exports = function(RED) {
    const { I2CTransport }   = require('periph/src/transport/i2c');
    const { Pcf8575Full }   = require('periph/src/chips/io_expander/pcf8575');

    function Pcf8575Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(
                parseInt(config.bus),
                parseInt(config.address, 16)
            );
            node.chip      = new Pcf8575Full(transport);
            node.transport = transport;
        } catch (e) {
            node.error('PCF8575 init failed: ' + e.message);
        }

        node.on('input', function(msg, send, done) {
            if (!node.chip) { done(new Error('PCF8575 not initialised')); return; }
            try {
                const p = msg.payload;
                if (p && typeof p.pin === 'number' && typeof p.value === 'number') {
                    node.chip._setPin(p.pin, p.value ? 1 : 0);
                    done();
                } else if (p && typeof p.pin === 'number') {
                    const port = Math.floor(p.pin / 8);
                    const bit = p.pin % 8;
                    msg.payload = { pin: p.pin, value: (node.chip.readPort(port) >> bit) & 1 };
                    send(msg);
                    done();
                } else if (p && typeof p.port === 'number') {
                    msg.payload = { port: p.port, value: node.chip.readPort(p.port) };
                    send(msg);
                    done();
                } else {
                    done(new Error('msg.payload must be { pin, value }, { pin }, or { port }'));
                }
            } catch (e) { done(e); }
        });

        node.on('close', function() {
            if (node.transport) node.transport.close && node.transport.close();
        });
    }

    RED.nodes.registerType('periph-pcf8575', Pcf8575Node);
};