//! INA3221 — three-channel 26 V current/voltage/power monitor (Texas Instruments).
//!
//! Communicates over I²C (up to 400 kHz fast mode, 2.44 MHz high-speed mode).
//!
//! The INA3221 is a three-channel sibling of the INA226, but has **no Calibration,
//! Current, or Power registers** — current and power are computed in software from
//! the shunt voltage and the user-supplied shunt resistance.
//!
//! The [`Ina3221Minimal`] struct provides per-channel voltage, shunt voltage, current,
//! and power readings with no configuration beyond the transport and shunt resistors.

use embedded_hal::i2c::I2c;

const REG_CONFIG: u8 = 0x00;
const REG_SHUNT1: u8 = 0x01;
const REG_BUS1: u8 = 0x02;
const REG_SHUNT2: u8 = 0x03;
const REG_BUS2: u8 = 0x04;
const REG_SHUNT3: u8 = 0x05;
const REG_BUS3: u8 = 0x06;
const REG_CH1_CRIT: u8 = 0x07;
const REG_CH1_WARN: u8 = 0x08;
const REG_CH2_CRIT: u8 = 0x09;
const REG_CH2_WARN: u8 = 0x0A;
const REG_CH3_CRIT: u8 = 0x0B;
const REG_CH3_WARN: u8 = 0x0C;
const REG_SUM: u8 = 0x0D;
const REG_SUM_LIMIT: u8 = 0x0E;
const REG_MASK_EN: u8 = 0x0F;
const REG_PV_UPPER: u8 = 0x10;
const REG_PV_LOWER: u8 = 0x11;
const REG_MFR_ID: u8 = 0xFE;
const REG_DIE_ID: u8 = 0xFF;

const SHUNT_REGS: [u8; 3] = [REG_SHUNT1, REG_SHUNT2, REG_SHUNT3];
const BUS_REGS: [u8; 3] = [REG_BUS1, REG_BUS2, REG_BUS3];
const CRIT_REGS: [u8; 3] = [REG_CH1_CRIT, REG_CH2_CRIT, REG_CH3_CRIT];
const WARN_REGS: [u8; 3] = [REG_CH1_WARN, REG_CH2_WARN, REG_CH3_WARN];

/// Alert flag constant: Channel 1 critical-alert flag.
pub const CF1: u16 = 0x0200;
/// Alert flag constant: Channel 2 critical-alert flag.
pub const CF2: u16 = 0x0100;
/// Alert flag constant: Channel 3 critical-alert flag.
pub const CF3: u16 = 0x0080;
/// Alert flag constant: Summation-alert flag.
pub const SF: u16 = 0x0040;
/// Alert flag constant: Channel 1 warning-alert flag.
pub const WF1: u16 = 0x0020;
/// Alert flag constant: Channel 2 warning-alert flag.
pub const WF2: u16 = 0x0010;
/// Alert flag constant: Channel 3 warning-alert flag.
pub const WF3: u16 = 0x0008;
/// Alert flag constant: Power-valid flag.
pub const PVF: u16 = 0x0004;
/// Alert flag constant: Timing-control flag.
pub const TCF: u16 = 0x0002;
/// Alert flag constant: Conversion-ready flag.
pub const CVRF: u16 = 0x0001;

/// Mode constant: power-down.
pub const MODE_POWERDOWN: u8 = 0;
/// Mode constant: shunt voltage, single-shot triggered.
pub const MODE_SHUNT_TRIG: u8 = 1;
/// Mode constant: bus voltage, single-shot triggered.
pub const MODE_BUS_TRIG: u8 = 2;
/// Mode constant: shunt and bus, single-shot triggered.
pub const MODE_SHUNT_BUS_TRIG: u8 = 3;
/// Mode constant: shunt voltage, continuous.
pub const MODE_SHUNT_CONT: u8 = 5;
/// Mode constant: bus voltage, continuous.
pub const MODE_BUS_CONT: u8 = 6;
/// Mode constant: shunt and bus, continuous.
pub const MODE_SHUNT_BUS_CONT: u8 = 7;

fn channel_valid(ch: u8) -> u8 {
    if ch < 1 || ch > 3 {
        1
    } else {
        ch
    }
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u16) -> Result<(), I2C::Error> {
    let buf = [reg, (value >> 8) as u8, (value & 0xFF) as u8];
    i2c.write(addr, &buf)
}

fn read_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u16, I2C::Error> {
    let mut buf = [0u8; 2];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(((buf[0] as u16) << 8) | buf[1] as u16)
}

fn read_reg_signed<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<i16, I2C::Error> {
    Ok(read_reg(i2c, addr, reg)? as i16)
}

/// INA3221 minimal driver — bus voltage, shunt voltage, current, and power for three channels.
///
/// The chip's power-on default (all three channels on, continuous shunt+bus) is
/// used without modification. The constructor accepts either a single shunt
/// resistance applied to all three channels, or a 3-element slice for per-channel values.
pub struct Ina3221Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    r_shunt: [f32; 3],
}

impl<I2C: I2c> Ina3221Minimal<I2C> {
    /// Create a new `Ina3221Minimal`.
    ///
    /// # Arguments
    /// * `i2c`     — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`    — 7-bit device address (typically `0x40`–`0x43`).
    /// * `r_shunt` — Single shunt resistor value in ohms, applied to all three channels.
    pub fn new(i2c: I2C, addr: u8, r_shunt: f32) -> Self {
        Self {
            i2c,
            addr,
            r_shunt: [r_shunt, r_shunt, r_shunt],
        }
    }

    /// Create a new `Ina3221Minimal` with per-channel shunt resistances.
    ///
    /// # Arguments
    /// * `i2c`     — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`    — 7-bit device address (typically `0x40`–`0x43`).
    /// * `r_shunt` — 3-element slice of shunt resistor values in ohms, one per channel.
    pub fn with_r_shunt_per_channel(i2c: I2C, addr: u8, r_shunt: &[f32; 3]) -> Self {
        Self {
            i2c,
            addr,
            r_shunt: *r_shunt,
        }
    }

    /// Read bus voltage for a channel.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    ///
    /// Returns voltage in volts. LSB = 8 mV. The register stores a 12-bit
    /// left-aligned value; this method right-shifts by 3 before scaling.
    pub fn voltage(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        let ch = channel_valid(channel);
        let raw = read_reg(&mut self.i2c, self.addr, BUS_REGS[ch as usize - 1])?;
        Ok((raw >> 3) as f32 * 8e-3)
    }

    /// Read differential shunt voltage for a channel.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    ///
    /// Returns voltage in volts, signed. LSB = 40 µV. The register stores a 13-bit
    /// signed left-aligned value; multiplying the raw signed count by 5e-6 gives
    /// the correct result without an explicit right-shift.
    pub fn shunt_voltage(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        let ch = channel_valid(channel);
        let raw = read_reg_signed(&mut self.i2c, self.addr, SHUNT_REGS[ch as usize - 1])?;
        Ok(raw as f32 * 5e-6)
    }

    /// Read calculated current through the shunt for a channel.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    ///
    /// Returns current in amperes, computed as `shunt_voltage / r_shunt[channel]`.
    pub fn current(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        let ch = channel_valid(channel);
        let sv = self.shunt_voltage(ch)?;
        Ok(sv / self.r_shunt[ch as usize - 1])
    }

    /// Read calculated power for a channel.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    ///
    /// Returns power in watts, computed as `voltage * current`.
    pub fn power(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        let ch = channel_valid(channel);
        Ok(self.voltage(ch)? * self.current(ch)?)
    }
}

/// INA3221 full driver — extends [`Ina3221Minimal`] with configuration and alert support.
///
/// Adds Configuration Register programming, channel enables, conversion-ready,
/// per-channel critical and warning alerts, shunt-voltage summation, power-valid
/// monitoring, reset, and shutdown/wake.
pub struct Ina3221Full<I2C> {
    inner: Ina3221Minimal<I2C>,
    mode: u8,
}

impl<I2C: I2c> Ina3221Full<I2C> {
    /// Create a new `Ina3221Full`.
    ///
    /// # Arguments
    /// * `i2c`     — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`    — 7-bit device address (typically `0x40`–`0x43`).
    /// * `r_shunt` — Single shunt resistor value in ohms, applied to all three channels.
    pub fn new(i2c: I2C, addr: u8, r_shunt: f32) -> Self {
        Self {
            inner: Ina3221Minimal::new(i2c, addr, r_shunt),
            mode: 0x07,
        }
    }

    /// Create a new `Ina3221Full` with per-channel shunt resistances.
    ///
    /// # Arguments
    /// * `i2c`     — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr`    — 7-bit device address (typically `0x40`–`0x43`).
    /// * `r_shunt` — 3-element slice of shunt resistor values in ohms, one per channel.
    pub fn with_r_shunt_per_channel(i2c: I2C, addr: u8, r_shunt: &[f32; 3]) -> Self {
        Self {
            inner: Ina3221Minimal::with_r_shunt_per_channel(i2c, addr, r_shunt),
            mode: 0x07,
        }
    }

    /// Read bus voltage. Delegates to the inner [`Ina3221Minimal`].
    pub fn voltage(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        self.inner.voltage(channel)
    }

    /// Read shunt voltage. Delegates to the inner [`Ina3221Minimal`].
    pub fn shunt_voltage(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        self.inner.shunt_voltage(channel)
    }

    /// Read current. Delegates to the inner [`Ina3221Minimal`].
    pub fn current(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        self.inner.current(channel)
    }

    /// Read power. Delegates to the inner [`Ina3221Minimal`].
    pub fn power(&mut self, channel: u8) -> Result<f32, I2C::Error> {
        self.inner.power(channel)
    }

    /// Write the Configuration Register.
    ///
    /// Preserves channel-enable bits (CH1en, CH2en, CH3en).
    ///
    /// # Arguments
    /// * `avg`     — Averaging count selector 0–7 (0=1 sample, 7=1024 samples).
    /// * `vbus_ct` — Bus voltage conversion time selector 0–7 (default 4=1.1 ms).
    /// * `vsh_ct`  — Shunt voltage conversion time selector 0–7 (default 4=1.1 ms).
    /// * `mode`    — Operating mode (default 7=shunt+bus continuous).
    pub fn configure(&mut self, avg: u8, vbus_ct: u8, vsh_ct: u8, mode: u8) -> Result<(), I2C::Error> {
        let cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        let config = ((avg as u16 & 0x07) << 9)
            | ((vbus_ct as u16 & 0x07) << 6)
            | ((vsh_ct as u16 & 0x07) << 3)
            | (mode as u16 & 0x07)
            | (cfg & 0x7000);
        self.mode = mode & 0x07;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, config)
    }

    /// Enable or disable a channel.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    /// * `enabled`  — `true` to enable, `false` to disable.
    pub fn enable_channel(&mut self, channel: u8, enabled: bool) -> Result<(), I2C::Error> {
        let ch = channel_valid(channel);
        let cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        let bit = 14 - (ch as u8 - 1);
        let cfg = if enabled {
            cfg | (1u << bit)
        } else {
            cfg & !(1u << bit)
        };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg)
    }

    /// Read whether a channel is enabled.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    ///
    /// Returns `true` if the channel is enabled.
    pub fn channel_enabled(&mut self, channel: u8) -> Result<bool, I2C::Error> {
        let ch = channel_valid(channel);
        let cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        let bit = 14 - (ch as u8 - 1);
        Ok((cfg & (1u << bit)) != 0)
    }

    /// Read the Conversion Ready Flag (CVRF).
    ///
    /// Returns `true` if a conversion completed.
    pub fn conversion_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN)? & CVRF != 0)
    }

    /// Set the critical-alert limit for a channel.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    /// * `limit_v` — Voltage limit in volts.
    /// * `latch`   — If `true`, use latched mode (default `false`).
    pub fn set_critical_alert(&mut self, channel: u8, limit_v: f32, latch: bool) -> Result<(), I2C::Error> {
        let ch = channel_valid(channel);
        let raw = ((limit_v / 40e-6) as i32 << 3) as u16 & 0xFFF8;
        write_reg(&mut self.inner.i2c, self.inner.addr, CRIT_REGS[ch as usize - 1], raw)?;
        let cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN)?;
        let cfg = if latch { cfg | 0x0400 } else { cfg & !0x0400 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN, cfg)
    }

    /// Set the warning-alert limit for a channel.
    ///
    /// # Arguments
    /// * `channel` — Channel number 1, 2, or 3.
    /// * `limit_v` — Voltage limit in volts.
    /// * `latch`   — If `true`, use latched mode (default `false`).
    pub fn set_warning_alert(&mut self, channel: u8, limit_v: f32, latch: bool) -> Result<(), I2C::Error> {
        let ch = channel_valid(channel);
        let raw = ((limit_v / 40e-6) as i32 << 3) as u16 & 0xFFF8;
        write_reg(&mut self.inner.i2c, self.inner.addr, WARN_REGS[ch as usize - 1], raw)?;
        let cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN)?;
        let cfg = if latch { cfg | 0x0800 } else { cfg & !0x0800 };
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN, cfg)
    }

    /// Read the Mask/Enable Register.
    ///
    /// Reading this register clears the latched alert flags (CF1/CF2/CF3,
    /// WF1/WF2/WF3, SF) when latch mode is enabled.
    ///
    /// Returns the raw 16-bit Mask/Enable register value.
    pub fn alert_flags(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN)
    }

    /// Configure the shunt-voltage summation function.
    ///
    /// # Arguments
    /// * `channels` — Slice of channel numbers to sum (e.g. `[1, 2, 3]`).
    /// * `limit_v`  — Shunt-voltage sum limit in volts.
    pub fn set_summation_channels(&mut self, channels: &[u8], limit_v: f32) -> Result<(), I2C::Error> {
        let mut cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN)? & !0xE000;
        for &ch in channels {
            let _ = channel_valid(ch);
            cfg |= 1u << (15 - (ch - 1));
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN, cfg)?;
        let raw = ((limit_v / 40e-6) as i32 << 1) as u16 & 0xFFFE;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_SUM_LIMIT, raw)
    }

    /// Read the shunt-voltage sum.
    ///
    /// Returns the sum of selected channels' shunt voltages in volts.
    pub fn summation_value(&mut self) -> Result<f32, I2C::Error> {
        let raw = read_reg_signed(&mut self.inner.i2c, self.inner.addr, REG_SUM)?;
        Ok(raw as f32 * 5e-6)
    }

    /// Set the Power-Valid upper and lower voltage limits.
    ///
    /// # Arguments
    /// * `upper_v` — Upper bus voltage limit in volts.
    /// * `lower_v` — Lower bus voltage limit in volts.
    pub fn set_power_valid_limits(&mut self, upper_v: f32, lower_v: f32) -> Result<(), I2C::Error> {
        let raw_upper = ((upper_v / 8e-3) as i32 << 3) as u16 & 0xFFF8;
        let raw_lower = ((lower_v / 8e-3) as i32 << 3) as u16 & 0xFFF8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PV_UPPER, raw_upper)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_PV_LOWER, raw_lower)
    }

    /// Read the Power-Valid flag (PVF).
    ///
    /// Returns `true` if all enabled bus voltages are within the PV limits.
    pub fn power_valid(&mut self) -> Result<bool, I2C::Error> {
        Ok(read_reg(&mut self.inner.i2c, self.inner.addr, REG_MASK_EN)? & PVF != 0)
    }

    /// Enter power-down mode and save the current mode for [`wake`](Self::wake).
    pub fn shutdown(&mut self) -> Result<(), I2C::Error> {
        let cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        self.mode = (cfg & 0x07) as u8;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, cfg & 0xFFF8)
    }

    /// Restore the operating mode saved by [`shutdown`](Self::shutdown).
    pub fn wake(&mut self) -> Result<(), I2C::Error> {
        let cfg = read_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, (cfg & 0xFFF8) | self.mode as u16)
    }

    /// Reset all registers to power-on defaults.
    pub fn reset(&mut self) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONFIG, 0x8000)
    }

    /// Read the Manufacturer ID register. Expect `0x5449` (Texas Instruments).
    pub fn manufacturer_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_MFR_ID)
    }

    /// Read the Die ID register. Expect `0x3220`.
    pub fn die_id(&mut self) -> Result<u16, I2C::Error> {
        read_reg(&mut self.inner.i2c, self.inner.addr, REG_DIE_ID)
    }
}