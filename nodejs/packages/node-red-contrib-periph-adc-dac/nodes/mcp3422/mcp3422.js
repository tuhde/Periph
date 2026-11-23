'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MCP3422Full } = require('periph/src/chips/adc_dac/mcp3422');

    function MCP3422DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address || '0x68', 16);
            node.connection = new I2CConnection(parseInt(config.bus, 10), address);
            node.driver = new MCP3422Full(node.connection);
            node.ready = (async () => {
                await node.driver.init();
                await node.driver.setResolution(parseInt(config.resolution || '16', 10));
                await node.driver.setGain(parseInt(config.gain || '1', 10));
            })();
            node.ready.then(() => node.status({ fill: 'green', shape: 'dot', text: 'I2C' }))
                .catch((e) => node.error('MCP3422 init failed: ' + e.message));
        } catch (e) {
            node.error('MCP3422 init failed: ' + e.message);
        }

        node.on('close', async function(removed, done) {
            try {
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('mcp3422-device', MCP3422DeviceNode);

    function MCP3422ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(new Error('MCP3422 not initialised')); return; }
            try {
                await deviceNode.ready;
                // msg.gain / msg.resolution apply before the conversion and persist.
                if (msg.resolution !== undefined) await deviceNode.driver.setResolution(Number(msg.resolution));
                if (msg.gain !== undefined) await deviceNode.driver.setGain(Number(msg.gain));
                const lsb = deviceNode.driver.lsb();
                const gain = deviceNode.driver.getGain();
                if (msg.channel !== undefined) {
                    const channel = Number(msg.channel) < 1 ? 0 : 1;
                    const raw = await deviceNode.driver.readRaw(channel);
                    msg.payload = { channel, raw, voltage: raw * lsb / gain };
                } else {
                    const raw0 = await deviceNode.driver.readRaw(0);
                    const raw1 = await deviceNode.driver.readRaw(1);
                    msg.payload = { channels: [raw0 * lsb / gain, raw1 * lsb / gain], raw: [raw0, raw1] };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('mcp3422', MCP3422ReadNode);
};
