//! BNO055 — 9-axis absolute-orientation sensor (Bosch Sensortec): 14-bit accelerometer, 16-bit
//! gyroscope and magnetometer with an on-chip sensor-fusion microcontroller that outputs Euler
//! angles, a quaternion, linear acceleration and the gravity vector, with automatic calibration.
//!
//! Communicates over I²C at `0x29` (COM3 high, datasheet default) or `0x28` (COM3 low); the UART
//! interface is out of scope. The chip stretches the I²C clock, so the host bus must support clock
//! stretching. All data registers are little-endian and are always read as one burst so the
//! LSB/MSB pairs are consistent.
//!
//! The driver fixes `UNIT_SEL = 0x86` (Android orientation, Euler angles in radians, angular rate
//! in rad/s, acceleration in m/s², temperature in °C) and converts everything to SI-style units.
//!
//! Rust drivers expose [`Bno055Full::poll_interrupt`] only — the caller wires the INT pin into an
//! ISR or polling loop of their own and calls it when the pin asserts.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_CHIP_ID: u8 = 0x00;
const REG_PAGE_ID: u8 = 0x07;
const REG_ACC_DATA: u8 = 0x08;
const REG_MAG_DATA: u8 = 0x0E;
const REG_GYR_DATA: u8 = 0x14;
const REG_EUL_DATA: u8 = 0x1A;
const REG_QUA_DATA: u8 = 0x20;
const REG_LIA_DATA: u8 = 0x28;
const REG_GRV_DATA: u8 = 0x2E;
const REG_TEMP: u8 = 0x34;
const REG_CALIB_STAT: u8 = 0x35;
const REG_ST_RESULT: u8 = 0x36;
const REG_INT_STA: u8 = 0x37;
const REG_SYS_STATUS: u8 = 0x39;
const REG_UNIT_SEL: u8 = 0x3B;
const REG_OPR_MODE: u8 = 0x3D;
const REG_PWR_MODE: u8 = 0x3E;
const REG_SYS_TRIGGER: u8 = 0x3F;
const REG_TEMP_SOURCE: u8 = 0x40;
const REG_AXIS_MAP_CONFIG: u8 = 0x41;
const REG_AXIS_MAP_SIGN: u8 = 0x42;
const REG_ACC_OFFSET: u8 = 0x55;
const REG_INT_MSK: u8 = 0x0F; // page 1
const REG_INT_EN: u8 = 0x10; // page 1
const REG_UNIQUE_ID: u8 = 0x50; // page 1

const CHIP_ID_VALUE: u8 = 0xA0;
const UNIT_SEL_SI: u8 = 0x86;

/// Operation mode: CONFIGMODE (all outputs zero; the only mode in which most registers are writable).
pub const MODE_CONFIG: u8 = 0x0;
/// Operation mode: raw accelerometer only.
pub const MODE_ACCONLY: u8 = 0x1;
/// Operation mode: raw magnetometer only.
pub const MODE_MAGONLY: u8 = 0x2;
/// Operation mode: raw gyroscope only.
pub const MODE_GYROONLY: u8 = 0x3;
/// Operation mode: raw accelerometer and magnetometer.
pub const MODE_ACCMAG: u8 = 0x4;
/// Operation mode: raw accelerometer and gyroscope.
pub const MODE_ACCGYRO: u8 = 0x5;
/// Operation mode: raw magnetometer and gyroscope.
pub const MODE_MAGGYRO: u8 = 0x6;
/// Operation mode: all three raw sensors.
pub const MODE_AMG: u8 = 0x7;
/// Operation mode: fusion, relative orientation from accelerometer + gyroscope (100 Hz).
pub const MODE_IMU: u8 = 0x8;
/// Operation mode: fusion, absolute heading (20 Hz).
pub const MODE_COMPASS: u8 = 0x9;
/// Operation mode: fusion, relative orientation with magnetometer for gyroscope (50 Hz).
pub const MODE_M4G: u8 = 0xA;
/// Operation mode: 9-DOF fusion, fast magnetometer calibration off (100 Hz).
pub const MODE_NDOF_FMC_OFF: u8 = 0xB;
/// Operation mode: 9-DOF absolute orientation (100 Hz, the default).
pub const MODE_NDOF: u8 = 0xC;

/// Interrupt source: accelerometer / fusion data ready.
pub const SOURCE_ACC_BSX_DRDY: u8 = 0x01;
/// Interrupt source: magnetometer data ready.
pub const SOURCE_MAG_DRDY: u8 = 0x02;
/// Interrupt source: gyroscope any motion.
pub const SOURCE_GYRO_AM: u8 = 0x04;
/// Interrupt source: gyroscope high rate.
pub const SOURCE_GYR_HIGH_RATE: u8 = 0x08;
/// Interrupt source: gyroscope data ready.
pub const SOURCE_GYR_DRDY: u8 = 0x10;
/// Interrupt source: accelerometer high-g.
pub const SOURCE_ACC_HIGH_G: u8 = 0x20;
/// Interrupt source: accelerometer any motion.
pub const SOURCE_ACC_AM: u8 = 0x40;
/// Interrupt source: accelerometer no/slow motion.
pub const SOURCE_ACC_NM: u8 = 0x80;

/// Power mode: normal.
pub const POWER_NORMAL: u8 = 0;
/// Power mode: low power (accelerometer only, after 5 s without motion).
pub const POWER_LOW_POWER: u8 = 1;
/// Power mode: suspend (all sensors and the microcontroller asleep).
pub const POWER_SUSPEND: u8 = 2;

/// Temperature source: accelerometer.
pub const TEMP_SOURCE_ACCELEROMETER: u8 = 0;
/// Temperature source: gyroscope.
pub const TEMP_SOURCE_GYROSCOPE: u8 = 1;

/// Errors returned by the BNO055 drivers.
#[derive(Debug, PartialEq)]
pub enum Bno055Error<E> {
    /// The underlying I²C bus failed (also reported if the chip never ACKs within 650 ms).
    I2c(E),
    /// `CHIP_ID` did not read `0xA0` (wrong chip or address); carries the value read.
    BadChipId(u8),
    /// An argument is outside the range the chip accepts.
    InvalidArgument,
}

type Res<T, I2C> = Result<T, Bno055Error<<I2C as embedded_hal::i2c::ErrorType>::Error>>;

fn le16(b: &[u8], i: usize) -> i16 {
    i16::from_le_bytes([b[i], b[i + 1]])
}

/// Self-test result of [`Bno055Full::self_test`]; `true` = passed.
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct SelfTestResult {
    /// Accelerometer self-test passed.
    pub acc: bool,
    /// Magnetometer self-test passed.
    pub mag: bool,
    /// Gyroscope self-test passed.
    pub gyr: bool,
    /// Microcontroller self-test passed.
    pub mcu: bool,
}

/// Identification registers `0x00`–`0x06`, returned by [`Bno055Full::revisions`].
#[derive(Debug, PartialEq, Clone, Copy)]
pub struct Revisions {
    /// `CHIP_ID` (`0xA0`).
    pub chip_id: u8,
    /// `ACC_ID` (`0xFB`).
    pub acc_id: u8,
    /// `MAG_ID` (`0x32`).
    pub mag_id: u8,
    /// `GYR_ID` (`0x0F`).
    pub gyr_id: u8,
    /// Software revision (e.g. `0x0311` = 3.11).
    pub sw_revision: u16,
    /// Bootloader version.
    pub bootloader: u8,
}

/// BNO055 minimal driver — fused Euler angles.
///
/// Waits for the chip to boot (up to 650 ms), checks `CHIP_ID`, selects normal power mode and
/// `UNIT_SEL` = `0x86`, then enters NDOF fusion mode (100 Hz absolute orientation, internal
/// oscillator).
pub struct Bno055Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    mode: u8,
}

impl<I2C: I2c> Bno055Minimal<I2C> {
    /// Create a new `Bno055Minimal`: wait for boot, verify `CHIP_ID` and enter NDOF.
    ///
    /// # Arguments
    /// * `i2c`   — Configured I²C bus implementing [`embedded_hal::i2c::I2c`] (clock stretching required).
    /// * `addr`  — 7-bit device address (`0x29` with COM3 high, `0x28` with COM3 low).
    /// * `delay` — Delay provider (10 ms boot polling, 7 ms mode switch).
    ///
    /// # Errors
    /// [`Bno055Error::BadChipId`] if `CHIP_ID` is not `0xA0`, [`Bno055Error::I2c`] if the chip never answers.
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Res<Self, I2C> {
        let mut s = Self { i2c, addr, mode: MODE_NDOF };
        s.initialize(delay)?;
        Ok(s)
    }

    fn initialize<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        let mut last: Res<(), I2C> = Ok(());
        let mut found = false;
        for _ in 0..66 {
            // the chip does not ACK for up to 650 ms
            match self.r1(REG_CHIP_ID) {
                Ok(CHIP_ID_VALUE) => {
                    found = true;
                    break;
                }
                Ok(other) => last = Err(Bno055Error::BadChipId(other)),
                Err(e) => last = Err(e),
            }
            delay.delay_ms(10);
        }
        if !found {
            return last;
        }
        self.w(REG_PWR_MODE, 0x00)?;
        self.w(REG_PAGE_ID, 0x00)?;
        self.w(REG_UNIT_SEL, UNIT_SEL_SI)?;
        self.w(REG_OPR_MODE, MODE_NDOF)?;
        self.mode = MODE_NDOF;
        delay.delay_ms(7);
        Ok(())
    }

    fn w(&mut self, reg: u8, value: u8) -> Res<(), I2C> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value]).map_err(Bno055Error::I2c)
    }

    fn rd(&mut self, reg: u8, buf: &mut [u8]) -> Res<(), I2C> {
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, buf).map_err(Bno055Error::I2c)
    }

    fn r1(&mut self, reg: u8) -> Res<u8, I2C> {
        let mut b = [0u8; 1];
        self.rd(reg, &mut b)?;
        Ok(b[0])
    }

    fn vector(&mut self, reg: u8, scale: f32) -> Res<(f32, f32, f32), I2C> {
        let mut b = [0u8; 6];
        self.rd(reg, &mut b)?;
        Ok((le16(&b, 0) as f32 / scale, le16(&b, 2) as f32 / scale, le16(&b, 4) as f32 / scale))
    }

    /// Read the fused Euler angles (one burst of `0x1A`–`0x1F`, raw / 900).
    ///
    /// Returns (heading, roll, pitch) in rad.
    pub fn euler(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.vector(REG_EUL_DATA, 900.0)
    }
}

/// BNO055 full driver — all outputs, calibration, modes and interrupts. Owns a [`Bno055Minimal`].
pub struct Bno055Full<I2C> {
    inner: Bno055Minimal<I2C>,
    clk_sel: u8,
}

impl<I2C: I2c> Bno055Full<I2C> {
    /// Create a new `Bno055Full`; see [`Bno055Minimal::new`].
    pub fn new<D: DelayNs>(i2c: I2C, addr: u8, delay: &mut D) -> Res<Self, I2C> {
        Ok(Self { inner: Bno055Minimal::new(i2c, addr, delay)?, clk_sel: 0 })
    }

    /// Read the fused Euler angles; see [`Bno055Minimal::euler`].
    pub fn euler(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.euler()
    }

    fn enter_config<D: DelayNs>(&mut self, delay: &mut D) -> Res<u8, I2C> {
        let prev = self.inner.mode;
        self.inner.w(REG_OPR_MODE, MODE_CONFIG)?;
        delay.delay_ms(19);
        Ok(prev)
    }

    fn leave_config<D: DelayNs>(&mut self, mode: u8, delay: &mut D) -> Res<(), I2C> {
        if mode != MODE_CONFIG {
            self.inner.w(REG_OPR_MODE, mode)?;
            delay.delay_ms(7);
        }
        Ok(())
    }

    /// Select the operation mode through CONFIGMODE (19 ms + 7 ms waits).
    ///
    /// # Errors
    /// [`Bno055Error::InvalidArgument`] if `mode` > 12.
    pub fn set_mode<D: DelayNs>(&mut self, mode: u8, delay: &mut D) -> Res<(), I2C> {
        if mode > MODE_NDOF {
            return Err(Bno055Error::InvalidArgument);
        }
        self.inner.w(REG_OPR_MODE, MODE_CONFIG)?;
        delay.delay_ms(19);
        if mode != MODE_CONFIG {
            self.inner.w(REG_OPR_MODE, mode)?;
            delay.delay_ms(7);
        }
        self.inner.mode = mode;
        Ok(())
    }

    /// Read `OPR_MODE` (0–12).
    pub fn get_mode(&mut self) -> Res<u8, I2C> {
        Ok(self.inner.r1(REG_OPR_MODE)? & 0x0F)
    }

    /// Read the fused orientation quaternion (raw / 16384). Returns (w, x, y, z).
    pub fn quaternion(&mut self) -> Res<(f32, f32, f32, f32), I2C> {
        let mut b = [0u8; 8];
        self.inner.rd(REG_QUA_DATA, &mut b)?;
        Ok((le16(&b, 0) as f32 / 16384.0, le16(&b, 2) as f32 / 16384.0,
            le16(&b, 4) as f32 / 16384.0, le16(&b, 6) as f32 / 16384.0))
    }

    /// Read the gravity-free linear acceleration (raw / 100). Returns (x, y, z) in m/s².
    pub fn linear_acceleration(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.vector(REG_LIA_DATA, 100.0)
    }

    /// Read the gravity vector (raw / 100). Returns (x, y, z) in m/s².
    pub fn gravity(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.vector(REG_GRV_DATA, 100.0)
    }

    /// Read the raw accelerometer (raw / 100). Returns (x, y, z) in m/s².
    pub fn acceleration(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.vector(REG_ACC_DATA, 100.0)
    }

    /// Read the raw magnetometer (raw / 16). Returns (x, y, z) in µT.
    pub fn magnetic_field(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.vector(REG_MAG_DATA, 16.0)
    }

    /// Read the raw gyroscope (raw / 900). Returns (x, y, z) in rad/s.
    pub fn angular_rate(&mut self) -> Res<(f32, f32, f32), I2C> {
        self.inner.vector(REG_GYR_DATA, 900.0)
    }

    /// Read the temperature in °C (signed 8 bit, 1 LSB = 1 °C).
    pub fn temperature(&mut self) -> Res<i8, I2C> {
        Ok(self.inner.r1(REG_TEMP)? as i8)
    }

    /// Read `CALIB_STAT`. Returns (sys, gyr, acc, mag), each 0 (uncalibrated) … 3 (fully calibrated).
    pub fn calibration_status(&mut self) -> Res<(u8, u8, u8, u8), I2C> {
        let v = self.inner.r1(REG_CALIB_STAT)?;
        Ok(((v >> 6) & 3, (v >> 4) & 3, (v >> 2) & 3, v & 3))
    }

    /// True if sys, gyr, acc and mag are all 3.
    pub fn is_calibrated(&mut self) -> Res<bool, I2C> {
        Ok(self.inner.r1(REG_CALIB_STAT)? == 0xFF)
    }

    /// Read the 22 offset/radius bytes `0x55`–`0x6A` in CONFIGMODE (previous mode restored).
    pub fn get_calibration<D: DelayNs>(&mut self, delay: &mut D) -> Res<[u8; 22], I2C> {
        let prev = self.enter_config(delay)?;
        let mut data = [0u8; 22];
        let r = self.inner.rd(REG_ACC_OFFSET, &mut data);
        self.leave_config(prev, delay)?;
        r?;
        Ok(data)
    }

    /// Write the 22 offset/radius bytes in CONFIGMODE (previous mode restored).
    pub fn set_calibration<D: DelayNs>(&mut self, data: &[u8; 22], delay: &mut D) -> Res<(), I2C> {
        let prev = self.enter_config(delay)?;
        let mut r = Ok(());
        for (i, b) in data.iter().enumerate() {
            r = self.inner.w(REG_ACC_OFFSET + i as u8, *b);
            if r.is_err() {
                break;
            }
        }
        self.leave_config(prev, delay)?;
        r
    }

    /// Write `AXIS_MAP_CONFIG` / `AXIS_MAP_SIGN` in CONFIGMODE (previous mode restored).
    pub fn set_axis_remap<D: DelayNs>(&mut self, config: u8, sign: u8, delay: &mut D) -> Res<(), I2C> {
        let prev = self.enter_config(delay)?;
        self.inner.w(REG_AXIS_MAP_CONFIG, config)?;
        self.inner.w(REG_AXIS_MAP_SIGN, sign)?;
        self.leave_config(prev, delay)
    }

    /// Select the power mode ([`POWER_NORMAL`], [`POWER_LOW_POWER`], [`POWER_SUSPEND`]) in CONFIGMODE.
    pub fn set_power_mode<D: DelayNs>(&mut self, mode: u8, delay: &mut D) -> Res<(), I2C> {
        if mode > POWER_SUSPEND {
            return Err(Bno055Error::InvalidArgument);
        }
        let prev = self.enter_config(delay)?;
        self.inner.w(REG_PWR_MODE, mode)?;
        self.leave_config(prev, delay)
    }

    /// Select the temperature sensor ([`TEMP_SOURCE_ACCELEROMETER`] / [`TEMP_SOURCE_GYROSCOPE`]) in CONFIGMODE.
    pub fn set_temperature_source<D: DelayNs>(&mut self, source: u8, delay: &mut D) -> Res<(), I2C> {
        if source > TEMP_SOURCE_GYROSCOPE {
            return Err(Bno055Error::InvalidArgument);
        }
        let prev = self.enter_config(delay)?;
        self.inner.w(REG_TEMP_SOURCE, source)?;
        self.leave_config(prev, delay)
    }

    /// Select the external 32.768 kHz crystal (only if fitted) or the internal oscillator (CONFIGMODE).
    pub fn use_external_crystal<D: DelayNs>(&mut self, on: bool, delay: &mut D) -> Res<(), I2C> {
        let prev = self.enter_config(delay)?;
        self.clk_sel = if on { 0x80 } else { 0x00 };
        self.inner.w(REG_SYS_TRIGGER, self.clk_sel)?;
        self.leave_config(prev, delay)
    }

    /// Run the power-on self-test (CONFIGMODE, previous mode restored; waits 500 ms).
    pub fn self_test<D: DelayNs>(&mut self, delay: &mut D) -> Res<SelfTestResult, I2C> {
        let prev = self.enter_config(delay)?;
        self.inner.w(REG_SYS_TRIGGER, self.clk_sel | 0x01)?;
        delay.delay_ms(500);
        let v = self.inner.r1(REG_ST_RESULT)?;
        self.leave_config(prev, delay)?;
        Ok(SelfTestResult { acc: v & 1 != 0, mag: v & 2 != 0, gyr: v & 4 != 0, mcu: v & 8 != 0 })
    }

    /// Read `SYS_STATUS` and `SYS_ERR`. Returns (status, error); status 5 = fusion running, error 0 = none.
    pub fn system_status(&mut self) -> Res<(u8, u8), I2C> {
        let mut b = [0u8; 2];
        self.inner.rd(REG_SYS_STATUS, &mut b)?;
        Ok((b[0], b[1]))
    }

    /// Reset the chip (`RST_SYS`), wait 650 ms and re-initialise into NDOF.
    pub fn reset<D: DelayNs>(&mut self, delay: &mut D) -> Res<(), I2C> {
        self.inner.w(REG_SYS_TRIGGER, self.clk_sel | 0x20)?;
        delay.delay_ms(650);
        self.clk_sel = 0;
        self.inner.initialize(delay)
    }

    /// Read the identification and revision registers `0x00`–`0x06`.
    pub fn revisions(&mut self) -> Res<Revisions, I2C> {
        let mut b = [0u8; 7];
        self.inner.rd(REG_CHIP_ID, &mut b)?;
        Ok(Revisions { chip_id: b[0], acc_id: b[1], mag_id: b[2], gyr_id: b[3],
                       sw_revision: u16::from(b[4]) | (u16::from(b[5]) << 8), bootloader: b[6] })
    }

    /// Read the 16-byte unique ID from page 1 (page 0 restored).
    pub fn unique_id(&mut self) -> Res<[u8; 16], I2C> {
        self.inner.w(REG_PAGE_ID, 0x01)?;
        let mut data = [0u8; 16];
        let r = self.inner.rd(REG_UNIQUE_ID, &mut data);
        self.inner.w(REG_PAGE_ID, 0x00)?;
        r?;
        Ok(data)
    }

    fn update_int_reg(&mut self, source: u8, enable: bool) -> Res<(), I2C> {
        self.inner.w(REG_PAGE_ID, 0x01)?;
        let mut r = Ok(());
        for reg in [REG_INT_EN, REG_INT_MSK] {
            r = self.inner.r1(reg).and_then(|v| {
                let nv = if enable { v | source } else { v & !source };
                self.inner.w(reg, nv)
            });
            if r.is_err() {
                break;
            }
        }
        self.inner.w(REG_PAGE_ID, 0x00)?;
        r
    }

    /// Enable interrupt source bits in `INT_EN` and `INT_MSK` (page 1, page 0 restored).
    pub fn enable_interrupt(&mut self, source: u8) -> Res<(), I2C> {
        self.update_int_reg(source, true)
    }

    /// Disable interrupt source bits in `INT_EN` and `INT_MSK`.
    pub fn disable_interrupt(&mut self, source: u8) -> Res<(), I2C> {
        self.update_int_reg(source, false)
    }

    /// Read `INT_STA` (cleared on read) as `SOURCE_*` bits.
    ///
    /// The caller is responsible for wiring this into an ISR (INT pin, rising edge) or a polling loop.
    pub fn poll_interrupt(&mut self) -> Res<u8, I2C> {
        self.inner.r1(REG_INT_STA)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::{ErrorKind, ErrorType, Operation};
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use std::collections::HashMap;

    const ADDR: u8 = 0x29;

    /// Flat register-map I²C fake that logs every write.
    struct BnoI2c {
        regs: HashMap<u8, u8>,
        log: Vec<Vec<u8>>,
        nack: u32,
    }

    impl BnoI2c {
        fn new() -> Self {
            let mut s = Self { regs: HashMap::new(), log: Vec::new(), nack: 0 };
            s.set(0x00, &[0xA0, 0xFB, 0x32, 0x0F, 0x11, 0x03, 0x15]);
            s
        }
        fn set(&mut self, reg: u8, values: &[u8]) {
            for (i, v) in values.iter().enumerate() {
                self.regs.insert(reg + i as u8, *v);
            }
        }
        fn writes_to(&self, reg: u8) -> Vec<u8> {
            self.log.iter().filter(|d| d.len() == 2 && d[0] == reg).map(|d| d[1]).collect()
        }
    }

    impl ErrorType for BnoI2c {
        type Error = ErrorKind;
    }

    impl I2c for BnoI2c {
        fn transaction(&mut self, _address: u8, operations: &mut [Operation<'_>]) -> Result<(), ErrorKind> {
            if self.nack > 0 {
                self.nack -= 1;
                return Err(ErrorKind::Other);
            }
            let mut reg = 0u8;
            for op in operations.iter_mut() {
                match op {
                    Operation::Write(data) => {
                        self.log.push(data.to_vec());
                        reg = data[0];
                        for (i, v) in data[1..].iter().enumerate() {
                            self.regs.insert(reg + i as u8, *v);
                        }
                    }
                    Operation::Read(buf) => {
                        for (i, b) in buf.iter_mut().enumerate() {
                            *b = *self.regs.get(&(reg + i as u8)).unwrap_or(&0);
                        }
                    }
                }
            }
            Ok(())
        }
    }

    fn le(vals: &[i16]) -> Vec<u8> {
        vals.iter().flat_map(|v| v.to_le_bytes()).collect()
    }

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-4
    }

    fn make() -> Bno055Full<BnoI2c> {
        Bno055Full::new(BnoI2c::new(), ADDR, &mut NoopDelay).unwrap()
    }

    #[test]
    fn init_sequence() {
        let s = make();
        let w: Vec<&Vec<u8>> = s.inner.i2c.log.iter().filter(|d| d.len() == 2).collect();
        assert!(w.contains(&&vec![0x3E, 0x00]));
        assert!(w.contains(&&vec![0x07, 0x00]));
        assert!(w.contains(&&vec![0x3B, 0x86]));
        assert_eq!(w.last().unwrap().as_slice(), &[0x3D, 0x0C]);
    }

    #[test]
    fn bad_chip_id() {
        let mut i2c = BnoI2c::new();
        i2c.set(0x00, &[0x55]);
        let r = Bno055Minimal::new(i2c, ADDR, &mut NoopDelay);
        assert!(matches!(r, Err(Bno055Error::BadChipId(0x55))));
    }

    #[test]
    fn boot_retry_on_nack() {
        let mut i2c = BnoI2c::new();
        i2c.nack = 5;
        let m = Bno055Minimal::new(i2c, ADDR, &mut NoopDelay);
        assert!(m.is_ok());
    }

    #[test]
    fn euler_conversion() {
        let mut i2c = BnoI2c::new();
        i2c.set(0x1A, &le(&[1414, -900, 450]));
        let mut m = Bno055Minimal::new(i2c, ADDR, &mut NoopDelay).unwrap();
        let (h, r, p) = m.euler().unwrap();
        assert!(close(h, 1414.0 / 900.0) && close(r, -1.0) && close(p, 0.5));
    }

    #[test]
    fn vectors() {
        let mut s = make();
        s.inner.i2c.set(0x20, &le(&[16384, -8192, 0, 4096]));
        assert_eq!(s.quaternion().unwrap(), (1.0, -0.5, 0.0, 0.25));
        s.inner.i2c.set(0x28, &le(&[100, -250, 981]));
        let (x, y, z) = s.linear_acceleration().unwrap();
        assert!(close(x, 1.0) && close(y, -2.5) && close(z, 9.81));
        s.inner.i2c.set(0x2E, &le(&[0, 0, 981]));
        assert!(close(s.gravity().unwrap().2, 9.81));
        s.inner.i2c.set(0x08, &le(&[-100, 200, 300]));
        assert_eq!(s.acceleration().unwrap(), (-1.0, 2.0, 3.0));
        s.inner.i2c.set(0x0E, &le(&[160, -32, 16]));
        assert_eq!(s.magnetic_field().unwrap(), (10.0, -2.0, 1.0));
        s.inner.i2c.set(0x14, &le(&[900, 0, -450]));
        assert_eq!(s.angular_rate().unwrap(), (1.0, 0.0, -0.5));
        s.inner.i2c.set(0x34, &[0xE7]);
        assert_eq!(s.temperature().unwrap(), -25);
    }

    #[test]
    fn calibration_status() {
        let mut s = make();
        s.inner.i2c.set(0x35, &[0xE4]);
        assert_eq!(s.calibration_status().unwrap(), (3, 2, 1, 0));
        assert!(!s.is_calibrated().unwrap());
        s.inner.i2c.set(0x35, &[0xFF]);
        assert!(s.is_calibrated().unwrap());
    }

    #[test]
    fn modes() {
        let mut s = make();
        s.inner.i2c.log.clear();
        s.set_mode(MODE_IMU, &mut NoopDelay).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x3D), vec![0x00, 0x08]);
        s.inner.i2c.set(0x3D, &[0x08]);
        assert_eq!(s.get_mode().unwrap(), 8);
        assert!(matches!(s.set_mode(13, &mut NoopDelay), Err(Bno055Error::InvalidArgument)));
    }

    #[test]
    fn calibration_blob() {
        let mut s = make();
        let blob: [u8; 22] = core::array::from_fn(|i| i as u8 + 1);
        s.inner.i2c.set(0x55, &blob);
        s.inner.i2c.log.clear();
        assert_eq!(s.get_calibration(&mut NoopDelay).unwrap(), blob);
        assert_eq!(s.inner.i2c.writes_to(0x3D), vec![0x00, 0x0C]);
        s.inner.i2c.log.clear();
        s.set_calibration(&blob, &mut NoopDelay).unwrap();
        let data: Vec<&Vec<u8>> = s.inner.i2c.log.iter().filter(|d| d[0] >= 0x55 && d[0] <= 0x6A).collect();
        assert_eq!(data.len(), 22);
        assert!(data.iter().enumerate().all(|(i, d)| d[0] == 0x55 + i as u8 && d[1] == blob[i]));
        assert_eq!(s.inner.i2c.writes_to(0x3D), vec![0x00, 0x0C]);
    }

    #[test]
    fn configuration() {
        let mut s = make();
        s.inner.i2c.log.clear();
        s.set_axis_remap(0x21, 0x02, &mut NoopDelay).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x41), vec![0x21]);
        assert_eq!(s.inner.i2c.writes_to(0x42), vec![0x02]);
        assert_eq!(s.inner.i2c.writes_to(0x3D), vec![0x00, 0x0C]);
        s.set_power_mode(POWER_LOW_POWER, &mut NoopDelay).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x3E), vec![1]);
        assert!(matches!(s.set_power_mode(3, &mut NoopDelay), Err(Bno055Error::InvalidArgument)));
        s.set_temperature_source(TEMP_SOURCE_GYROSCOPE, &mut NoopDelay).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x40), vec![1]);
        s.use_external_crystal(true, &mut NoopDelay).unwrap();
        s.use_external_crystal(false, &mut NoopDelay).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x3F), vec![0x80, 0x00]);
    }

    #[test]
    fn self_test_status_revisions_unique_id() {
        let mut s = make();
        s.inner.i2c.set(0x36, &[0x0D]);
        s.inner.i2c.log.clear();
        let st = s.self_test(&mut NoopDelay).unwrap();
        assert_eq!(st, SelfTestResult { acc: true, mag: false, gyr: true, mcu: true });
        assert_eq!(s.inner.i2c.writes_to(0x3F), vec![0x01]);
        assert_eq!(s.inner.i2c.writes_to(0x3D), vec![0x00, 0x0C]);
        s.inner.i2c.set(0x39, &[1, 3]);
        assert_eq!(s.system_status().unwrap(), (1, 3));
        let r = s.revisions().unwrap();
        assert_eq!(r, Revisions { chip_id: 0xA0, acc_id: 0xFB, mag_id: 0x32, gyr_id: 0x0F, sw_revision: 0x0311, bootloader: 0x15 });
        let uid: Vec<u8> = (0..16).collect();
        s.inner.i2c.set(0x50, &uid);
        s.inner.i2c.log.clear();
        assert_eq!(s.unique_id().unwrap().to_vec(), uid);
        assert_eq!(s.inner.i2c.writes_to(0x07), vec![1, 0]);
    }

    #[test]
    fn interrupts_and_reset() {
        let mut s = make();
        s.inner.i2c.set(0x10, &[0x00]);
        s.inner.i2c.set(0x0F, &[0x00]);
        s.inner.i2c.log.clear();
        s.enable_interrupt(SOURCE_ACC_BSX_DRDY | SOURCE_GYR_DRDY).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x10), vec![0x11]);
        assert_eq!(s.inner.i2c.writes_to(0x0F), vec![0x11]);
        assert_eq!(s.inner.i2c.writes_to(0x07), vec![1, 0]);
        s.inner.i2c.set(0x10, &[0x11]);
        s.inner.i2c.set(0x0F, &[0x11]);
        s.inner.i2c.log.clear();
        s.disable_interrupt(SOURCE_GYR_DRDY).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x10), vec![0x01]);
        assert_eq!(s.inner.i2c.writes_to(0x0F), vec![0x01]);
        s.inner.i2c.set(0x37, &[0x41]);
        assert_eq!(s.poll_interrupt().unwrap(), 0x41);
        s.inner.i2c.log.clear();
        s.reset(&mut NoopDelay).unwrap();
        assert_eq!(s.inner.i2c.writes_to(0x3F), vec![0x20]);
        assert_eq!(*s.inner.i2c.writes_to(0x3D).last().unwrap(), 0x0C);
    }
}
