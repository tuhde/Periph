# node-red-contrib-periph-humidity

Node-RED nodes for humidity sensor chips — part of the [Periph](https://github.com/tuhde/Periph) library.

## Install

Open Node-RED, go to **Manage Palette → Install** and search for `node-red-contrib-periph-humidity`.

Or from the command line in your Node-RED user directory:

```sh
npm install node-red-contrib-periph-humidity
```

## Nodes

| Node | Kind | Description |
|------|------|-------------|
| `dht11-device` | config | Bus configuration for a DHT11 |
| `dht11` | input | Reads temperature and humidity from a DHT11 sensor over a single GPIO data line. |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [periph JS driver](https://www.npmjs.com/package/periph)
