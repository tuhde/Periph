'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { PCA9541Full } = require('periph/src/chips/other/pca9541');

    function PCA9541Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const address = parseInt(config.address || '0x70', 16);
            node.connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new PCA9541Full(node.connection);
            node.ready = node.driver.init();
            node.ready.then(() => node.status({ fill: 'green', shape: 'dot', text: 'I2C' }))
                .catch((e) => node.error('PCA9541 init failed: ' + e.message));
        } catch (e) {
            node.error('PCA9541 init failed: ' + e.message);
        }

        // Optional INT line of THIS master: emit { interrupt, bus_lost, ... } from the callback.
        if (node.driver && config.intPin !== '' && config.intPin !== undefined) {
            (async () => {
                try {
                    const { Default } = require('opengpio');
                    const { PollingInputPin } = require('periph/src/connection/input_pin');
                    const line = Default.input({ chip: parseInt(config.gpioChip || 0, 10), line: parseInt(config.intPin, 10) });
                    const intPin = new PollingInputPin(() => (line.value ? 1 : 0), 20);
                    await node.ready;
                    await node.driver.onInterrupt((status) => {
                        node.send({ payload: {
                            interrupt: status,
                            int_in: Boolean(status & 0x01),
                            bus_init: Boolean(status & 0x02),
                            bus_not_idle: Boolean(status & 0x04),
                            bus_lost: Boolean(status & 0x08),
                            test: Boolean(status & 0x40),
                            other_test: Boolean(status & 0x80),
                        } });
                    }, { intPin });
                } catch (e) {
                    node.error('PCA9541 INT setup failed: ' + e.message);
                }
            })();
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(new Error('PCA9541 not initialised')); return; }
            try {
                await node.ready;
                const p = msg.payload;
                const command = p && typeof p === 'object' ? p.command : undefined;
                if (command === 'acquire') await node.driver.acquire(p.busInit === true);
                else if (command === 'release') await node.driver.release();
                else if (command === 'recover') await node.driver.recoverBus();
                else if (command === 'disconnect') await node.driver.disconnect();
                const st = await node.driver.status();
                msg.payload = { control: st.hasControl && st.connected, connected: st.connected };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.driver) await node.driver.offInterrupt();
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-pca9541', PCA9541Node);
};
