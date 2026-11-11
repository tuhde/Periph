'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Pca9536Full } = require('periph/src/chips/io_expander/pca9536');

    function Pca9536Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            node.connection = new I2CConnection(parseInt(config.bus), 0x41);
            node.chip = new Pca9536Full(node.connection);
            node.chip.ready.catch((e) => node.error('PCA9536 init failed: ' + e.message));
            node.status({ fill: 'green', shape: 'dot', text: 'I2C' });
        } catch (e) {
            node.error('PCA9536 init failed: ' + e.message);
        }

        // Optional change polling (the chip has no interrupt pin): emit { port, changed, value }.
        const interval = parseFloat(config.pollInterval);
        if (node.chip && interval > 0) {
            node.chip.ready.then(() => {
                node.chip.watchChanges(async (changed) => {
                    const value = await node.chip.readPort();
                    node.send({ payload: { port: 0, changed, value } });
                }, interval / 1000);
            }).catch((e) => node.error('PCA9536 polling setup failed: ' + e.message));
        }

        node.on('input', async function(msg, send, done) {
            if (!node.chip) { done(new Error('PCA9536 not initialised')); return; }
            try {
                const p = msg.payload;
                if (p && typeof p.pin === 'number' && (p.pin < 0 || p.pin > 3)) {
                    done(new Error('pin must be 0-3'));
                } else if (p && typeof p.pin === 'number' && typeof p.value === 'number') {
                    const pin = node.chip.pin(p.pin);
                    if ((node.chip._direction >> p.pin) & 1) await pin.setDirection('out');
                    await pin.write(p.value ? 1 : 0);
                    done();
                } else if (p && typeof p.pin === 'number') {
                    msg.payload = { pin: p.pin, value: ((await node.chip.readPort()) >> p.pin) & 1 };
                    send(msg);
                    done();
                } else if (p && typeof p.port === 'number') {
                    msg.payload = { port: p.port, value: await node.chip.readPort() };
                    send(msg);
                    done();
                } else {
                    done(new Error('msg.payload must be { pin, value }, { pin }, or { port }'));
                }
            } catch (e) { done(e); }
        });

        node.on('close', async function() {
            if (node.chip) node.chip.unwatchChanges();
            if (node.connection) await node.connection.close();
        });
    }

    RED.nodes.registerType('periph-pca9536', Pca9536Node);
};
