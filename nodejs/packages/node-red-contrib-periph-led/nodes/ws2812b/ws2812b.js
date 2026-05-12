'use strict';

module.exports = function(RED) {
    const { NeoPixelTransport } = require('periph/src/transport/neopixel');
    const { WS2812BFull }       = require('periph/src/chips/led/ws2812b');

    function WS2812BDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            node.transport = new NeoPixelTransport(
                parseInt(config.spiBus),
                parseInt(config.spiDevice)
            );
            node.driver = new WS2812BFull(node.transport, parseInt(config.pixelCount));
            node.driver.brightness = Math.max(0, Math.min(255, parseInt(config.brightness) || 255));
        } catch (e) {
            node.error('WS2812B init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.driver) node.driver.off();
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('ws2812b-device', WS2812BDeviceNode);

    function WS2812BWriteNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No WS2812B device configured', msg);
                done();
                return;
            }
            const strip = node.device.driver;
            const payload = msg.payload;
            try {
                if (typeof payload === 'object' && payload !== null) {
                    if (payload.command === 'off') {
                        strip.off();
                    } else if (typeof payload.color === 'string') {
                        const hex = payload.color.replace('#', '');
                        const r = parseInt(hex.slice(0, 2), 16);
                        const g = parseInt(hex.slice(2, 4), 16);
                        const b = parseInt(hex.slice(4, 6), 16);
                        strip.fill(r, g, b);
                    } else if (Array.isArray(payload.pixels)) {
                        strip.set_pixels(payload.pixels);
                        strip.show();
                    } else if (typeof payload.pixel === 'number') {
                        strip.set_pixel(payload.pixel,
                            payload.r || 0, payload.g || 0, payload.b || 0);
                        strip.show();
                    } else if (typeof payload.r === 'number' ||
                               typeof payload.g === 'number' ||
                               typeof payload.b === 'number') {
                        strip.fill(payload.r || 0, payload.g || 0, payload.b || 0);
                    }
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-ws2812b', WS2812BWriteNode);
};
