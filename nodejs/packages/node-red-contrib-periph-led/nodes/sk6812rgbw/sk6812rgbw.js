'use strict';

module.exports = function(RED) {
    const { NeoPixelTransport }  = require('periph/src/transport/neopixel');
    const { SK6812RGBWFull }     = require('periph/src/chips/led/sk6812rgbw');

    function SK6812RGBWDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            node.transport = new NeoPixelTransport(
                parseInt(config.spiBus),
                parseInt(config.spiDevice)
            );
            node.driver = new SK6812RGBWFull(node.transport, parseInt(config.pixelCount));
            node.driver.brightness = Math.max(0, Math.min(255, parseInt(config.brightness) || 255));
        } catch (e) {
            node.error('SK6812RGBW init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.driver) node.driver.off();
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('sk6812rgbw-device', SK6812RGBWDeviceNode);

    function SK6812RGBWWriteNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No SK6812RGBW device configured', msg);
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
                        strip.fill(r, g, b, 0);
                    } else if (Array.isArray(payload.pixels)) {
                        strip.set_pixels(payload.pixels);
                        strip.show();
                    } else if (typeof payload.pixel === 'number') {
                        strip.set_pixel(payload.pixel,
                            payload.r || 0, payload.g || 0,
                            payload.b || 0, payload.w || 0);
                        strip.show();
                    } else if (typeof payload.r === 'number' || typeof payload.g === 'number' ||
                               typeof payload.b === 'number' || typeof payload.w === 'number') {
                        strip.fill(payload.r || 0, payload.g || 0,
                                   payload.b || 0, payload.w || 0);
                    }
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-sk6812rgbw', SK6812RGBWWriteNode);
};
