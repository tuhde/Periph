package it.uhde.periph.chips.humidity

import it.uhde.periph.transport.Transport

/**
 * DHT11 -- temperature and humidity sensor (full driver).
 *
 * Extends DHT11Minimal with readTemperature(), readHumidity(),
 * readRetry(), and readRaw() methods.
 */
@CompileStatic
class DHT11Full extends DHT11Minimal {

    DHT11Full(Transport transport) {
        super(transport)
    }

    double readTemperature() {
        read()[0]
    }

    double readHumidity() {
        read()[1]
    }

    double[] readRetry(int maxRetries) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                return read()
            } catch (IOException e) {
                // continue
            }
        }
        throw new IOException("all retries exhausted")
    }

    byte[] readRaw() {
        byte[] frame = transport.read(5)

        int humInt  = frame[0] & 0xFF
        int humDec  = frame[1] & 0xFF
        int tempInt = frame[2] & 0xFF
        int tempDec = frame[3] & 0xFF
        int checksum = frame[4] & 0xFF

        if ((humInt + humDec + tempInt + tempDec) & 0xFF != checksum) {
            throw new IOException("checksum mismatch")
        }

        return frame
    }
}
