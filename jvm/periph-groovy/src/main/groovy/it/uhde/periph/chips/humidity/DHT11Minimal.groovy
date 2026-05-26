package it.uhde.periph.chips.humidity

import it.uhde.periph.transport.Transport

/**
 * DHT11 -- temperature and humidity sensor (minimal driver).
 *
 * DHT11 is a low-cost combined temperature and humidity sensor with factory-calibrated
 * digital output. Each read returns the result of the sensor's most recent completed
 * measurement, not a fresh instantaneous conversion.
 *
 * Note: This driver requires a GPIO-based transport (e.g. DHTxxTransport) that
 * implements the bit-bang protocol for the single-wire data line.
 */
@CompileStatic
class DHT11Minimal {

    protected final Transport transport

    DHT11Minimal(Transport transport) {
        this.transport = transport
    }

    double[] read() {
        byte[] frame = transport.read(5)

        int humInt  = frame[0] & 0xFF
        int humDec  = frame[1] & 0xFF
        int tempInt = frame[2] & 0xFF
        int tempDec = frame[3] & 0xFF
        int checksum = frame[4] & 0xFF

        if ((humInt + humDec + tempInt + tempDec) & 0xFF != checksum) {
            throw new IOException("checksum mismatch")
        }

        double humidity = humInt + humDec / 10.0d
        int sign = (tempDec & 0x80) != 0 ? -1 : 1
        int tempDecValue = tempDec & 0x7F
        double temperature = sign * (tempInt + tempDecValue / 10.0d)

        return [temperature, humidity] as double[]
    }
}
