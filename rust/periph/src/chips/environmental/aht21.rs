//! AHT21 — temperature and humidity sensor (ASAIR).
//!
//! Communicates over I²C (up to 400 kHz fast mode). The sensor uses a
//! command-based protocol rather than a register map. Both
//! [`Aht21Minimal::new`] and [`Aht21Full::new`] handle power-on
//! initialization, calibration check, and soft reset automatically.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_TRIGGER: [u8; 3] = [0xAC, 0x33, 0x00];
const CMD_SOFT_RESET: u8 = 0xBA;
const CMD_CAL_INIT_1: [u8; 3] = [0x1B, 0x00, 0x00];
const CMD_CAL_INIT_2: [u8; 3] = [0x1C, 0x00, 0x00];
const CMD_CAL_INIT_3: [u8; 3] = [0x1E, 0x00, 0x00];

const STATUS_BUSY: u8 = 0x80;
const STATUS_CAL: u8 = 0x08;

/// AHT21 minimal driver — temperature and humidity readings.
///
/// Handles power-on initialization, calibration check, and measurement
/// triggering automatically. The default configuration baked in is:
/// measurement triggered on every `read()` call, 80 ms fixed wait after
/// trigger, no CRC verification.
pub struct Aht21Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Aht21Minimal<I2C> {
    /// Create a new `Aht21Minimal` and perform power-on initialization.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (fixed at `0x38`).
    /// * `delay` — Delay provider for timing requirements.
    pub fn new<D: DelayNs>(mut i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, I2C::Error> {
        delay.delay_ms(100);
        let status = read_status(&mut i2c, addr)?;
        if (status & 0x18) != 0x18 {
            i2c.write(addr, &[CMD_SOFT_RESET])?;
            delay.delay_ms(20);
            let status = read_status(&mut i2c, addr)?;
            if (status & 0x18) != 0x18 {
                i2c.write(addr, &CMD_CAL_INIT_1)?;
                delay.delay_ms(10);
                i2c.write(addr, &CMD_CAL_INIT_2)?;
                delay.delay_ms(10);
                i2c.write(addr, &CMD_CAL_INIT_3)?;
                delay.delay_ms(10);
            }
        }
        Ok(Self { i2c, addr })
    }

    /// Trigger a measurement and return temperature and humidity.
    ///
    /// Returns `(temperature_c, humidity_pct)` where temperature is in
    /// degrees Celsius (-50 to 150 °C) and humidity is in percent (0 to 100 %RH).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), I2C::Error> {
        self.i2c.write(self.addr, &CMD_TRIGGER)?;
        delay.delay_ms(80);
        let mut buf = [0u8; 6];
        self.i2c.read(self.addr, &mut buf)?;
        Ok(decode(&buf))
    }
}

/// AHT21 full driver — extends [`Aht21Minimal`] with CRC and status support.
///
/// Provides CRC-8 verification, explicit soft reset, calibration status
/// inspection, and individual temperature/humidity readings.
pub struct Aht21Full<I2C> {
    inner: Aht21Minimal<I2C>,
}

impl<I2C: I2c> Aht21Full<I2C> {
    /// Create a new `Aht21Full` and perform power-on initialization.
    ///
    /// Same arguments as [`Aht21Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, I2C::Error> {
        let inner = Aht21Minimal::new(i2c, addr, delay)?;
        Ok(Self { inner })
    }

    /// Trigger a measurement and return temperature and humidity.
    /// Delegates to the inner [`Aht21Minimal`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), I2C::Error> {
        self.inner.read(delay)
    }

    /// Trigger a measurement and return temperature only.
    ///
    /// Returns temperature in degrees Celsius (-50 to 150 °C).
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        Ok(self.inner.read(delay)?.0)
    }

    /// Trigger a measurement and return humidity only.
    ///
    /// Returns relative humidity in percent (0 to 100 %RH).
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, I2C::Error> {
        Ok(self.inner.read(delay)?.1)
    }

    /// Trigger a measurement, read 7 bytes, and verify CRC-8.
    ///
    /// Returns `(temperature_c, humidity_pct, crc_ok)` where `crc_ok` is
    /// `true` if the CRC-8 verification passed.
    pub fn read_with_crc<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32, bool), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &CMD_TRIGGER)?;
        delay.delay_ms(80);
        let mut buf = [0u8; 7];
        self.inner.i2c.read(self.inner.addr, &mut buf)?;
        let (t, h) = decode(&buf);
        let crc_ok = crc8(&buf[0..6]) == buf[6];
        Ok((t, h, crc_ok))
    }

    /// Send the soft reset command and wait 20 ms for recovery.
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.inner.i2c.write(self.inner.addr, &[CMD_SOFT_RESET])?;
        delay.delay_ms(20);
        Ok(())
    }

    /// Check if the calibration bit is set in the status byte.
    ///
    /// Returns `true` if the sensor reports calibration enabled.
    pub fn is_calibrated(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_status(&mut self.inner.i2c, self.inner.addr)? & STATUS_CAL != 0)
    }

    /// Check if the busy bit is set in the status byte.
    ///
    /// Returns `true` if a measurement is in progress.
    pub fn is_busy(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_status(&mut self.inner.i2c, self.inner.addr)? & STATUS_BUSY != 0)
    }
}

fn read_status<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.read(addr, &mut buf)?;
    Ok(buf[0])
}

fn decode(buf: &[u8]) -> (f32, f32) {
    let raw_rh = ((buf[1] as u32) << 12) | ((buf[2] as u32) << 4) | ((buf[3] as u32) >> 4);
    let raw_t = ((buf[3] as u32 & 0x0F) << 16) | ((buf[4] as u32) << 8) | (buf[5] as u32);
    let humidity_pct = (raw_rh as f32 / 1048576.0) * 100.0;
    let temperature_c = (raw_t as f32 / 1048576.0) * 200.0 - 50.0;
    (temperature_c, humidity_pct)
}

fn crc8(data: &[u8]) -> u8 {
    let mut crc: u8 = 0xFF;
    for &byte in data {
        crc ^= byte;
        for _ in 0..8 {
            if crc & 0x80 != 0 {
                crc = (crc << 1) ^ 0x31;
            } else {
                crc <<= 1;
            }
        }
    }
    crc
}
