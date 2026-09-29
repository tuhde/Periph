//! ADE7953 — single-phase multifunction metering IC (Analog Devices).
//!
//! Communicates over I²C, SPI, or UART. Provides RMS voltage, two current
//! channels (phase A and neutral B), instantaneous and accumulated active /
//! reactive / apparent power and energy, power factor, phase angle, line
//! period and frequency. Calibration is supplied at construction time as
//! `voltage_gain` (real V at the mains per V at VP–VN) and `current_gain`
//! (real A per V at IAP–IAN) — there is no universal default because the
//! scaling depends entirely on the external CT + burden / shunt / Rogowski
//! and voltage-divider network.
//!
//! Reference: `specs/power/ade7953.md`.

use core::f32::consts::PI;
use embedded_hal::i2c::I2c;
use embedded_hal::delay::DelayNs;

// 8-bit registers
pub const REG_DISNOLOAD: u16       = 0x001;
pub const REG_PGA_V: u16           = 0x007;
pub const REG_PGA_IA: u16          = 0x008;
pub const REG_PGA_IB: u16          = 0x009;
pub const REG_WRITE_PROTECT: u16   = 0x040;
pub const REG_VERSION: u16         = 0x702;
pub const REG_EX_REF: u16          = 0x800;

// 16-bit registers
pub const REG_CONFIG: u16          = 0x102;
pub const REG_CF1DEN: u16          = 0x103;
pub const REG_CF2DEN: u16          = 0x104;
pub const REG_CFMODE: u16          = 0x107;
pub const REG_PHCALA: u16          = 0x108;
pub const REG_PHCALB: u16          = 0x109;
pub const REG_PFA: u16             = 0x10A;
pub const REG_ANGLE_A: u16         = 0x10C;
pub const REG_PERIOD: u16          = 0x10E;
pub const REG_ALT_OUTPUT: u16      = 0x110;
pub const REG_INTERNAL_RES: u16    = 0x120;

// 24-bit / 32-bit registers (24-bit addresses, 32-bit aliases +0x100)
pub const REG_SAGLVL: u16          = 0x200;
pub const REG_ACCMODE: u16         = 0x201;
pub const REG_AP_NOLOAD: u16       = 0x203;
pub const REG_VAR_NOLOAD: u16      = 0x204;
pub const REG_VA_NOLOAD: u16       = 0x205;
pub const REG_AWATT: u16           = 0x212;
pub const REG_VRMS: u16            = 0x21C;
pub const REG_AENERGYA: u16        = 0x21E;
pub const REG_OVLVL: u16           = 0x224;
pub const REG_OILVL: u16           = 0x225;
pub const REG_IRQENA: u16          = 0x22C;
pub const REG_RSTIRQSTATA: u16     = 0x22E;
pub const REG_IRQENB: u16          = 0x22F;
pub const REG_RSTIRQSTATB: u16     = 0x231;
pub const REG_CRC: u16             = 0x37F;
pub const REG_AWGAIN: u16          = 0x282;
pub const REG_AWATTOS: u16         = 0x289;

pub const REG_120_UNLOCK_ADDR: u16 = 0x0FE;
pub const REG_120_UNLOCK: u8       = 0xAD;
pub const REG_120_VALUE: u16       = 0x30;

const ADC_FS_VOLTS: f32   = 0.5 / core::f32::consts::SQRT_2;
const ADC_FS_CODE: i32    = 9032007;
const POWER_FS_CODE: i32  = 4862401;
const T_SAMPLE: f32       = 1.0 / 206900.0;
const PF_LSB: f32         = 1.0 / 32768.0;
const ANGLE_LSB: f32      = 1.0 / 223750.0;
const PHASE_LSB: f32      = 1.0 / 895000.0;

const BUS_I2C: u8   = 0;
const BUS_SPI: u8   = 1;
const BUS_UART: u8  = 2;

/// ADE7953 single-phase metering IC — minimal interface.
pub struct Ade7953Minimal<I2C> {
    conn: Connection<I2C>,
    addr: u8,
    bus_type: u8,
    voltage_gain: f32,
    current_gain_a: f32,
    current_gain_b: f32,
    pga_a: u8,
    pga_b: u8,
    pga_v: u8,
}

/// Bus connection wrapper.
pub struct Connection<I2C> {
    i2c: I2C,
    enabled: bool,
}

impl<I2C> Connection<I2C> {
    pub fn new(i2c: I2C) -> Self {
        Self { i2c, enabled: true }
    }
    pub fn enable(&mut self) { self.enabled = true; }
    pub fn disable(&mut self) { self.enabled = false; }
    pub fn is_enabled(&mut self) -> bool { self.enabled }
}

impl<I2C: I2c> Connection<I2C> {
    pub fn read(&mut self, addr: u8, reg: u16, buf: &mut [u8]) -> Result<(), I2C::Error> {
        if !self.enabled { for b in buf.iter_mut() { *b = 0; } return Ok(()); }
        self.i2c.write_read(addr, &[(reg >> 8) as u8, reg as u8], buf)
    }
    pub fn write(&mut self, addr: u8, reg: u16, data: &[u8]) -> Result<(), I2C::Error> {
        if !self.enabled { return Ok(()); }
        let mut buf = [0u8; 6];
        buf[0] = (reg >> 8) as u8;
        buf[1] = reg as u8;
        buf[2..2 + data.len()].copy_from_slice(data);
        self.i2c.write(addr, &buf[..2 + data.len()])
    }
}

impl<I2C: I2c> Ade7953Minimal<I2C> {
    /// Construct and initialise the ADE7953.
    ///
    /// # Arguments
    /// * `i2c` — I²C bus (driver takes ownership).
    /// * `addr` — 7-bit I²C address (fixed at 0x38 for the ADE7953).
    /// * `voltage_gain` — Real volts at the mains per volt at VP–VN.
    /// * `current_gain` — Real amperes per volt at IAP–IAN.
    /// * `delay` — Delay provider for the 100 ms power-up wait.
    pub fn new<D: DelayNs>(
        i2c: I2C,
        addr: u8,
        voltage_gain: f32,
        current_gain: f32,
        delay: &mut D,
    ) -> Result<Self, I2C::Error> {
        let mut conn = Connection::new(i2c);
        delay.delay_ms(110);
        conn.write(addr, REG_120_UNLOCK_ADDR, &[REG_120_UNLOCK])?;
        let payload = [(REG_120_VALUE >> 8) as u8, REG_120_VALUE as u8];
        conn.write(addr, REG_INTERNAL_RES, &payload)?;
        Ok(Self {
            conn,
            addr,
            bus_type: BUS_I2C,
            voltage_gain,
            current_gain_a: current_gain,
            current_gain_b: current_gain,
            pga_a: 1,
            pga_b: 1,
            pga_v: 1,
        })
    }

    fn read_u24(&mut self, reg: u16) -> Result<u32, I2C::Error> {
        let mut buf = [0u8; 3];
        self.conn.read(self.addr, reg, &mut buf)?;
        Ok(((buf[0] as u32) << 16) | ((buf[1] as u32) << 8) | buf[2] as u32)
    }

    fn read_s24(&mut self, reg: u16) -> Result<i32, I2C::Error> {
        let v = self.read_u24(reg)?;
        let sign = (v & 0x800000) != 0;
        let raw = if sign { (v & 0xFFFFFF) as i32 - 0x1000000 } else { v as i32 };
        Ok(raw)
    }

    fn read_u16(&mut self, reg: u16) -> Result<u16, I2C::Error> {
        let mut buf = [0u8; 2];
        self.conn.read(self.addr, reg, &mut buf)?;
        Ok(((buf[0] as u16) << 8) | buf[1] as u16)
    }

    fn read_s16(&mut self, reg: u16) -> Result<i16, I2C::Error> {
        let v = self.read_u16(reg)?;
        Ok(v as i16)
    }

    fn write_u8(&mut self, reg: u16, value: u8) -> Result<(), I2C::Error> {
        self.conn.write(self.addr, reg, &[value])
    }

    fn write_u16(&mut self, reg: u16, value: u16) -> Result<(), I2C::Error> {
        self.conn.write(self.addr, reg, &[(value >> 8) as u8, value as u8])
    }

    fn write_u24(&mut self, reg: u16, value: u32) -> Result<(), I2C::Error> {
        self.conn.write(self.addr, reg, &[(value >> 16) as u8, (value >> 8) as u8, value as u8])
    }

    fn voltage_scale(&self) -> f32 {
        (ADC_FS_VOLTS * self.voltage_gain) / (ADC_FS_CODE as f32 * self.pga_v as f32)
    }

    fn current_scale(&self, gain: f32) -> f32 {
        (ADC_FS_VOLTS * gain) / ADC_FS_CODE as f32
    }

    fn power_scale(&self, gain: f32) -> f32 {
        ((ADC_FS_VOLTS * ADC_FS_VOLTS) * self.voltage_gain * gain) / POWER_FS_CODE as f32
    }

    fn energy_scale(&self, gain: f32) -> f32 {
        ((ADC_FS_VOLTS * ADC_FS_VOLTS) * self.voltage_gain * gain * T_SAMPLE) / 3600.0
    }

    /// Read the RMS voltage on the voltage channel.
    /// Returns voltage in volts.
    pub fn voltage(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.read_u24(REG_VRMS)? as f32 * self.voltage_scale())
    }

    /// Read the RMS current on Current Channel A.
    /// Returns current in amperes.
    pub fn current(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.read_u24(0x21A)? as f32 * self.current_scale(self.current_gain_a))
    }

    /// Read instantaneous active power on Current Channel A.
    /// Returns active power in watts (signed).
    pub fn active_power(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.read_s24(REG_AWATT)? as f32 * self.power_scale(self.current_gain_a))
    }

    /// Read the active-energy accumulator for Current Channel A.
    /// Returns active energy in watt-hours accumulated since the previous call.
    pub fn active_energy(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.read_s24(REG_AENERGYA)? as f32 * self.energy_scale(self.current_gain_a))
    }

    /// Read the silicon version register.
    pub fn version(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.conn.read(self.addr, REG_VERSION, &mut buf)?;
        Ok(buf[0])
    }

    /// Read instantaneous reactive power on Current Channel A.
    pub fn reactive_power(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.read_s24(0x214)? as f32 * self.power_scale(self.current_gain_a))
    }

    /// Read instantaneous apparent power on Current Channel A.
    pub fn apparent_power(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.read_s24(0x210)? as f32 * self.power_scale(self.current_gain_a))
    }

    /// Read power factor for Current Channel A.
    /// Returns the value in range [-1.0, +1.0].
    pub fn power_factor(&mut self) -> Result<f32, I2C::Error> {
        Ok(self.read_s16(REG_PFA)? as f32 * PF_LSB)
    }

    /// Read line period in seconds.
    pub fn line_period(&mut self) -> Result<f32, I2C::Error> {
        Ok((self.read_u16(REG_PERIOD)? as f32 + 1.0) * ANGLE_LSB)
    }

    /// Read line frequency in Hertz.
    pub fn line_frequency(&mut self) -> Result<f32, I2C::Error> {
        Ok(1.0 / self.line_period()?)
    }

    /// Configure overvoltage threshold (volts).
    pub fn configure_overvoltage(&mut self, threshold: f32) -> Result<(), I2C::Error> {
        let mut raw = (threshold * (ADC_FS_CODE as f32 * self.pga_v as f32)) /
                      (ADC_FS_VOLTS * self.voltage_gain);
        if raw < 0.0 { raw = 0.0; }
        if raw > 0xFFFFFF as f32 { raw = 0xFFFFFF as f32; }
        self.write_u24(REG_OVLVL, raw as u32)
    }

    /// Configure overcurrent threshold (amperes; shared by both channels).
    pub fn configure_overcurrent(&mut self, threshold: f32) -> Result<(), I2C::Error> {
        let mut raw = (threshold * ADC_FS_CODE as f32) / (ADC_FS_VOLTS * self.current_gain_a);
        if raw < 0.0 { raw = 0.0; }
        if raw > 0xFFFFFF as f32 { raw = 0xFFFFFF as f32; }
        self.write_u24(REG_OILVL, raw as u32)
    }

    /// Software-reset the chip.
    pub fn reset<D2: DelayNs>(&mut self, delay: &mut D2) -> Result<(), I2C::Error> {
        let cfg = self.read_u16(REG_CONFIG)? | (1 << 7);
        self.write_u16(REG_CONFIG, cfg)?;
        delay.delay_ms(110);
        self.write_u8(REG_120_UNLOCK_ADDR, REG_120_UNLOCK)?;
        self.write_u16(REG_INTERNAL_RES, REG_120_VALUE)?;
        self.pga_a = 1;
        self.pga_b = 1;
        self.pga_v = 1;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x38;
    // voltage_gain=900.0, current_gain=30.0 -- fixture values chosen so the
    // overvoltage/overcurrent conversions below land comfortably inside the
    // 24-bit range (see the dedicated clamp test for the >0xFFFFFF case).
    const VOLTAGE_GAIN: f32 = 900.0;
    const CURRENT_GAIN: f32 = 30.0;

    fn new_ade7953(transactions: &[I2cTransaction]) -> Ade7953Minimal<I2cMock> {
        let mut init = vec![
            I2cTransaction::write(ADDR, vec![0x00, 0xFE, 0xAD]),
            I2cTransaction::write(ADDR, vec![0x01, 0x20, 0x00, 0x30]),
        ];
        init.extend_from_slice(transactions);
        let i2c = I2cMock::new(&init);
        let mut delay = NoopDelay::new();
        Ade7953Minimal::new(i2c, ADDR, VOLTAGE_GAIN, CURRENT_GAIN, &mut delay).expect("new")
    }

    #[test]
    fn full_api() {
        let mut ade = new_ade7953(&[
            // version()
            I2cTransaction::write_read(ADDR, vec![0x07, 0x02], vec![0xAB]),
            // voltage(): raw_VRMS = 1_000_000
            I2cTransaction::write_read(ADDR, vec![0x02, 0x1C], vec![0x0F, 0x42, 0x40]),
            // current(): raw_IRMSA = 500_000
            I2cTransaction::write_read(ADDR, vec![0x02, 0x1A], vec![0x07, 0xA1, 0x20]),
            // active_power(): raw_AWATT = 1_000_000
            I2cTransaction::write_read(ADDR, vec![0x02, 0x12], vec![0x0F, 0x42, 0x40]),
            // active_energy(): raw_AENERGYA = 2_000_000
            I2cTransaction::write_read(ADDR, vec![0x02, 0x1E], vec![0x1E, 0x84, 0x80]),
            // reactive_power(): raw_AVAR = -300_000 (two's complement 24-bit)
            I2cTransaction::write_read(ADDR, vec![0x02, 0x14], vec![0xFB, 0x6C, 0x20]),
            // apparent_power(): raw_AVA = 1_100_000
            I2cTransaction::write_read(ADDR, vec![0x02, 0x10], vec![0x10, 0xC8, 0xE0]),
            // power_factor(): raw_PFA = 20_000
            I2cTransaction::write_read(ADDR, vec![0x01, 0x0A], vec![0x4E, 0x20]),
            // line_period(): raw_Period = 4475
            I2cTransaction::write_read(ADDR, vec![0x01, 0x0E], vec![0x11, 0x7B]),
            // line_frequency() re-reads Period internally (calls line_period() again)
            I2cTransaction::write_read(ADDR, vec![0x01, 0x0E], vec![0x11, 0x7B]),
            // configure_overvoltage(260.0): raw = 260*ADC_FS_CODE/(ADC_FS_VOLTS*900) = 0x709C5F
            I2cTransaction::write(ADDR, vec![0x02, 0x24, 0x70, 0x9C, 0x5F]),
            // configure_overcurrent(5.0): raw = 5*ADC_FS_CODE/(ADC_FS_VOLTS*30) ~= 0x40F7C1
            // (f32 rounding vs the f64 reference calc; regression: previously
            // omitted current_gain entirely, writing a threshold ~30x too
            // large -- see current_gain fix)
            I2cTransaction::write(ADDR, vec![0x02, 0x25, 0x40, 0xF7, 0xC1]),
            // reset(): read CONFIG=0x8004, set bit 7 -> 0x8084, then re-run
            // the power-up unlock sequence
            I2cTransaction::write_read(ADDR, vec![0x01, 0x02], vec![0x80, 0x04]),
            I2cTransaction::write(ADDR, vec![0x01, 0x02, 0x80, 0x84]),
            I2cTransaction::write(ADDR, vec![0x00, 0xFE, 0xAD]),
            I2cTransaction::write(ADDR, vec![0x01, 0x20, 0x00, 0x30]),
        ]);

        assert_eq!(ade.version().unwrap(), 0xAB);
        assert!((ade.voltage().unwrap() - 35.230049).abs() < 1e-2);
        assert!((ade.current().unwrap() - 0.587167).abs() < 1e-3);
        assert!((ade.active_power().unwrap() - 694.101535).abs() < 1e-1);
        assert!((ade.active_energy().unwrap() - 9.062349).abs() < 1e-2);
        assert!((ade.reactive_power().unwrap() - (-208.230461)).abs() < 1e-1);
        assert!((ade.apparent_power().unwrap() - 763.511689).abs() < 1e-1);
        assert!((ade.power_factor().unwrap() - 0.610352).abs() < 1e-4);
        assert!((ade.line_period().unwrap() - 0.020004).abs() < 1e-5);
        assert!((ade.line_frequency().unwrap() - 49.988829).abs() < 1e-1);

        ade.configure_overvoltage(260.0).unwrap();
        ade.configure_overcurrent(5.0).unwrap();
        ade.reset(&mut NoopDelay::new()).unwrap();

        ade.conn.i2c.done();
    }

    #[test]
    fn configure_thresholds_clamp_and_floor_at_zero() {
        let mut ade = new_ade7953(&[
            // configure_overvoltage(-10.0): negative clamps to raw 0
            I2cTransaction::write(ADDR, vec![0x02, 0x24, 0x00, 0x00, 0x00]),
            // configure_overcurrent(100_000.0): raw overflows past 0xFFFFFF, clamps
            I2cTransaction::write(ADDR, vec![0x02, 0x25, 0xFF, 0xFF, 0xFF]),
        ]);

        ade.configure_overvoltage(-10.0).unwrap();
        ade.configure_overcurrent(100_000.0).unwrap();

        ade.conn.i2c.done();
    }
}