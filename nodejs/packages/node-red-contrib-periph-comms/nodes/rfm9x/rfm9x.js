'use strict';
module.exports = function(RED) {
    function RFM9xNode(config) {
        RED.nodes.createNode(this, config);
        const bus = parseInt(config.spiBus || '0', 10);
        const device = parseInt(config.spiDevice || '0', 10);
        const cs = parseInt(config.spiCs || '0', 10);
        const variant = config.variant || 'RFM95W';
        const frequency = parseInt(config.frequency || '868000000', 10);
        const sf = parseInt(config.sf || '7', 10);
        const bw = parseFloat(config.bandwidth || '125');
        const cr = parseInt(config.codingRate || '5', 10);
        const txPower = parseInt(config.txPower || '17', 10);
        const crc = config.crc !== 'false';

        const { SpiTransport } = require('periph/src/transport/spi');
        const { RFM95Full, RFM96Full, RFM97Full, RFM98Full } = require('periph/src/chips/comms/rfm9x');

        const transport = new SpiTransport(bus, device, cs);
        let rfm;
        try {
            if (variant === 'RFM95W') rfm = new RFM95Full(transport, frequency, 0, 0);
            else if (variant === 'RFM96W') rfm = new RFM96Full(transport, frequency, 0, 0);
            else if (variant === 'RFM97W') rfm = new RFM97Full(transport, frequency, 0, 0);
            else rfm = new RFM98Full(transport, frequency, 0, 0);
            rfm.configure(sf, bw, cr, crc);
            rfm.set_tx_power(txPower, true);
        } catch (e) {
            this.error('RFM9x init failed: ' + e.message);
        }

        this.on('input', function(msg) {
            if (msg.payload && Buffer.isBuffer(msg.payload)) {
                try { rfm.send(msg.payload); } catch (e) { this.error('send failed: ' + e.message); }
            } else if (typeof msg.payload === 'string') {
                try { rfm.send(Buffer.from(msg.payload)); } catch (e) { this.error('send failed: ' + e.message); }
            } else {
                try {
                    const rx = rfm.receive(2000);
                    if (rx) {
                        msg.payload = { data: rx, rssi: rfm.last_packet_rssi(), snr: rfm.last_packet_snr() };
                    } else {
                        msg.payload = null;
                    }
                    this.send(msg);
                } catch (e) { this.error('receive failed: ' + e.message); }
            }
        });

        this.on('close', function() {
            transport.close();
        });
    }
    RED.nodes.registerType('periph-rfm9x', RFM9xNode);
};