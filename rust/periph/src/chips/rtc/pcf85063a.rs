//! PCF85063A — Low-power I²C real-time clock and calendar (NXP).
//!
//! Fixed I²C address `0x51`. Maintains seconds/minutes/hours/days/weekdays/
//! months/years in BCD registers (2000–2099) driven by a 32.768 kHz crystal.
//! Adds a five-field alarm (second, minute, hour, day, weekday), an 8-bit
//! countdown timer with four source clocks, minute / half-minute
//! interrupts, a programmable `CLKOUT` (separate pin from `INT`), a
//! clock-offset calibration register, a selectable oscillator load
//! capacitance and one free RAM byte. There is no battery switch-over: the
//! chip is fed from a single `VDD` supply.
//!
//! [`Pcf85063aMinimal::new`] forces 24-hour mode with the clock running
//! (`STOP`=0); it writes no time register and does not reset the chip. The
//! hour registers are always operated in 24-hour mode, and `weekday`
//! follows the datasheet's suggested assignment (`0`=Sunday … `6`=Saturday)
//! — see `specs/rtc/pcf85063a.md` for the full register map and rationale.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Pcf85063aFull::poll_interrupt`] (no callback
//! subscription — the caller wires it into an ISR or polling loop) plus
//! [`Pcf85063aFull::enable_interrupt`] / [`Pcf85063aFull::disable_interrupt`]
//! with the `SOURCE_*` constants.

use embedded_hal::i2c::I2c;

use crate::connection::register;

const REG_CONTROL_1: u8 = 0x00;
const REG_CONTROL_2: u8 = 0x01;
const REG_OFFSET: u8 = 0x02;
const REG_RAM: u8 = 0x03;
const REG_SECONDS: u8 = 0x04;
const REG_SECOND_ALARM: u8 = 0x0B;
const REG_TIMER_VALUE: u8 = 0x10;
const REG_TIMER_MODE: u8 = 0x11;

const C1_STOP: u8 = 0x20;
const C1_SR: u8 = 0x10;
const C1_CIE: u8 = 0x04;
const C1_12_24: u8 = 0x02;
const C1_CAP_SEL: u8 = 0x01;

const C2_AIE: u8 = 0x80;
const C2_AF: u8 = 0x40;
const C2_MI: u8 = 0x20;
const C2_HMI: u8 = 0x10;
const C2_TF: u8 = 0x08;
const C2_COF: u8 = 0x07;
const C2_FLAGS: u8 = 0x48; // AF|TF: write 0 clears, 1 keeps

const TM_TCF_MASK: u8 = 0x18;
const TM_TE: u8 = 0x04;
const TM_TIE: u8 = 0x02;
const TM_TI_TP: u8 = 0x01;

const SECONDS_OS: u8 = 0x80;

/// Fixed 7-bit I²C address.
pub const I2C_ADDRESS: u8 = 0x51;

/// Interrupt source: all enabled alarm fields matched (`AF`).
pub const SOURCE_ALARM: u8 = 0x01;
/// Interrupt source: countdown timer expired (`TF`).
pub const SOURCE_TIMER: u8 = 0x02;
/// Interrupt source: minute interrupt (enable only; reported as [`SOURCE_TIMER`]).
pub const SOURCE_MINUTE: u8 = 0x04;
/// Interrupt source: half-minute interrupt (enable only; reported as [`SOURCE_TIMER`]).
pub const SOURCE_HALF_MINUTE: u8 = 0x08;
/// Interrupt source: offset-correction pulse (enable only, no flag).
pub const SOURCE_CORRECTION: u8 = 0x10;

/// Error returned by [`Pcf85063aMinimal::set_datetime`].
#[derive(Debug, PartialEq, Eq)]
pub enum Pcf85063aError<E> {
    /// The underlying I²C transfer failed.
    Bus(E),
    /// `year` is outside 2000–2099.
    InvalidArgument,
}

impl<E> From<E> for Pcf85063aError<E> {
    fn from(e: E) -> Self {
        Pcf85063aError::Bus(e)
    }
}

/// Calendar clock reading/target: BCD-decoded, 24-hour, Sunday=0 weekday.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DateTime {
    /// Full year, `2000`-`2099`.
    pub year: u16,
    /// Month, `1`-`12`.
    pub month: u8,
    /// Day of month, `1`-`31`.
    pub day: u8,
    /// Day of week, `0`=Sunday … `6`=Saturday.
    pub weekday: u8,
    /// Hour, `0`-`23`.
    pub hour: u8,
    /// Minute, `0`-`59`.
    pub minute: u8,
    /// Second, `0`-`59`.
    pub second: u8,
}

/// Alarm configuration. A `None` field is disabled (ignored in the match);
/// the alarm fires when every enabled field matches.
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct Alarm {
    /// Second, `0`-`59`.
    pub second: Option<u8>,
    /// Minute, `0`-`59`.
    pub minute: Option<u8>,
    /// Hour, `0`-`23`.
    pub hour: Option<u8>,
    /// Day of month, `1`-`31`.
    pub day: Option<u8>,
    /// Day of week, `0`=Sunday … `6`=Saturday.
    pub weekday: Option<u8>,
}

/// Timer source clock (`TCF` encoding).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum SourceClock {
    /// 4.096 kHz — 244 µs … 62.256 ms.
    Hz4096,
    /// 64 Hz — 15.625 ms … 3.984 s.
    Hz64,
    /// 1 Hz — 1 s … 255 s.
    Hz1,
    /// 1/60 Hz — 1 min … 4 h 15 min.
    Hz1_60,
}

impl SourceClock {
    fn bits(self) -> u8 {
        match self {
            SourceClock::Hz4096 => 0x00,
            SourceClock::Hz64 => 0x01,
            SourceClock::Hz1 => 0x02,
            SourceClock::Hz1_60 => 0x03,
        }
    }
}

/// Offset correction mode.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum OffsetMode {
    /// Correction once every two hours, 4.34 ppm per LSB (default).
    Normal,
    /// Correction every four minutes, 4.069 ppm per LSB, higher power.
    Course,
}

/// Minute / half-minute interrupt selection.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum MinuteInterrupt {
    /// Both disabled.
    Off,
    /// Interrupt once per minute (`MI`).
    Minute,
    /// Interrupt every 30 s (`HMI`).
    HalfMinute,
}

/// Oscillator load capacitance (`CAP_SEL`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum OscillatorLoad {
    /// 7 pF (power-on default).
    Pf7,
    /// 12.5 pF.
    Pf12_5,
}

fn bcd_to_bin(b: u8) -> u8 {
    (b >> 4) * 10 + (b & 0x0F)
}

fn bin_to_bcd(v: u8) -> u8 {
    ((v / 10) << 4) | (v % 10)
}

/// PCF85063A minimal driver — calendar clock in 24-hour mode, no
/// configuration required beyond the connection.
pub struct Pcf85063aMinimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Pcf85063aMinimal<I2C> {
    /// Create a new `Pcf85063aMinimal`: confirm the device answers (plain
    /// `Control_1` read — no identity register) and force 24-hour mode with
    /// `STOP`=0 (read-modify-write, other bits preserved).
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit I²C address (fixed [`I2C_ADDRESS`]).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut i2c, addr, REG_CONTROL_1.into(), 1, &mut buf)?;
        let mut dev = Self { i2c, addr };
        let ctrl1 = dev.read_control_1()?;
        dev.write_reg(REG_CONTROL_1, ctrl1 & !(C1_STOP | C1_12_24))?;
        Ok(dev)
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])
    }

    /// `Control_1` with `SR` masked, safe for read-modify-write.
    fn read_control_1(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.read_reg(REG_CONTROL_1)? & !C1_SR)
    }

    /// Read the current calendar clock (one 7-byte burst).
    pub fn get_datetime(&mut self) -> Result<DateTime, I2C::Error> {
        let mut buf = [0u8; 7];
        register::read_register(&mut self.i2c, self.addr, REG_SECONDS.into(), 1, &mut buf)?;
        Ok(DateTime {
            second: bcd_to_bin(buf[0] & 0x7F),
            minute: bcd_to_bin(buf[1] & 0x7F),
            hour: bcd_to_bin(buf[2] & 0x3F),
            day: bcd_to_bin(buf[3] & 0x3F),
            weekday: buf[4] & 0x07,
            month: bcd_to_bin(buf[5] & 0x1F),
            year: 2000 + bcd_to_bin(buf[6]) as u16,
        })
    }

    /// Set the calendar clock using the `STOP`-bit precision start: freeze
    /// the divider chain (`STOP`=1), write all seven time/date registers in
    /// one burst, then release `STOP`. Forces 24-hour mode and clears the
    /// `OS` flag — the time is now known-good.
    ///
    /// Returns [`Pcf85063aError::InvalidArgument`] (writing nothing) if
    /// `dt.year` is outside 2000–2099.
    pub fn set_datetime(&mut self, dt: DateTime) -> Result<(), Pcf85063aError<I2C::Error>> {
        if !(2000..=2099).contains(&dt.year) {
            return Err(Pcf85063aError::InvalidArgument);
        }
        let ctrl1 = self.read_control_1()? & !C1_12_24;
        self.write_reg(REG_CONTROL_1, ctrl1 | C1_STOP)?;
        let buf = [
            REG_SECONDS,
            bin_to_bcd(dt.second) & 0x7F,
            bin_to_bcd(dt.minute),
            bin_to_bcd(dt.hour) & 0x3F,
            bin_to_bcd(dt.day),
            dt.weekday & 0x07,
            bin_to_bcd(dt.month),
            bin_to_bcd((dt.year - 2000) as u8),
        ];
        register::write_register(&mut self.i2c, self.addr, buf[0].into(), 1, &buf[1..])?;
        self.write_reg(REG_CONTROL_1, ctrl1 & !C1_STOP)?;
        Ok(())
    }
}

/// PCF85063A full driver — extends minimal with the five-field alarm, the
/// countdown timer, minute / half-minute interrupts, `CLKOUT`, offset
/// calibration, oscillator-load selection, oscillator-stop detection, clock
/// stop/start, the free RAM byte, software reset, and the interrupt API.
///
/// The `INT` pin is open-drain and active-low; `CLKOUT` is a separate pin.
pub struct Pcf85063aFull<I2C> {
    inner: Pcf85063aMinimal<I2C>,
}

impl<I2C: I2c> Pcf85063aFull<I2C> {
    /// Create a new `Pcf85063aFull`. See [`Pcf85063aMinimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Pcf85063aMinimal::new(i2c, addr)? })
    }

    /// Read the current calendar clock. Delegates to [`Pcf85063aMinimal::get_datetime`].
    pub fn get_datetime(&mut self) -> Result<DateTime, I2C::Error> {
        self.inner.get_datetime()
    }

    /// Set the calendar clock. Delegates to [`Pcf85063aMinimal::set_datetime`].
    pub fn set_datetime(&mut self, dt: DateTime) -> Result<(), Pcf85063aError<I2C::Error>> {
        self.inner.set_datetime(dt)
    }

    fn update_control_1(&mut self, clear_mask: u8, set_bits: u8) -> Result<(), I2C::Error> {
        let ctrl1 = self.inner.read_control_1()?;
        self.inner.write_reg(REG_CONTROL_1, (ctrl1 & !clear_mask) | set_bits)
    }

    /// Read-modify-write `Control_2`. `AF` and `TF` are written 1 (unchanged)
    /// unless explicitly cleared through `clear_mask` (AND semantics).
    fn update_control_2(&mut self, clear_mask: u8, set_bits: u8) -> Result<(), I2C::Error> {
        let reg = self.inner.read_reg(REG_CONTROL_2)?;
        self.inner.write_reg(REG_CONTROL_2, (reg & !clear_mask) | set_bits | (C2_FLAGS & !clear_mask))
    }

    fn update_timer_mode(&mut self, clear_mask: u8, set_bits: u8) -> Result<(), I2C::Error> {
        let reg = self.inner.read_reg(REG_TIMER_MODE)?;
        self.inner.write_reg(REG_TIMER_MODE, ((reg & !clear_mask) | set_bits) & 0x1F)
    }

    /// `OS` flag (bit 7 of `Seconds`) — the time may be invalid; cleared by
    /// [`Self::set_datetime`] or [`Self::clear_oscillator_stop`].
    pub fn oscillator_stopped(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_SECONDS)? & SECONDS_OS != 0)
    }

    /// Clear `OS`, keeping the current seconds value. If
    /// [`Self::oscillator_stopped`] is still true afterwards the oscillator
    /// is not running.
    pub fn clear_oscillator_stop(&mut self) -> Result<(), I2C::Error> {
        let sec = self.inner.read_reg(REG_SECONDS)?;
        self.inner.write_reg(REG_SECONDS, sec & 0x7F)
    }

    /// Freeze the clock (`STOP`=1); the divider chain is held in reset.
    pub fn stop_clock(&mut self) -> Result<(), I2C::Error> {
        self.update_control_1(0, C1_STOP)
    }

    /// Release the clock (`STOP`=0).
    pub fn start_clock(&mut self) -> Result<(), I2C::Error> {
        self.update_control_1(C1_STOP, 0)
    }

    /// Read the alarm registers (`0x0B`-`0x0F`); disabled fields are `None`.
    pub fn get_alarm(&mut self) -> Result<Alarm, I2C::Error> {
        let mut buf = [0u8; 5];
        register::read_register(&mut self.inner.i2c, self.inner.addr, REG_SECOND_ALARM.into(), 1, &mut buf)?;
        let field = |b: u8, mask: u8, bcd: bool| -> Option<u8> {
            if b & 0x80 != 0 {
                None
            } else if bcd {
                Some(bcd_to_bin(b & mask))
            } else {
                Some(b & mask)
            }
        };
        Ok(Alarm {
            second: field(buf[0], 0x7F, true),
            minute: field(buf[1], 0x7F, true),
            hour: field(buf[2], 0x3F, true),
            day: field(buf[3], 0x3F, true),
            weekday: field(buf[4], 0x07, false),
        })
    }

    /// Write the alarm registers (`0x0B`-`0x0F`) and clear `AF`. `None`
    /// fields are disabled (`AEN_x`=1); an alarm with every field `None`
    /// never fires.
    pub fn set_alarm(&mut self, alarm: Alarm) -> Result<(), I2C::Error> {
        let enc = |v: Option<u8>, mask: u8| v.map_or(0x80, |x| bin_to_bcd(x) & mask);
        let buf = [
            REG_SECOND_ALARM,
            enc(alarm.second, 0x7F),
            enc(alarm.minute, 0x7F),
            enc(alarm.hour, 0x3F),
            enc(alarm.day, 0x3F),
            alarm.weekday.map_or(0x80, |w| w & 0x07),
        ];
        register::write_register(&mut self.inner.i2c, self.inner.addr, buf[0].into(), 1, &buf[1..])?;
        self.update_control_2(C2_AF, 0)
    }

    /// Configure and start the countdown timer with a `value` (1-255)
    /// ticking at `source_clock`. The timer is disabled first so
    /// `Timer_value` can be changed safely; `pulsed` selects a pulsed
    /// instead of a level interrupt. Does not set `TIE` — use
    /// [`Self::enable_interrupt`] with [`SOURCE_TIMER`].
    pub fn configure_timer(&mut self, value: u8, source_clock: SourceClock, pulsed: bool) -> Result<(), I2C::Error> {
        self.update_timer_mode(TM_TE, 0)?;
        self.inner.write_reg(REG_TIMER_VALUE, value)?;
        self.update_timer_mode(
            TM_TCF_MASK | TM_TI_TP,
            (source_clock.bits() << 3) | if pulsed { TM_TI_TP } else { 0 } | TM_TE,
        )
    }

    /// Stop the timer (`TE`=0, `TIE`=0) and select the 1/60 Hz clock for
    /// power saving.
    pub fn disable_timer(&mut self) -> Result<(), I2C::Error> {
        self.update_timer_mode(TM_TE | TM_TIE | TM_TCF_MASK, TM_TCF_MASK)
    }

    /// The timer's live countdown value (not the loaded one); reads until
    /// two consecutive reads agree.
    pub fn read_timer(&mut self) -> Result<u8, I2C::Error> {
        let mut prev = self.inner.read_reg(REG_TIMER_VALUE)?;
        loop {
            let cur = self.inner.read_reg(REG_TIMER_VALUE)?;
            if cur == prev {
                return Ok(cur);
            }
            prev = cur;
        }
    }

    /// Select the minute / half-minute interrupt (requires offset mode
    /// [`OffsetMode::Normal`]).
    pub fn set_minute_interrupt(&mut self, mode: MinuteInterrupt) -> Result<(), I2C::Error> {
        let bits = match mode {
            MinuteInterrupt::Off => 0,
            MinuteInterrupt::Minute => C2_MI,
            MinuteInterrupt::HalfMinute => C2_HMI,
        };
        self.update_control_2(C2_MI | C2_HMI, bits)
    }

    /// Drive `CLKOUT` at one of 32768, 16384, 8192, 4096, 2048, 1024 or 1 Hz
    /// (any other value disables it).
    pub fn set_clock_output(&mut self, frequency_hz: u32) -> Result<(), I2C::Error> {
        let cof = match frequency_hz {
            32768 => 0,
            16384 => 1,
            8192 => 2,
            4096 => 3,
            2048 => 4,
            1024 => 5,
            1 => 6,
            _ => 7,
        };
        self.update_control_2(C2_COF, cof)
    }

    /// Disable `CLKOUT` (`COF`=111, pin low) to save power.
    pub fn disable_clock_output(&mut self) -> Result<(), I2C::Error> {
        self.update_control_2(C2_COF, C2_COF)
    }

    /// Read the offset calibration register: two's-complement correction
    /// (−64…+63 LSB) and its mode.
    pub fn get_offset(&mut self) -> Result<(i8, OffsetMode), I2C::Error> {
        let raw = self.inner.read_reg(REG_OFFSET)?;
        let offset = ((raw << 1) as i8) >> 1; // sign-extend bit 6
        let mode = if raw & 0x80 != 0 { OffsetMode::Course } else { OffsetMode::Normal };
        Ok((offset, mode))
    }

    /// Write the offset calibration register (`offset` −64…+63 LSB).
    pub fn set_offset(&mut self, offset: i8, mode: OffsetMode) -> Result<(), I2C::Error> {
        let mode_bit = if mode == OffsetMode::Course { 0x80 } else { 0 };
        self.inner.write_reg(REG_OFFSET, mode_bit | (offset as u8 & 0x7F))
    }

    /// Select the oscillator load capacitance (`CAP_SEL`) — match the
    /// crystal's specified load.
    pub fn set_oscillator_load(&mut self, load: OscillatorLoad) -> Result<(), I2C::Error> {
        self.update_control_1(C1_CAP_SEL, if load == OscillatorLoad::Pf12_5 { C1_CAP_SEL } else { 0 })
    }

    /// Read the free general-purpose RAM byte.
    pub fn read_ram(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_RAM)
    }

    /// Write the free general-purpose RAM byte.
    pub fn write_ram(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_RAM, value)
    }

    /// Send the software-reset sequence (`0x58` to `Control_1`). Resets ALL
    /// registers including the time (2000-01-01 00:00:00, `OS`=1).
    pub fn software_reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONTROL_1, 0x58)
    }

    /// Read `Control_2`, clear the set `AF`/`TF` flags (enable bits and
    /// `COF` preserved), and return the pre-clear status mask —
    /// [`SOURCE_ALARM`] and/or [`SOURCE_TIMER`] (the timer, minute and
    /// half-minute sources all report as [`SOURCE_TIMER`]).
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let ctrl2 = self.inner.read_reg(REG_CONTROL_2)?;
        let mut status = 0;
        if ctrl2 & C2_AF != 0 {
            status |= SOURCE_ALARM;
        }
        if ctrl2 & C2_TF != 0 {
            status |= SOURCE_TIMER;
        }
        if status != 0 {
            // Write 0 only to the flags seen set, 1 to the others, so a flag
            // that sets between the read and this write is not lost.
            self.inner.write_reg(REG_CONTROL_2, (ctrl2 & !C2_FLAGS) | (C2_FLAGS & !ctrl2))?;
        }
        Ok(status)
    }

    /// Enable one or more interrupt sources (bitwise OR of `SOURCE_*`):
    /// `AIE`, `TIE`, `MI`, `HMI`, `CIE`.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.set_interrupt_enables(source, true)
    }

    /// Disable one or more interrupt sources (bitwise OR of `SOURCE_*`).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.set_interrupt_enables(source, false)
    }

    fn set_interrupt_enables(&mut self, source: u8, enable: bool) -> Result<(), I2C::Error> {
        let mut bits2 = 0;
        if source & SOURCE_ALARM != 0 {
            bits2 |= C2_AIE;
        }
        if source & SOURCE_MINUTE != 0 {
            bits2 |= C2_MI;
        }
        if source & SOURCE_HALF_MINUTE != 0 {
            bits2 |= C2_HMI;
        }
        if bits2 != 0 {
            self.update_control_2(if enable { 0 } else { bits2 }, if enable { bits2 } else { 0 })?;
        }
        if source & SOURCE_TIMER != 0 {
            self.update_timer_mode(if enable { 0 } else { TM_TIE }, if enable { TM_TIE } else { 0 })?;
        }
        if source & SOURCE_CORRECTION != 0 {
            self.update_control_1(if enable { 0 } else { C1_CIE }, if enable { C1_CIE } else { 0 })?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x51;

    fn init() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x00]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x00]),
        ]
    }

    #[test]
    fn init_clears_stop_and_12_24() {
        let t = vec![
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x37]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x37]),
            // STOP and 12_24 cleared, SR masked, CIE and CAP_SEL preserved.
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x05]),
        ];
        let mut i2c = I2cMock::new(&t);
        Pcf85063aMinimal::new(i2c.clone(), ADDR).expect("init");
        i2c.done();
    }

    #[test]
    fn minimal_datetime_stop_sequence() {
        let mut t = init();
        t.extend([
            // set_datetime: read Control_1 (12-hour mode set), STOP=1 with 12_24 cleared.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x02]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x20]),
            I2cTransaction::write(ADDR, vec![REG_SECONDS, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x00]),
            // get_datetime: OS flag set in Seconds is masked off.
            I2cTransaction::write_read(ADDR, vec![REG_SECONDS], vec![0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf85063aMinimal::new(i2c.clone(), ADDR).expect("init");
        let dt = DateTime { year: 2026, month: 9, day: 23, weekday: 3, hour: 14, minute: 30, second: 45 };
        rtc.set_datetime(dt).expect("set_datetime");
        assert_eq!(rtc.get_datetime().expect("get_datetime"), dt);
        i2c.done();
    }

    #[test]
    fn set_datetime_rejects_out_of_range_year() {
        let t = init();
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf85063aMinimal::new(i2c.clone(), ADDR).expect("init");
        let dt = DateTime { year: 2100, month: 1, day: 1, weekday: 5, hour: 0, minute: 0, second: 0 };
        assert!(matches!(rtc.set_datetime(dt), Err(Pcf85063aError::InvalidArgument)));
        i2c.done();
    }

    #[test]
    fn full_alarm_offset_timer() {
        let mut t = init();
        t.extend([
            // set_alarm: five registers, then AF cleared (TF kept 1) in Control_2.
            I2cTransaction::write(ADDR, vec![REG_SECOND_ALARM, 0x05, 0x45, 0x80, 0x80, 0x06]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0xC8]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x88]),
            I2cTransaction::write_read(ADDR, vec![REG_SECOND_ALARM], vec![0x05, 0x45, 0x80, 0x80, 0x06]),
            I2cTransaction::write(ADDR, vec![REG_OFFSET, 0xC0]),
            I2cTransaction::write_read(ADDR, vec![REG_OFFSET], vec![0xC0]),
            I2cTransaction::write_read(ADDR, vec![REG_OFFSET], vec![0x3F]),
            // configure_timer(30, Hz1, pulsed): TE cleared, value, then TCF=10 | TI_TP | TE.
            I2cTransaction::write_read(ADDR, vec![REG_TIMER_MODE], vec![0x18]),
            I2cTransaction::write(ADDR, vec![REG_TIMER_MODE, 0x18]),
            I2cTransaction::write(ADDR, vec![REG_TIMER_VALUE, 30]),
            I2cTransaction::write_read(ADDR, vec![REG_TIMER_MODE], vec![0x18]),
            I2cTransaction::write(ADDR, vec![REG_TIMER_MODE, 0x15]),
            // disable_timer -> TE=0, TIE=0, TCF=11, TI_TP kept.
            I2cTransaction::write_read(ADDR, vec![REG_TIMER_MODE], vec![0x17]),
            I2cTransaction::write(ADDR, vec![REG_TIMER_MODE, 0x19]),
            // read_timer: two consecutive reads must agree.
            I2cTransaction::write_read(ADDR, vec![REG_TIMER_VALUE], vec![18]),
            I2cTransaction::write_read(ADDR, vec![REG_TIMER_VALUE], vec![17]),
            I2cTransaction::write_read(ADDR, vec![REG_TIMER_VALUE], vec![17]),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf85063aFull::new(i2c.clone(), ADDR).expect("init");
        let alarm = Alarm { second: Some(5), minute: Some(45), hour: None, day: None, weekday: Some(6) };
        rtc.set_alarm(alarm).expect("set_alarm");
        assert_eq!(rtc.get_alarm().expect("get_alarm"), alarm);
        rtc.set_offset(-64, OffsetMode::Course).expect("set_offset");
        assert_eq!(rtc.get_offset().expect("get_offset"), (-64, OffsetMode::Course));
        assert_eq!(rtc.get_offset().expect("get_offset"), (63, OffsetMode::Normal));
        rtc.configure_timer(30, SourceClock::Hz1, true).expect("timer");
        rtc.disable_timer().expect("disable_timer");
        assert_eq!(rtc.read_timer().expect("read_timer"), 17);
        i2c.done();
    }

    #[test]
    fn full_clock_output_load_ram() {
        let mut t = init();
        t.extend([
            // set_clock_output(1): COF=110, flags written 1.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0x48]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x4E]),
            // disable_clock_output: COF=111.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0x4E]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x4F]),
            // set_minute_interrupt(Minute): MI set, COF kept.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0x4F]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x6F]),
            // set_oscillator_load(12.5 pF).
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x01]),
            // write_ram / read_ram.
            I2cTransaction::write(ADDR, vec![REG_RAM, 0xA5]),
            I2cTransaction::write_read(ADDR, vec![REG_RAM], vec![0xA5]),
            // clear_oscillator_stop keeps the seconds bits.
            I2cTransaction::write_read(ADDR, vec![REG_SECONDS], vec![0xD9]),
            I2cTransaction::write(ADDR, vec![REG_SECONDS, 0x59]),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf85063aFull::new(i2c.clone(), ADDR).expect("init");
        rtc.set_clock_output(1).expect("clkout");
        rtc.disable_clock_output().expect("clkout off");
        rtc.set_minute_interrupt(MinuteInterrupt::Minute).expect("minute");
        rtc.set_oscillator_load(OscillatorLoad::Pf12_5).expect("load");
        rtc.write_ram(0xA5).expect("write_ram");
        assert_eq!(rtc.read_ram().expect("read_ram"), 0xA5);
        rtc.clear_oscillator_stop().expect("clear os");
        i2c.done();
    }

    #[test]
    fn full_interrupts() {
        let mut t = init();
        t.extend([
            // poll_interrupt: AF set, AIE|MI enabled, COF=5; AF cleared, TF written 1.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0xE5]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0xA5 | 0x08]),
            // poll_interrupt with TF only.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0x08]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x40]),
            // enable_interrupt(ALARM | CORRECTION | TIMER).
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x80 | 0x48]),
            I2cTransaction::write_read(ADDR, vec![REG_TIMER_MODE], vec![0x18]),
            I2cTransaction::write(ADDR, vec![REG_TIMER_MODE, 0x1A]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x04]),
            // software_reset.
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x58]),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf85063aFull::new(i2c.clone(), ADDR).expect("init");
        assert_eq!(rtc.poll_interrupt().expect("poll"), SOURCE_ALARM);
        assert_eq!(rtc.poll_interrupt().expect("poll"), SOURCE_TIMER);
        rtc.enable_interrupt(SOURCE_ALARM | SOURCE_CORRECTION | SOURCE_TIMER).expect("enable");
        rtc.software_reset().expect("reset");
        i2c.done();
    }
}
