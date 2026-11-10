'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Mcp4xxxFull }   = require('periph/src/chips/adc_dac/mcp4xxx');
    const { GpioOutputPin } = require('periph/src/connection/output_pin');

    function Mcp4xxxDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            let hvc = null;
            if (config.hvcPin !== undefined && config.hvcPin !== '') {
                // opengpio is only needed when a high-voltage control pin is configured.
                const { Default } = require('opengpio');
                hvc = new GpioOutputPin(Default.output({ chip: parseInt(config.gpioChip || 0), line: parseInt(config.hvcPin) }));
            }
            node.driver = new Mcp4xxxFull(connection, config.part || 'MCP4532',
                parseFloat(config.rab || '10000'), parseFloat(config.rwiper || '75'), hvc);
            node.connection = connection;
            node.driver.init().catch(function(e) { node.error('MCP4XXX init failed: ' + e.message); });
        } catch (e) {
            node.error('MCP4XXX init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mcp4xxx-device', Mcp4xxxDeviceNode);

    async function snapshot(d, channel) {
        return {
            channel:    channel,
            code:       await d.getWiper(channel),
            resistance: await d.getResistance(channel)
        };
    }

    function Mcp4xxxNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MCP4XXX device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = msg.payload;
                if (p && typeof p === 'object' && p.data !== undefined) {
                    const index = parseInt(p.data.index);
                    if (p.data.value !== undefined) await d.writeData(index, parseInt(p.data.value));
                    msg.payload = { index: index, value: await d.readData(index) };
                } else if (p && typeof p === 'object' && (p.code !== undefined || p.ohms !== undefined ||
                           p.ratio !== undefined || p.step !== undefined || p.shutdown !== undefined ||
                           p.store !== undefined)) {
                    const channel = p.channel !== undefined ? parseInt(p.channel) : 0;
                    if (p.code !== undefined) {
                        await d.setWiper(parseInt(p.code), channel);
                    } else if (p.ohms !== undefined) {
                        await d.setResistance(parseFloat(p.ohms), channel);
                    } else if (p.ratio !== undefined) {
                        await d.setRatio(parseFloat(p.ratio), channel);
                    } else if (p.step !== undefined) {
                        const n = parseInt(p.step);
                        if (n >= 0) await d.increment(n, channel); else await d.decrement(-n, channel);
                    } else if (p.shutdown !== undefined) {
                        if (p.shutdown) await d.shutdown(channel); else await d.resume(channel);
                    }
                    const out = await snapshot(d, channel);
                    if (p.shutdown !== undefined) out.shutdown = await d.isShutdown(channel);
                    if (p.store) out.stored = await d.storeWiper(channel);
                    msg.payload = out;
                } else {
                    const wiper = [];
                    const resistance = [];
                    for (let c = 0; c < d.channels(); c++) {
                        wiper.push(await d.getWiper(c));
                        resistance.push(await d.getResistance(c));
                    }
                    msg.payload = { wiper: wiper, resistance: resistance };
                    if (d.isNonvolatile()) msg.payload.status = await d.readStatus();
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-mcp4xxx', Mcp4xxxNode);
};
