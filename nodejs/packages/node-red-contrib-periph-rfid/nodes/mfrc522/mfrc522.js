'use strict';

module.exports = function(RED) {
    const { SPITransport } = require('periph/src/transport/spi');
    const { MFRC522Full }   = require('periph/src/chips/rfid/mfrc522');

    function MFRC522Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new SPITransport(
                parseInt(config.bus, 10),
                parseInt(config.device, 10),
                { maxSpeedHz: parseInt(config.speed, 10) || 1000000, mode: 0 }
            );
            node.driver    = new MFRC522Full(transport);
            node.transport = transport;
            node.pollMs    = parseInt(config.pollInterval, 10) || 500;
            node._timer    = null;
            node._lastUid  = null;

            const tick = function() {
                if (!node.driver) return;
                try {
                    const present = node.driver.isCardPresent();
                    let uid = null;
                    if (present) {
                        const buf = node.driver.readUid();
                        if (buf) uid = buf.toString('hex');
                    }
                    if (uid !== node._lastUid) {
                        node._lastUid = uid;
                        const msg = { payload: { present: present, uid: uid } };
                        node.send(msg);
                    }
                } catch (e) {
                    node.error('MFRC522 poll failed: ' + e.message);
                }
            };

            node._timer = setInterval(tick, node.pollMs);
        } catch (e) {
            node.error('MFRC522 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node._timer) { clearInterval(node._timer); node._timer = null; }
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('periph-mfrc522', MFRC522Node);
};
