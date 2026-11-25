//! AHT20 — temperature and humidity sensor (ASAIR).
//!
//! Communicates over I²C (up to 400 kHz fast mode). The sensor uses a
//! command-based protocol rather than a register map. Both
//! [`Aht20Minimal::new`] and [`Aht20Full::new`] handle the power-on wait,
//! the calibration check (status word against `0x18`, with a soft reset and the
//! `0x1B`/`0x1C`/`0x1E` calibration register init if needed) automatically.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const CMD_STATUS: u8 = 0x71;
const CMD_CAL_INIT: [[u8; 3]; 3] = [[0x1B, 0x00, 0x00], [0x1C, 0x00, 0x00], [0x1E, 0x00, 0x00]];
const CMD_TRIGGER: [u8; 3] = [0xAC, 0x33, 0x00];
const CMD_SOFT_RESET: u8 = 0xBA;

const STATUS_BUSY: u8 = 0x80;
const STATUS_CAL: u8 = 0x08;
const STATUS_READY: u8 = 0x18;

/// Errors returned by the AHT20 drivers.
#[derive(Debug)]
pub enum Aht20Error<E> {
    /// Underlying I²C bus error.
    I2c(E),
    /// The sensor did not report calibrated after the soft reset and the calibration register init.
    NotCalibrated,
    /// BUSY did not clear within 200 ms after the 80 ms measurement wait.
    Timeout,
}

impl<E> From<E> for Aht20Error<E> {
    fn from(e: E) -> Self {
        Aht20Error::I2c(e)
    }
}

/// AHT20 minimal driver — temperature and humidity readings.
///
/// Handles power-on initialization, calibration check, and measurement
/// triggering automatically. The default configuration baked in is:
/// one-shot measurement on every `read()` call, 80 ms wait after the
/// trigger followed by BUSY polling (10 ms steps, 200 ms guard), no CRC verification.
pub struct Aht20Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Aht20Minimal<I2C> {
    /// Create a new `Aht20Minimal` and perform power-on initialization.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`  — 7-bit device address (`0x38`).
    /// * `delay` — Delay provider for timing requirements.
    ///
    /// Returns [`Aht20Error::NotCalibrated`] if the status word is still not `0x18`
    /// after the soft reset and the calibration register init.
    pub fn new<D: DelayNs>(mut i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, Aht20Error<I2C::Error>> {
        delay.delay_ms(100);
        calibrate(&mut i2c, addr, delay)?;
        delay.delay_ms(10);
        Ok(Self { i2c, addr })
    }

    /// Trigger a measurement and return temperature and humidity.
    ///
    /// Returns `(temperature_c, humidity_pct)` where temperature is in
    /// degrees Celsius (-50 to 150 °C) and humidity is in percent (0 to 100 %RH).
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Aht20Error<I2C::Error>> {
        let buf = self.measure::<D, 6>(delay)?;
        let (rh, t) = raw(&buf);
        Ok(convert(rh, t))
    }

    fn measure<D: DelayNs, const N: usize>(&mut self, delay: &mut D) -> Result<[u8; N], Aht20Error<I2C::Error>> {
        self.i2c.write(self.addr, &CMD_TRIGGER)?;
        delay.delay_ms(80); // conversion takes > 80 ms
        let mut waited = 0;
        loop {
            let mut buf = [0u8; N];
            self.i2c.read(self.addr, &mut buf)?;
            if buf[0] & STATUS_BUSY == 0 {
                return Ok(buf);
            }
            if waited >= 200 {
                return Err(Aht20Error::Timeout);
            }
            delay.delay_ms(10);
            waited += 10;
        }
    }
}

/// AHT20 full driver — extends [`Aht20Minimal`] with CRC, raw values, status and dew point.
pub struct Aht20Full<I2C> {
    inner: Aht20Minimal<I2C>,
}

impl<I2C: I2c> Aht20Full<I2C> {
    /// Create a new `Aht20Full` and perform power-on initialization.
    ///
    /// Same arguments as [`Aht20Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Result<Self, Aht20Error<I2C::Error>> {
        let inner = Aht20Minimal::new(i2c, addr, delay)?;
        Ok(Self { inner })
    }

    /// Trigger a measurement and return temperature and humidity.
    /// Delegates to the inner [`Aht20Minimal`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32), Aht20Error<I2C::Error>> {
        self.inner.read(delay)
    }

    /// Trigger a measurement and return temperature only.
    ///
    /// Returns temperature in degrees Celsius (-50 to 150 °C).
    pub fn read_temperature<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Aht20Error<I2C::Error>> {
        Ok(self.inner.read(delay)?.0)
    }

    /// Trigger a measurement and return humidity only.
    ///
    /// Returns relative humidity in percent (0 to 100 %RH).
    pub fn read_humidity<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Aht20Error<I2C::Error>> {
        Ok(self.inner.read(delay)?.1)
    }

    /// Trigger a measurement and return the raw 20-bit values.
    ///
    /// Returns `(raw_humidity, raw_temperature)`, each 0 to 1048575.
    pub fn read_raw<D: DelayNs>(&mut self, delay: &mut D) -> Result<(u32, u32), Aht20Error<I2C::Error>> {
        let buf = self.inner.measure::<D, 6>(delay)?;
        Ok(raw(&buf))
    }

    /// Trigger a measurement, read 7 bytes and verify the CRC-8
    /// (polynomial 0x31, initial value 0xFF, over bytes 0 to 5, compared with byte 6).
    ///
    /// Returns `(temperature_c, humidity_pct, crc_ok)`.
    pub fn read_with_crc<D: DelayNs>(&mut self, delay: &mut D) -> Result<(f32, f32, bool), Aht20Error<I2C::Error>> {
        let buf = self.inner.measure::<D, 7>(delay)?;
        let (rh, t) = raw(&buf);
        let (temperature_c, humidity_pct) = convert(rh, t);
        Ok((temperature_c, humidity_pct, crc8(&buf[..6]) == buf[6]))
    }

    /// Read the status byte (bit 7 BUSY, bit 3 CAL).
    pub fn read_status(&mut self) -> Result<u8, Aht20Error<I2C::Error>> {
        Ok(read_status(&mut self.inner.i2c, self.inner.addr)?)
    }

    /// Check if the calibration bit is set in the status byte.
    ///
    /// Returns `true` if the sensor reports calibration enabled.
    pub fn is_calibrated(&mut self) -> Result<bool, Aht20Error<I2C::Error>> {
        Ok(self.read_status()? & STATUS_CAL != 0)
    }

    /// Check if the busy bit is set in the status byte.
    ///
    /// Returns `true` if a measurement is in progress.
    pub fn is_busy(&mut self) -> Result<bool, Aht20Error<I2C::Error>> {
        Ok(self.read_status()? & STATUS_BUSY != 0)
    }

    /// Send the soft reset command, wait 20 ms, and re-run the calibration check.
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Aht20Error<I2C::Error>> {
        self.inner.i2c.write(self.inner.addr, &[CMD_SOFT_RESET])?;
        delay.delay_ms(20);
        calibrate(&mut self.inner.i2c, self.inner.addr, delay)
    }

    /// Trigger a measurement and return the dew point in °C (Magnus formula).
    ///
    /// Returns -50.0 if the humidity is 0 %RH.
    pub fn dew_point<D: DelayNs>(&mut self, delay: &mut D) -> Result<f32, Aht20Error<I2C::Error>> {
        let (t, rh) = self.inner.read(delay)?;
        if rh <= 0.0 {
            return Ok(-50.0);
        }
        let gamma = libm::logf(rh / 100.0) + 17.62 * t / (243.12 + t);
        Ok(243.12 * gamma / (17.62 - gamma))
    }
}

// The datasheet tests the status word against 0x18 once after power-on. This
// function is also used after a soft reset (which then needs the reset first).
fn calibrate<I2C: I2c, D: DelayNs>(i2c: &mut I2C, addr: u8, delay: &mut D) -> Result<(), Aht20Error<I2C::Error>> {
    if read_status(i2c, addr)? & STATUS_READY == STATUS_READY {
        return Ok(());
    }
    i2c.write(addr, &[CMD_SOFT_RESET])?;
    delay.delay_ms(20);
    if read_status(i2c, addr)? & STATUS_READY == STATUS_READY {
        return Ok(());
    }
    for cmd in CMD_CAL_INIT.iter() {
        i2c.write(addr, cmd)?;
        delay.delay_ms(10);
    }
    if read_status(i2c, addr)? & STATUS_READY != STATUS_READY {
        return Err(Aht20Error::NotCalibrated);
    }
    Ok(())
}

fn read_status<I2C: I2c>(i2c: &mut I2C, addr: u8) -> Result<u8, I2C::Error> {
    i2c.write(addr, &[CMD_STATUS])?;
    let mut buf = [0u8; 1];
    i2c.read(addr, &mut buf)?;
    Ok(buf[0])
}

fn crc8(data: &[u8]) -> u8 {
    let mut crc = 0xFFu8;
    for &byte in data {
        crc ^= byte;
        for _ in 0..8 {
            crc = if crc & 0x80 != 0 { (crc << 1) ^ 0x31 } else { crc << 1 };
        }
    }
    crc
}

fn raw(buf: &[u8]) -> (u32, u32) {
    let raw_rh = ((buf[1] as u32) << 12) | ((buf[2] as u32) << 4) | ((buf[3] as u32) >> 4);
    let raw_t = ((buf[3] as u32 & 0x0F) << 16) | ((buf[4] as u32) << 8) | (buf[5] as u32);
    (raw_rh, raw_t)
}

fn convert(raw_rh: u32, raw_t: u32) -> (f32, f32) {
    let humidity_pct = (raw_rh as f32 / 1048576.0 * 100.0).clamp(0.0, 100.0);
    let temperature_c = (raw_t as f32 / 1048576.0) * 200.0 - 50.0;
    (temperature_c, humidity_pct)
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x38;

    // 6-byte measurement response encoding humidity=50.0%, temperature=50.0 degC:
    // raw_rh = buf[1]<<12 | buf[2]<<4 | buf[3]>>4       = 0x80000 (524288 / 2**20 * 100 = 50.0)
    // raw_t  = (buf[3]&0x0F)<<16 | buf[4]<<8 | buf[5]   = 0x80000 (524288 / 2**20 * 200 - 50 = 50.0)
    const MEASUREMENT: [u8; 6] = [0x08, 0x80, 0x00, 0x08, 0x00, 0x00];

    fn status(value: u8) -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write(ADDR, vec![CMD_STATUS]),
            I2cTransaction::read(ADDR, vec![value]),
        ]
    }

    fn init_ok() -> Vec<I2cTransaction> {
        status(0x18)
    }

    #[test]
    fn full_api() {
        let mut delay = NoopDelay::new();
        let mut transactions = init_ok();
        transactions.extend(vec![
            // read()
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, MEASUREMENT.to_vec()),
            // read_temperature()
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, MEASUREMENT.to_vec()),
            // read_humidity()
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, MEASUREMENT.to_vec()),
            // read_raw()
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, MEASUREMENT.to_vec()),
            // read() with BUSY on the first read
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, vec![0x88, 0x80, 0x00, 0x08, 0x00, 0x00]),
            I2cTransaction::read(ADDR, MEASUREMENT.to_vec()),
            // spec example: raw_t = 0x6B300 -> 33.74 degC
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, vec![0x08, 0x80, 0x00, 0x06, 0xB3, 0x00]),
            // dew_point()
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, MEASUREMENT.to_vec()),
            // read_with_crc(): good CRC, then corrupted CRC
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, crc_frame(0)),
            I2cTransaction::write(ADDR, CMD_TRIGGER.to_vec()),
            I2cTransaction::read(ADDR, crc_frame(0xFF)),
        ]);
        transactions.extend(status(0x08)); // read_status()
        transactions.extend(vec![
            // soft_reset(): reset, then the calibration check finds 0x18
            I2cTransaction::write(ADDR, vec![CMD_SOFT_RESET]),
        ]);
        transactions.extend(status(0x18));
        // is_calibrated(), is_busy()
        transactions.extend(status(0x08));
        transactions.extend(status(0x00));
        transactions.extend(status(0x80));
        transactions.extend(status(0x00));
        let transactions = transactions;
        let i2c = I2cMock::new(&transactions);

        let mut sensor = Aht20Full::new(i2c, ADDR, &mut delay).expect("init");

        let (t, h) = sensor.read(&mut delay).unwrap();
        assert_eq!(t, 50.0);
        assert_eq!(h, 50.0);

        assert_eq!(sensor.read_temperature(&mut delay).unwrap(), 50.0);
        assert_eq!(sensor.read_humidity(&mut delay).unwrap(), 50.0);
        assert_eq!(sensor.read_raw(&mut delay).unwrap(), (0x80000, 0x80000));
        assert_eq!(sensor.read_temperature(&mut delay).unwrap(), 50.0);
        assert!((sensor.read_temperature(&mut delay).unwrap() - 33.74).abs() < 0.01);
        assert!((sensor.dew_point(&mut delay).unwrap() - 36.7).abs() < 0.1);
        let (t, h, ok) = sensor.read_with_crc(&mut delay).unwrap();
        assert!(ok && t == 50.0 && h == 50.0);
        assert!(!sensor.read_with_crc(&mut delay).unwrap().2);
        assert_eq!(sensor.read_status().unwrap(), 0x08);

        sensor.soft_reset(&mut delay).unwrap();

        assert!(sensor.is_calibrated().unwrap());
        assert!(!sensor.is_calibrated().unwrap());
        assert!(sensor.is_busy().unwrap());
        assert!(!sensor.is_busy().unwrap());

        sensor.inner.i2c.done();
    }

    // 7-byte frame (50 %RH / 50 degC) whose CRC byte is XORed with `corrupt`.
    fn crc_frame(corrupt: u8) -> Vec<u8> {
        let mut frame = vec![0x1C, 0x80, 0x00, 0x08, 0x00, 0x00];
        frame.push(crc8(&frame) ^ corrupt);
        frame
    }

    #[test]
    fn crc8_known_vector() {
        assert_eq!(crc8(&[0xBE, 0xEF]), 0x92);
    }

    #[test]
    fn init_soft_reset_fixes_calibration() {
        let mut delay = NoopDelay::new();
        let mut transactions = status(0x00);
        transactions.push(I2cTransaction::write(ADDR, vec![CMD_SOFT_RESET]));
        transactions.extend(status(0x18));
        let mut i2c = I2cMock::new(&transactions);
        Aht20Minimal::new(i2c.clone(), ADDR, &mut delay).expect("init");
        i2c.done();
    }

    #[test]
    fn init_writes_calibration_registers() {
        let mut delay = NoopDelay::new();
        let mut transactions = status(0x00);
        transactions.push(I2cTransaction::write(ADDR, vec![CMD_SOFT_RESET]));
        transactions.extend(status(0x00));
        for cmd in CMD_CAL_INIT.iter() {
            transactions.push(I2cTransaction::write(ADDR, cmd.to_vec()));
        }
        transactions.extend(status(0x18));
        let mut i2c = I2cMock::new(&transactions);
        Aht20Minimal::new(i2c.clone(), ADDR, &mut delay).expect("init");
        i2c.done();
    }

    #[test]
    fn init_retries_then_fails() {
        let mut delay = NoopDelay::new();
        let mut transactions = status(0x00);
        transactions.push(I2cTransaction::write(ADDR, vec![CMD_SOFT_RESET]));
        transactions.extend(status(0x00));
        for cmd in CMD_CAL_INIT.iter() {
            transactions.push(I2cTransaction::write(ADDR, cmd.to_vec()));
        }
        transactions.extend(status(0x00));
        let mut i2c = I2cMock::new(&transactions);
        let r = Aht20Minimal::new(i2c.clone(), ADDR, &mut delay);
        assert!(matches!(r, Err(Aht20Error::NotCalibrated)));
        i2c.done();
    }
}
