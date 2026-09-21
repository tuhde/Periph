'use strict';

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { APA102Full }    = require('periph/src/chips/led/apa102');

    function APA102DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            node.connection = new SPIConnection(
                parseInt(config.spiBus),
                parseInt(config.spiDevice),
                { mode: 0, maxSpeedHz: 1000000 }
            );
            node.driver = new APA102Full(node.connection, parseInt(config.pixelCount));
            node.driver.brightness = Math.max(0, Math.min(255, parseInt(config.brightness) || 255));
        } catch (e) {
            node.error('APA102 init failed: ' + e.message);
        }
        node.on('close', async function(done) {
            if (node.driver) await node.driver.off();
            if (node.connection) await node.connection.close();
            done();
        });
    }
    RED.nodes.registerType('apa102-device', APA102DeviceNode);

    function APA102WriteNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No APA102 device configured', msg);
                done();
                return;
            }
            const strip = node.device.driver;
            const payload = msg.payload;
            try {
                if (typeof payload === 'object' && payload !== null) {
                    if (payload.command === 'off') {
                        await strip.off();
                    } else if (typeof payload.color === 'string') {
                        const hex = payload.color.replace('#', '');
                        const r = parseInt(hex.slice(0, 2), 16);
                        const g = parseInt(hex.slice(2, 4), 16);
                        const b = parseInt(hex.slice(4, 6), 16);
                        await strip.fill(r, g, b);
                    } else if (Array.isArray(payload.pixels)) {
                        strip.set_pixels(payload.pixels);
                        await strip.show();
                    } else if (typeof payload.pixel === 'number') {
                        strip.set_pixel(payload.pixel,
                            payload.r || 0, payload.g || 0, payload.b || 0,
                            payload.pixel_brightness);
                        await strip.show();
                    } else if (typeof payload.r === 'number' ||
                               typeof payload.g === 'number' ||
                               typeof payload.b === 'number') {
                        await strip.fill(payload.r || 0, payload.g || 0, payload.b || 0,
                            payload.pixel_brightness);
                    }
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-apa102', APA102WriteNode);
};