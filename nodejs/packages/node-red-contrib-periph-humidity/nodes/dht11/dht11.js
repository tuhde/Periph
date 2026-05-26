'use strict';

module.exports = function(RED) {
  function DHT11Node(config) {
    RED.nodes.createNode(this, config);

    const dataPin = parseInt(config.dataPin || '4', 10);
    const retries = parseInt(config.retries || '3', 10);

    const { DHTxxTransport } = require('periph/src/transport/dhtxx');
    const { DHT11Full } = require('periph/src/chips/humidity/dht11');

    const transport = new DHTxxTransport(new Gpio(dataPin, 'out'));
    const sensor = new DHT11Full(transport);

    this.on('input', function(msg) {
      let temp, hum;
      try {
        [temp, hum] = sensor.readRetry(retries);
      } catch (e) {
        this.warn('DHT11 read failed: ' + e.message);
        return;
      }

      msg.payload = {
        temperature_c: temp,
        humidity_rh: hum,
      };
      this.send(msg);
    });

    this.on('close', function() {
      transport.close();
    });
  }

  RED.nodes.registerType('periph-dht11', DHT11Node);
};
