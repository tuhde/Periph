package it.uhde.periph.chips.humidity;

import it.uhde.periph.transport.Transport;
import java.io.IOException;

/**
 * DHT11 -- temperature and humidity sensor (full driver).
 *
 * <p>Extends DHT11Minimal with readTemperature(), readHumidity(),
 * readRetry(), and readRaw() methods.
 */
public class DHT11Full extends DHT11Minimal {

    public DHT11Full(Transport transport) {
        super(transport);
    }

    public double readTemperature() throws IOException {
        return read()[0];
    }

    public double readHumidity() throws IOException {
        return read()[1];
    }

    public double[] readRetry(int maxRetries) throws IOException {
        for (int i = 0; i < maxRetries; i++) {
            try {
                return read();
            } catch (IOException e) {
                // continue
            }
        }
        throw new IOException("all retries exhausted");
    }

    public byte[] readRaw() throws IOException {
        byte[] frame = transport.read(5);

        int humInt  = frame[0] & 0xFF;
        int humDec  = frame[1] & 0xFF;
        int tempInt = frame[2] & 0xFF;
        int tempDec = frame[3] & 0xFF;
        int checksum = frame[4] & 0xFF;

        if ((humInt + humDec + tempInt + tempDec) & 0xFF != checksum) {
            throw new IOException("checksum mismatch");
        }

        return frame;
    }
}
