//! APDS-9250 — digital RGB, IR and ambient light sensor (Broadcom).
//!
//! Four simultaneously integrating photodiode channels (red, green, blue, IR;
//! the green channel doubles as the ambient-light channel) with a 13- to 20-bit
//! ADC, five gains (×1, ×3, ×6, ×9, ×18) and a measurement rate of 25 ms to 2 s.
//! Results are unsigned counts in 3-byte registers (LSB first). A threshold or
//! variation interrupt on one channel, with a persistence of 1–16 events, drives
//! the open-drain INT pin. The 7-bit I²C address is fixed at `0x52`.
//!
//! The driver uses register access (1-byte address, auto-increment on block
//! transfers). Reading `MAIN_STATUS` clears its flags, so the driver keeps the
//! interrupt and power-on bits it has seen until
//! [`Apds9250Full::poll_interrupt`] / [`Apds9250Full::status`] hands them out.
//!
//! A delay provider is needed for [`Apds9250Minimal::init`] (support-block
//! settling) and [`Apds9250Minimal::read`] (waiting for a new result). The
//! interrupt is exposed as [`Apds9250Full::poll_interrupt`] only: wire your own
//! GPIO to INT and call it from the falling-edge handler.

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// Fixed 7-bit I²C address.
pub const APDS9250_I2C_ADDRESS: u8 = 0x52;
/// Interrupt source: selected channel outside the threshold window.
pub const APDS9250_SOURCE_THRESHOLD: u8 = 0x01;
/// Interrupt source: selected channel changed by more than the variance threshold.
pub const APDS9250_SOURCE_VARIATION: u8 = 0x02;
/// `MAIN_STATUS` bit 5: power-up event occurred.
pub const APDS9250_STATUS_POWER_ON: u8 = 0x20;
/// `MAIN_STATUS` bit 4: interrupt condition fulfilled.
pub const APDS9250_STATUS_INTERRUPT: u8 = 0x10;
/// `MAIN_STATUS` bit 3: new data available.
pub const APDS9250_STATUS_DATA_READY: u8 = 0x08;

const REG_MAIN_CTRL: u8 = 0x00;
const REG_LS_MEAS_RATE: u8 = 0x04;
const REG_LS_GAIN: u8 = 0x05;
const REG_PART_ID: u8 = 0x06;
const REG_MAIN_STATUS: u8 = 0x07;
const REG_DATA_IR: u8 = 0x0A;
const REG_DATA_GREEN: u8 = 0x0D;
const REG_DATA_BLUE: u8 = 0x10;
const REG_DATA_RED: u8 = 0x13;
const REG_INT_CFG: u8 = 0x19;
const REG_INT_PERSISTENCE: u8 = 0x1A;
const REG_THRES_UP: u8 = 0x21;
const REG_THRES_LOW: u8 = 0x24;
const REG_THRES_VAR: u8 = 0x27;

const CTRL_SW_RESET: u8 = 0x10;
const CTRL_CS_MODE: u8 = 0x04;
const CTRL_LS_EN: u8 = 0x02;
const INT_EN: u8 = 0x04;
const INT_VAR_MODE: u8 = 0x08;
const INT_SEL_MASK: u8 = 0x30;

const RESOLUTION_BITS: [u8; 6] = [20, 19, 18, 17, 16, 13];
const INTEGRATION_S: [f32; 6] = [0.4, 0.2, 0.1, 0.05, 0.025, 0.003125];
const RATE_MS: [u16; 8] = [25, 50, 100, 200, 500, 1000, 2000, 2000];
const GAINS: [u8; 5] = [1, 3, 6, 9, 18];
const POLL_STEP_MS: u32 = 5;

fn count(b: &[u8]) -> u32 {
    b[0] as u32 | ((b[1] as u32) << 8) | (((b[2] & 0x0F) as u32) << 16)
}

/// The four channel counts of one conversion (unsigned, up to 20 bit).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Apds9250Counts {
    /// Red count.
    pub red: u32,
    /// Green / ambient-light count.
    pub green: u32,
    /// Blue count.
    pub blue: u32,
    /// Infrared count.
    pub ir: u32,
}

/// Interrupt channel selection (`LS_INT_SEL`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Apds9250Channel {
    /// Infrared channel.
    Ir = 0,
    /// Green / ambient-light channel.
    Green = 1,
    /// Red channel.
    Red = 2,
    /// Blue channel.
    Blue = 3,
}

/// Errors returned by the driver.
#[derive(Debug)]
pub enum Apds9250Error<E> {
    /// I²C bus error.
    I2c(E),
    /// `PART_ID` does not identify an APDS-9250.
    NotFound(u8),
    /// An argument is outside the supported values; nothing was written.
    InvalidArgument,
}

impl<E> From<E> for Apds9250Error<E> {
    fn from(e: E) -> Self {
        Apds9250Error::I2c(e)
    }
}

/// APDS-9250 — minimal interface: read the four channel counts.
pub struct Apds9250Minimal<I2C> {
    i2c: I2C,
    addr: u8,
    pending: u8,
    integration_s: f32,
}

impl<I2C: I2c> Apds9250Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once
    /// after power-up). `addr` is [`APDS9250_I2C_ADDRESS`].
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr, pending: 0, integration_s: 0.1 }
    }

    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    pub(crate) fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])
    }

    pub(crate) fn update_reg(&mut self, reg: u8, set: u8, clear: u8) -> Result<(), I2C::Error> {
        let value = self.read_reg(reg)?;
        self.write_reg(reg, (value & !clear) | set)
    }

    pub(crate) fn read_status(&mut self) -> Result<u8, I2C::Error> {
        let value = self.read_reg(REG_MAIN_STATUS)?;
        self.pending |= value & (APDS9250_STATUS_POWER_ON | APDS9250_STATUS_INTERRUPT);
        Ok(value)
    }

    pub(crate) fn read_block(&mut self) -> Result<Apds9250Counts, I2C::Error> {
        let mut raw = [0u8; 12];
        register::read_register(&mut self.i2c, self.addr, REG_DATA_IR.into(), 1, &mut raw)?;
        Ok(Apds9250Counts {
            ir: count(&raw[0..3]),
            green: count(&raw[3..6]),
            blue: count(&raw[6..9]),
            red: count(&raw[9..12]),
        })
    }

    pub(crate) fn read_channel(&mut self, reg: u8) -> Result<u32, I2C::Error> {
        let mut raw = [0u8; 3];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut raw)?;
        Ok(count(&raw))
    }

    /// Start the sensor: read `PART_ID` (the upper nibble must be `0xB`), write
    /// the defaults (18 bit, 100 ms, gain ×3, interrupt off), set `LS_EN` and
    /// `CS_MODE` and wait 5 ms for the support blocks to settle.
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Apds9250Error<I2C::Error>> {
        let part = self.read_reg(REG_PART_ID)?;
        if part & 0xF0 != 0xB0 {
            return Err(Apds9250Error::NotFound(part));
        }
        self.write_reg(REG_LS_MEAS_RATE, 0x22)?;
        self.write_reg(REG_LS_GAIN, 0x01)?;
        self.write_reg(REG_INT_CFG, 0x10)?;
        self.write_reg(REG_MAIN_CTRL, CTRL_LS_EN | CTRL_CS_MODE)?;
        self.integration_s = 0.1;
        delay.delay_ms(5);
        Ok(())
    }

    /// Read all four channels from one conversion. Waits for a new result (at
    /// most one integration time plus 50 ms), then block-reads `0x0A`–`0x15`.
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Apds9250Counts, I2C::Error> {
        let limit_ms = ((self.integration_s + 0.05) * 1000.0) as u32;
        let mut waited = 0;
        while waited <= limit_ms {
            if self.read_status()? & APDS9250_STATUS_DATA_READY != 0 {
                break;
            }
            delay.delay_ms(POLL_STEP_MS);
            waited += POLL_STEP_MS;
        }
        self.read_block()
    }
}

/// APDS-9250 — full interface: single-channel reads, mode, resolution, rate and
/// gain configuration, thresholds, variation and persistence, interrupt polling.
pub struct Apds9250Full<I2C> {
    inner: Apds9250Minimal<I2C>,
}

impl<I2C: I2c> Apds9250Full<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: Apds9250Minimal::new(i2c, addr) }
    }

    /// Start the sensor. See [`Apds9250Minimal::init`].
    pub fn init<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), Apds9250Error<I2C::Error>> {
        self.inner.init(delay)
    }

    /// Read all four channels. See [`Apds9250Minimal::read`].
    pub fn read<D: DelayNs>(&mut self, delay: &mut D) -> Result<Apds9250Counts, I2C::Error> {
        self.inner.read(delay)
    }

    /// Read the red channel (3-byte read).
    pub fn read_red(&mut self) -> Result<u32, I2C::Error> {
        self.inner.read_channel(REG_DATA_RED)
    }

    /// Read the green / ambient-light channel.
    pub fn read_green(&mut self) -> Result<u32, I2C::Error> {
        self.inner.read_channel(REG_DATA_GREEN)
    }

    /// Read the blue channel.
    pub fn read_blue(&mut self) -> Result<u32, I2C::Error> {
        self.inner.read_channel(REG_DATA_BLUE)
    }

    /// Read the infrared channel.
    pub fn read_ir(&mut self) -> Result<u32, I2C::Error> {
        self.inner.read_channel(REG_DATA_IR)
    }

    /// Block-read all four channels without waiting (call after [`Self::data_ready`]).
    pub fn read_channels(&mut self) -> Result<Apds9250Counts, I2C::Error> {
        self.inner.read_block()
    }

    /// Report whether a new result is available. Reads `MAIN_STATUS`, which
    /// clears its flags; the interrupt and power-on bits are kept for
    /// [`Self::poll_interrupt`] and [`Self::status`].
    pub fn data_ready(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_status()? & APDS9250_STATUS_DATA_READY != 0)
    }

    /// Select colour-sensing (`true`: red, green, blue, IR) or ALS/IR mode
    /// (`false`: green and IR only).
    pub fn set_mode(&mut self, colour: bool) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_MAIN_CTRL, if colour { CTRL_CS_MODE } else { 0 }, CTRL_CS_MODE)
    }

    /// Activate the light sensor (`LS_EN` = 1).
    pub fn enable(&mut self) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_MAIN_CTRL, CTRL_LS_EN, 0)
    }

    /// Put the light sensor in standby (`LS_EN` = 0).
    pub fn disable(&mut self) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_MAIN_CTRL, 0, CTRL_LS_EN)
    }

    /// Set the ADC resolution (13, 16, 17, 18, 19 or 20 bit); restarts the
    /// measurement. Any other value returns [`Apds9250Error::InvalidArgument`].
    pub fn set_resolution(&mut self, bits: u8) -> Result<(), Apds9250Error<I2C::Error>> {
        let code = RESOLUTION_BITS.iter().position(|&b| b == bits).ok_or(Apds9250Error::InvalidArgument)?;
        self.inner.update_reg(REG_LS_MEAS_RATE, (code as u8) << 4, 0x70)?;
        self.inner.integration_s = INTEGRATION_S[code];
        Ok(())
    }

    /// Set the measurement rate in ms (25, 50, 100, 200, 500, 1000 or 2000).
    pub fn set_measurement_rate(&mut self, milliseconds: u16) -> Result<(), Apds9250Error<I2C::Error>> {
        let code = RATE_MS[..7].iter().position(|&m| m == milliseconds).ok_or(Apds9250Error::InvalidArgument)?;
        self.inner.update_reg(REG_LS_MEAS_RATE, code as u8, 0x07)?;
        Ok(())
    }

    /// Set the analog gain (1, 3, 6, 9 or 18) for all channels; restarts the measurement.
    pub fn set_gain(&mut self, gain: u8) -> Result<(), Apds9250Error<I2C::Error>> {
        let code = GAINS.iter().position(|&g| g == gain).ok_or(Apds9250Error::InvalidArgument)?;
        self.inner.write_reg(REG_LS_GAIN, code as u8)?;
        Ok(())
    }

    /// Read the ADC resolution in bits.
    pub fn get_resolution(&mut self) -> Result<u8, I2C::Error> {
        let code = ((self.inner.read_reg(REG_LS_MEAS_RATE)? >> 4) & 0x07) as usize;
        Ok(if code < 6 { RESOLUTION_BITS[code] } else { 18 })
    }

    /// Read the measurement rate in ms.
    pub fn get_measurement_rate(&mut self) -> Result<u16, I2C::Error> {
        Ok(RATE_MS[(self.inner.read_reg(REG_LS_MEAS_RATE)? & 0x07) as usize])
    }

    /// Read the analog gain (1, 3, 6, 9 or 18).
    pub fn get_gain(&mut self) -> Result<u8, I2C::Error> {
        let code = (self.inner.read_reg(REG_LS_GAIN)? & 0x07) as usize;
        Ok(if code < 5 { GAINS[code] } else { 18 })
    }

    /// Integration time of the current resolution in seconds (0.003125 … 0.4).
    pub fn integration_time(&mut self) -> Result<f32, I2C::Error> {
        let code = ((self.inner.read_reg(REG_LS_MEAS_RATE)? >> 4) & 0x07) as usize;
        Ok(if code < 6 { INTEGRATION_S[code] } else { 0.1 })
    }

    /// Convert a count to counts per gain-second: `counts / (gain · integration_time)`.
    pub fn normalized(&mut self, counts: u32) -> Result<f32, I2C::Error> {
        let gain = self.get_gain()? as f32;
        let time = self.integration_time()?;
        Ok(counts as f32 / (gain * time))
    }

    /// Read `PART_ID` as `(part, revision)` — the two nibbles (`0xB`, `0x5`).
    pub fn part_id(&mut self) -> Result<(u8, u8), I2C::Error> {
        let value = self.inner.read_reg(REG_PART_ID)?;
        Ok((value >> 4, value & 0x0F))
    }

    /// Read `MAIN_STATUS` (clearing the flags on the chip), with the interrupt
    /// and power-on bits seen since the last call merged in.
    pub fn status(&mut self) -> Result<u8, I2C::Error> {
        let value = self.inner.read_status()? | self.inner.pending;
        self.inner.pending = 0;
        Ok(value)
    }

    /// Trigger a software reset (all registers to defaults, sensor in standby).
    pub fn soft_reset<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_MAIN_CTRL, CTRL_SW_RESET)?;
        delay.delay_ms(10);
        self.inner.pending = 0;
        self.inner.integration_s = 0.1;
        Ok(())
    }

    /// Set the 20-bit threshold window (`low` ≤ `high` ≤ 1048575).
    pub fn set_thresholds(&mut self, low: u32, high: u32) -> Result<(), Apds9250Error<I2C::Error>> {
        if low > 0xFFFFF || high > 0xFFFFF || low > high {
            return Err(Apds9250Error::InvalidArgument);
        }
        let lo = [low as u8, (low >> 8) as u8, ((low >> 16) & 0x0F) as u8];
        let hi = [high as u8, (high >> 8) as u8, ((high >> 16) & 0x0F) as u8];
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_THRES_LOW.into(), 1, &lo)?;
        register::write_register(&mut self.inner.i2c, self.inner.addr, REG_THRES_UP.into(), 1, &hi)?;
        Ok(())
    }

    /// Read the threshold window as `(low, high)`.
    pub fn get_thresholds(&mut self) -> Result<(u32, u32), I2C::Error> {
        let mut lo = [0u8; 3];
        let mut hi = [0u8; 3];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_THRES_LOW.into(), 1, &mut lo)?;
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_THRES_UP.into(), 1, &mut hi)?;
        Ok((count(&lo), count(&hi)))
    }

    /// Select the channel the interrupt monitors.
    pub fn set_interrupt_channel(&mut self, channel: Apds9250Channel) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_INT_CFG, (channel as u8) << 4, INT_SEL_MASK)
    }

    /// Set the number of consecutive events (1–16) before the interrupt asserts.
    pub fn set_persistence(&mut self, count: u8) -> Result<(), Apds9250Error<I2C::Error>> {
        if !(1..=16).contains(&count) {
            return Err(Apds9250Error::InvalidArgument);
        }
        self.inner.write_reg(REG_INT_PERSISTENCE, (count - 1) << 4)?;
        Ok(())
    }

    /// Set the variation-interrupt threshold in counts (8, 16, 32, … 1024).
    pub fn set_variance(&mut self, counts: u16) -> Result<(), Apds9250Error<I2C::Error>> {
        let n = (0..8u8).find(|&n| counts == (8u16 << n)).ok_or(Apds9250Error::InvalidArgument)?;
        self.inner.write_reg(REG_THRES_VAR, n)?;
        Ok(())
    }

    /// Select threshold ([`APDS9250_SOURCE_THRESHOLD`]) or variation
    /// ([`APDS9250_SOURCE_VARIATION`]) mode and enable the interrupt.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let mode = if source == APDS9250_SOURCE_VARIATION { INT_VAR_MODE } else { 0 };
        self.inner.update_reg(REG_INT_CFG, INT_EN | mode, INT_EN | INT_VAR_MODE)
    }

    /// Disable the interrupt (clears `LS_INT_EN`; both sources share the bit).
    pub fn disable_interrupt(&mut self) -> Result<(), I2C::Error> {
        self.inner.update_reg(REG_INT_CFG, 0, INT_EN)
    }

    /// Read `MAIN_STATUS` (clearing the flags) and return it; bit 4
    /// ([`APDS9250_STATUS_INTERRUPT`]) is set if the condition was fulfilled.
    /// Wire your own GPIO to INT and call this from its falling-edge handler.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        self.status()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = APDS9250_I2C_ADDRESS;

    fn w(reg: u8, v: u8) -> T {
        T::write(ADDR, vec![reg, v])
    }

    fn r(reg: u8, v: u8) -> T {
        T::write_read(ADDR, vec![reg], vec![v])
    }

    fn init_t() -> Vec<T> {
        vec![r(6, 0xB5), w(4, 0x22), w(5, 0x01), w(0x19, 0x10), w(0, 0x06)]
    }

    fn full(extra: Vec<T>) -> (I2cMock, Apds9250Full<I2cMock>) {
        let mut t = init_t();
        t.extend(extra);
        let i2c = I2cMock::new(&t);
        let mut chip = Apds9250Full::new(i2c.clone(), ADDR);
        chip.init(&mut NoopDelay::new()).unwrap();
        (i2c, chip)
    }

    #[test]
    fn init_writes_defaults() {
        let (mut i2c, _chip) = full(vec![]);
        i2c.done();
    }

    #[test]
    fn init_rejects_wrong_part() {
        let mut i2c = I2cMock::new(&[r(6, 0xC5)]);
        let mut chip = Apds9250Minimal::new(i2c.clone(), ADDR);
        assert!(matches!(chip.init(&mut NoopDelay::new()), Err(Apds9250Error::NotFound(0xC5))));
        i2c.done();
    }

    #[test]
    fn read_waits_for_data_and_decodes_channels() {
        let block = vec![
            0x01, 0x02, 0x03, // IR = 0x030201
            0x10, 0x00, 0x00, // green = 16
            0xFF, 0xFF, 0x0F, // blue = 0xFFFFF
            0x00, 0x00, 0xF4, // red: upper nibble ignored -> 0x40000
        ];
        let (mut i2c, mut chip) = full(vec![
            r(7, 0x00),
            r(7, 0x08),
            T::write_read(ADDR, vec![0x0A], block),
        ]);
        let c = chip.read(&mut NoopDelay::new()).unwrap();
        assert_eq!(c, Apds9250Counts { red: 0x40000, green: 16, blue: 0xFFFFF, ir: 0x030201 });
        i2c.done();
    }

    #[test]
    fn single_channels_use_their_registers() {
        let (mut i2c, mut chip) = full(vec![
            T::write_read(ADDR, vec![0x13], vec![0x01, 0x00, 0x00]),
            T::write_read(ADDR, vec![0x0D], vec![0x02, 0x00, 0x00]),
            T::write_read(ADDR, vec![0x10], vec![0x03, 0x00, 0x00]),
            T::write_read(ADDR, vec![0x0A], vec![0x04, 0x00, 0x00]),
        ]);
        assert_eq!(chip.read_red().unwrap(), 1);
        assert_eq!(chip.read_green().unwrap(), 2);
        assert_eq!(chip.read_blue().unwrap(), 3);
        assert_eq!(chip.read_ir().unwrap(), 4);
        i2c.done();
    }

    #[test]
    fn resolution_rate_gain_read_modify_write() {
        let (mut i2c, mut chip) = full(vec![
            r(4, 0x22), w(4, 0x02),    // 20 bit keeps rate 100 ms
            r(4, 0x02), w(4, 0x52),    // 13 bit
            r(4, 0x52), w(4, 0x56),    // rate 2000 ms (code 6) keeps resolution
            w(5, 0x04),                // gain 18
            r(4, 0x52),                // get_resolution
            r(5, 0x03),                // get_gain
        ]);
        chip.set_resolution(20).unwrap();
        chip.set_resolution(13).unwrap();
        chip.set_measurement_rate(2000).unwrap();
        chip.set_gain(18).unwrap();
        assert_eq!(chip.get_resolution().unwrap(), 13);
        assert_eq!(chip.get_gain().unwrap(), 9);
        i2c.done();
    }

    #[test]
    fn invalid_arguments_write_nothing() {
        let (mut i2c, mut chip) = full(vec![]);
        assert!(matches!(chip.set_gain(2), Err(Apds9250Error::InvalidArgument)));
        assert!(matches!(chip.set_resolution(12), Err(Apds9250Error::InvalidArgument)));
        assert!(matches!(chip.set_measurement_rate(30), Err(Apds9250Error::InvalidArgument)));
        assert!(matches!(chip.set_persistence(0), Err(Apds9250Error::InvalidArgument)));
        assert!(matches!(chip.set_persistence(17), Err(Apds9250Error::InvalidArgument)));
        assert!(matches!(chip.set_variance(12), Err(Apds9250Error::InvalidArgument)));
        assert!(matches!(chip.set_thresholds(5, 4), Err(Apds9250Error::InvalidArgument)));
        assert!(matches!(chip.set_thresholds(0, 0x100000), Err(Apds9250Error::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn mode_enable_disable_and_reset() {
        let (mut i2c, mut chip) = full(vec![
            r(0, 0x06), w(0, 0x02),    // ALS mode
            r(0, 0x02), w(0, 0x06),    // colour mode
            r(0, 0x06), w(0, 0x04),    // disable
            r(0, 0x04), w(0, 0x06),    // enable
            w(0, 0x10),                // soft reset
        ]);
        chip.set_mode(false).unwrap();
        chip.set_mode(true).unwrap();
        chip.disable().unwrap();
        chip.enable().unwrap();
        chip.soft_reset(&mut NoopDelay::new()).unwrap();
        i2c.done();
    }

    #[test]
    fn thresholds_encode_three_bytes() {
        let (mut i2c, mut chip) = full(vec![
            T::write(ADDR, vec![0x24, 0x45, 0x23, 0x01]),
            T::write(ADDR, vec![0x21, 0xDE, 0xBC, 0x0A]),
            T::write_read(ADDR, vec![0x24], vec![0x45, 0x23, 0x01]),
            T::write_read(ADDR, vec![0x21], vec![0xDE, 0xBC, 0x0A]),
        ]);
        chip.set_thresholds(0x12345, 0xABCDE).unwrap();
        assert_eq!(chip.get_thresholds().unwrap(), (0x12345, 0xABCDE));
        i2c.done();
    }

    #[test]
    fn interrupt_configuration() {
        let (mut i2c, mut chip) = full(vec![
            r(0x19, 0x10), w(0x19, 0x20),          // channel red
            r(0x19, 0x20), w(0x19, 0x2C),          // enable variation
            r(0x19, 0x2C), w(0x19, 0x24),          // enable threshold
            r(0x19, 0x24), w(0x19, 0x20),          // disable
            w(0x1A, 0xF0),                         // persistence 16
            w(0x27, 0x07),                         // variance 1024
        ]);
        chip.set_interrupt_channel(Apds9250Channel::Red).unwrap();
        chip.enable_interrupt(APDS9250_SOURCE_VARIATION).unwrap();
        chip.enable_interrupt(APDS9250_SOURCE_THRESHOLD).unwrap();
        chip.disable_interrupt().unwrap();
        chip.set_persistence(16).unwrap();
        chip.set_variance(1024).unwrap();
        i2c.done();
    }

    #[test]
    fn status_keeps_interrupt_bit_across_data_ready() {
        let (mut i2c, mut chip) = full(vec![r(7, 0x18), r(7, 0x00), r(7, 0x00), r(7, 0x20)]);
        assert!(chip.data_ready().unwrap());
        assert!(!chip.data_ready().unwrap());
        assert_eq!(chip.poll_interrupt().unwrap(), 0x10);
        assert_eq!(chip.status().unwrap(), 0x20);
        i2c.done();
    }

    #[test]
    fn part_id_and_normalized() {
        let (mut i2c, mut chip) = full(vec![r(6, 0xB5), r(5, 0x01), r(4, 0x22)]);
        assert_eq!(chip.part_id().unwrap(), (0xB, 0x5));
        assert!((chip.normalized(3000).unwrap() - 10000.0).abs() < 1.0);
        i2c.done();
    }
}
