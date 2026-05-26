//! DHT11 humidity/temperature sensor driver.
//!
//! DHT11 is a low-cost combined temperature and humidity sensor with factory-calibrated
//! digital output. Each read returns the result of the sensor's most recent completed
//! measurement, not a fresh instantaneous conversion.

use crate::transport::dhtxx::DHTxxTransport;

/// DHT11 minimal driver -- reads temperature and humidity in one call.
pub struct Dht11Minimal<T> {
    transport: T,
}

impl<T> Dht11Minimal<T>
where
    T: DHTxxTransport,
{
    /// Create a new DHT11 minimal driver.
    pub fn new(transport: T) -> Self {
        Self { transport }
    }

    /// Read temperature and humidity.
    ///
    /// Returns `(temperature_C, humidity_RH)`.
    pub fn read(&mut self) -> Result<(f32, f32), T::Error> {
        let frame = self.transport.read()?;
        Ok(decode_frame(&frame))
    }
}

/// DHT11 full driver -- extends minimal with additional methods.
pub struct Dht11Full<T> {
    inner: Dht11Minimal<T>,
}

impl<T> Dht11Full<T>
where
    T: DHTxxTransport,
{
    /// Create a new DHT11 full driver.
    pub fn new(transport: T) -> Self {
        Self { inner: Dht11Minimal::new(transport) }
    }

    /// Read temperature in degrees Celsius.
    pub fn read_temperature(&mut self) -> Result<f32, T::Error> {
        Ok(self.read()?.0)
    }

    /// Read relative humidity in percent.
    pub fn read_humidity(&mut self) -> Result<f32, T::Error> {
        Ok(self.read()?.1)
    }

    /// Read with retry on checksum error.
    pub fn read_retry(&mut self, max_retries: u8) -> Result<(f32, f32), T::Error> {
        for _ in 0..max_retries {
            let result = self.read();
            if result.is_ok() {
                return result;
            }
        }
        self.read()
    }

    /// Return raw 5-byte frame without interpretation.
    pub fn read_raw(&mut self) -> Result<[u8; 5], T::Error> {
        Ok(self.transport.read()?)
    }

    fn read(&mut self) -> Result<(f32, f32), T::Error> {
        self.inner.read()
    }
}

fn decode_frame(frame: &[u8; 5]) -> (f32, f32) {
    let hum_int = frame[0];
    let hum_dec = frame[1];
    let temp_int = frame[2];
    let temp_dec = frame[3];
    let checksum = frame[4];

    let _checksum_ok = (hum_int as u16 + hum_dec as u16 + temp_int as u16 + temp_dec as u16) & 0xFF == checksum as u16;

    let humidity = hum_int as f32 + hum_dec as f32 / 10.0;
    let sign = if (temp_dec & 0x80) != 0 { -1.0 } else { 1.0 };
    let temp_dec_value = (temp_dec & 0x7F) as f32;
    let temperature = sign * (temp_int as f32 + temp_dec_value / 10.0);

    (temperature, humidity)
}
