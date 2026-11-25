"use strict";

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { GpioOutputPin } = require('periph/src/connection/output_pin');
    const { SDSPIFull } = require('periph/src/chips/memory/sd_spi');

    function SdSpiDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = null;
        try {
            // The kernel cannot hold chip select across calls, so CS is a GPIO (SPI_NO_CS).
            const { Default } = require('opengpio');
            const csPin = new GpioOutputPin(Default.output({ chip: config.gpiochip || '/dev/gpiochip0', line: parseInt(config.csLine) }));
            const connection = new SPIConnection(parseInt(config.bus), parseInt(config.device), {
                mode: 0,
                maxSpeedHz: 400000,
                csPin,
            });
            node.driver     = new SDSPIFull(connection, { speedHz: parseInt(config.speed) || 12000000 });
            node.connection = connection;
            node.ready      = node.driver.init().catch(function(e) {
                node.error('SD card init failed: ' + e.message);
                node.driver = null;
            });
        } catch (e) {
            node.error('SD card init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('sd-spi-device', SdSpiDeviceNode);

    function SdSpiNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (node.device && node.device.ready) await node.device.ready;
            if (!node.device || !node.device.driver) {
                node.error('No SD card device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const op = (msg.op || config.op || 'read');
                if (op === 'info') {
                    msg.payload = d.info();
                } else if (op === 'read') {
                    const block = (msg.block != null) ? msg.block : 0;
                    const count = (msg.count != null) ? msg.count : 1;
                    msg.payload = await d.read(block, count);
                } else if (op === 'write') {
                    const block = (msg.block != null) ? msg.block : 0;
                    const data  = Buffer.isBuffer(msg.payload) ? msg.payload : Buffer.from(msg.data || []);
                    await d.write(block, data);
                    msg.payload = { ok: true };
                } else if (op === 'erase') {
                    await d.erase((msg.block != null) ? msg.block : 0, (msg.count != null) ? msg.count : 1);
                    msg.payload = { ok: true };
                } else if (op === 'sync') {
                    await d.sync();
                    msg.payload = { ok: true };
                } else {
                    node.error('Unknown op: ' + op, msg);
                    done();
                    return;
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('sd-spi', SdSpiNode);
};
