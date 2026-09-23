//! PCF8523 — Low-power I²C real-time clock and calendar (NXP).
//!
//! Fixed I²C address `0x68`. Maintains seconds/minutes/hours/days/weekdays/
//! months/years in BCD registers, backed by a battery switch-over circuit
//! (`VDD` → `VBAT`) with battery-low detection. Adds one alarm (minute/
//! hour/day/weekday fields, each independently enabled), Timer A (countdown
//! or watchdog), Timer B (countdown, dedicated `INT2` pin), a programmable
//! `CLKOUT` sharing the `INT1` pin, and a clock-offset calibration register.
//!
//! [`Pcf8523Minimal::new`] enables battery switch-over in standard mode with
//! battery-low detection (`PM[2:0]`=`000`) — a deliberate override of the
//! chip's single-supply power-on default. The hour registers are always
//! operated in 24-hour mode, and `weekday` follows the datasheet's
//! suggested assignment (`0`=Sunday … `6`=Saturday) — see
//! `specs/rtc/pcf8523.md` for the full register map and rationale.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Pcf8523Full::poll_interrupt`] (no callback
//! subscription — the caller wires it into an ISR or polling loop) plus
//! [`Pcf8523Full::enable_interrupt`] / [`Pcf8523Full::disable_interrupt`]
//! with the `SOURCE_*` constants.

use embedded_hal::i2c::I2c;

const REG_CONTROL_1: u8 = 0x00;
const REG_CONTROL_2: u8 = 0x01;
const REG_CONTROL_3: u8 = 0x02;
const REG_SECONDS: u8 = 0x03;
const REG_MINUTE_ALARM: u8 = 0x0A;
const REG_OFFSET: u8 = 0x0E;
const REG_TMR_CLKOUT_CTRL: u8 = 0x0F;
const REG_TMR_A_FREQ_CTRL: u8 = 0x10;
const REG_TMR_A_REG: u8 = 0x11;
const REG_TMR_B_FREQ_CTRL: u8 = 0x12;
const REG_TMR_B_REG: u8 = 0x13;

const C1_T: u8 = 0x40;
const C1_STOP: u8 = 0x20;
const C1_SR: u8 = 0x10;
const C1_12_24: u8 = 0x08;
const C1_SIE: u8 = 0x04;
const C1_AIE: u8 = 0x02;

const C2_WTAF: u8 = 0x80;
const C2_CTAF: u8 = 0x40;
const C2_CTBF: u8 = 0x20;
const C2_SF: u8 = 0x10;
const C2_AF: u8 = 0x08;
const C2_WTAIE: u8 = 0x04;
const C2_CTAIE: u8 = 0x02;
const C2_CTBIE: u8 = 0x01;
const C2_CLEARABLE: u8 = 0x78; // CTAF|CTBF|SF|AF: write 0 clears, 1 keeps
const C2_ENABLES: u8 = 0x07;

const C3_PM_MASK: u8 = 0xE0;
const C3_BSF: u8 = 0x08;
const C3_BLF: u8 = 0x04;
const C3_BSIE: u8 = 0x02;
const C3_BLIE: u8 = 0x01;

const SECONDS_OS: u8 = 0x80;

const TMR_TAM: u8 = 0x80;
const TMR_TBM: u8 = 0x40;
const TMR_COF_MASK: u8 = 0x38;
const TMR_TAC_MASK: u8 = 0x06;
const TMR_TAC_COUNTDOWN: u8 = 0x02;
const TMR_TAC_WATCHDOG: u8 = 0x04;
const TMR_TBC: u8 = 0x01;

/// `TBW[2:0]` → low-pulse width in ms (datasheet Table 36; not uniformly spaced).
const TBW_WIDTHS_MS: [f32; 8] = [46.875, 62.5, 78.125, 93.75, 125.0, 156.25, 187.5, 218.75];

/// Fixed 7-bit I²C address.
pub const I2C_ADDRESS: u8 = 0x68;

/// Interrupt source: second tick (`SF`).
pub const SOURCE_SECOND: u8 = 0x01;
/// Interrupt source: Timer A timed out (`CTAF` or `WTAF`).
pub const SOURCE_TIMER_A: u8 = 0x02;
/// Interrupt source: Timer B timed out (`CTBF`).
pub const SOURCE_TIMER_B: u8 = 0x04;
/// Interrupt source: all enabled alarm fields matched (`AF`).
pub const SOURCE_ALARM: u8 = 0x08;
/// Interrupt source: battery switch-over occurred (`BSF`).
pub const SOURCE_BATTERY_SWITCH: u8 = 0x10;
/// Interrupt source: battery low (`BLF`).
pub const SOURCE_BATTERY_LOW: u8 = 0x20;

/// Calendar clock reading/target: BCD-decoded, 24-hour, Sunday=0 weekday.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DateTime {
    /// Full year, e.g. `2026` (register only stores 2000-2099).
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
    /// Minute, `0`-`59`.
    pub minute: Option<u8>,
    /// Hour, `0`-`23`.
    pub hour: Option<u8>,
    /// Day of month, `1`-`31`.
    pub day: Option<u8>,
    /// Day of week, `0`=Sunday … `6`=Saturday.
    pub weekday: Option<u8>,
}

/// Timer A operating mode.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum TimerAMode {
    /// Countdown timer (`TAC`=01), sets `CTAF`.
    Countdown,
    /// Watchdog timer (`TAC`=10), sets `WTAF`.
    Watchdog,
}

/// Timer source clock (`TAQ`/`TBQ` encoding).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum SourceClock {
    /// 4096 Hz — 244 µs … 62.256 ms.
    Hz4096,
    /// 64 Hz — 15.625 ms … 3.984 s.
    Hz64,
    /// 1 Hz — 1 s … 255 s.
    Hz1,
    /// 1/60 Hz — 1 min … 255 min.
    Hz1_60,
    /// 1/3600 Hz — 1 h … 255 h.
    Hz1_3600,
}

impl SourceClock {
    fn bits(self) -> u8 {
        match self {
            SourceClock::Hz4096 => 0x00,
            SourceClock::Hz64 => 0x01,
            SourceClock::Hz1 => 0x02,
            SourceClock::Hz1_60 => 0x03,
            SourceClock::Hz1_3600 => 0x07,
        }
    }
}

/// Offset correction interval.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum OffsetMode {
    /// Correction once every two hours, 4.34 ppm per LSB (default).
    EveryTwoHours,
    /// Correction once every minute, 4.069 ppm per LSB.
    EveryMinute,
}

/// Battery switch-over mode.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum BatteryMode {
    /// Switch when `VDD` < `VBAT` and `VDD` < 2.5 V.
    Standard,
    /// Switch whenever `VDD` < `VBAT`.
    Direct,
    /// `VDD` only — tie `VBAT` to `VDD`.
    Disabled,
}

fn bcd_to_bin(b: u8) -> u8 {
    (b >> 4) * 10 + (b & 0x0F)
}

fn bin_to_bcd(v: u8) -> u8 {
    ((v / 10) << 4) | (v % 10)
}

/// PCF8523 minimal driver — calendar clock with battery backup enabled, no
/// configuration required beyond the connection.
pub struct Pcf8523Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Pcf8523Minimal<I2C> {
    /// Create a new `Pcf8523Minimal`: confirm the device answers (plain
    /// `CONTROL_1` read — no identity register) and write `CONTROL_3`=`0x00`
    /// (battery switch-over standard mode, battery-low detection enabled).
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit I²C address (fixed [`I2C_ADDRESS`]).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut buf = [0u8; 1];
        i2c.write_read(addr, &[REG_CONTROL_1], &mut buf)?;
        i2c.write(addr, &[REG_CONTROL_3, 0x00])?;
        Ok(Self { i2c, addr })
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.write_read(self.addr, &[reg], &mut buf)?;
        Ok(buf[0])
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[reg, value])
    }

    /// `CONTROL_1` with `T` and `SR` masked, safe for read-modify-write.
    fn read_control_1(&mut self) -> Result<u8, I2C::Error> {
        Ok(self.read_reg(REG_CONTROL_1)? & !(C1_T | C1_SR))
    }

    /// Read the current calendar clock.
    pub fn get_datetime(&mut self) -> Result<DateTime, I2C::Error> {
        let mut buf = [0u8; 7];
        self.i2c.write_read(self.addr, &[REG_SECONDS], &mut buf)?;
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
    pub fn set_datetime(&mut self, dt: DateTime) -> Result<(), I2C::Error> {
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
            bin_to_bcd((dt.year % 100) as u8),
        ];
        self.i2c.write(self.addr, &buf)?;
        self.write_reg(REG_CONTROL_1, ctrl1 & !C1_STOP)
    }
}

/// PCF8523 full driver — extends minimal with the alarm, both timers,
/// `CLKOUT`, offset calibration, battery backup control/status,
/// oscillator-stop detection, software reset, and the interrupt API.
///
/// `INT1` is shared with `CLKOUT`: interrupts only reach `INT1` once
/// `CLKOUT` is disabled ([`Self::disable_clock_output`]).
pub struct Pcf8523Full<I2C> {
    inner: Pcf8523Minimal<I2C>,
}

impl<I2C: I2c> Pcf8523Full<I2C> {
    /// Create a new `Pcf8523Full`. See [`Pcf8523Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Pcf8523Minimal::new(i2c, addr)? })
    }

    /// Read the current calendar clock. Delegates to [`Pcf8523Minimal::get_datetime`].
    pub fn get_datetime(&mut self) -> Result<DateTime, I2C::Error> {
        self.inner.get_datetime()
    }

    /// Set the calendar clock. Delegates to [`Pcf8523Minimal::set_datetime`].
    pub fn set_datetime(&mut self, dt: DateTime) -> Result<(), I2C::Error> {
        self.inner.set_datetime(dt)
    }

    fn update_tmr_clkout(&mut self, clear_mask: u8, set_bits: u8) -> Result<(), I2C::Error> {
        let reg = self.inner.read_reg(REG_TMR_CLKOUT_CTRL)?;
        self.inner.write_reg(REG_TMR_CLKOUT_CTRL, (reg & !clear_mask) | set_bits)
    }

    /// Re-supply the `CONTROL_2` enable bits, writing 1 to every clearable
    /// flag so none is cleared by accident (AND semantics).
    fn write_control_2(&mut self, enables: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONTROL_2, C2_CLEARABLE | (enables & C2_ENABLES))
    }

    /// Write `CONTROL_3` with `BSF`=1 so it is left unchanged.
    fn write_control_3(&mut self, value: u8) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONTROL_3, (value & (C3_PM_MASK | C3_BSIE | C3_BLIE)) | C3_BSF)
    }

    /// Read the alarm registers (`0x0A`-`0x0D`); disabled fields are `None`.
    pub fn get_alarm(&mut self) -> Result<Alarm, I2C::Error> {
        let mut buf = [0u8; 4];
        self.inner.i2c.write_read(self.inner.addr, &[REG_MINUTE_ALARM], &mut buf)?;
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
            minute: field(buf[0], 0x7F, true),
            hour: field(buf[1], 0x3F, true),
            day: field(buf[2], 0x3F, true),
            weekday: field(buf[3], 0x07, false),
        })
    }

    /// Write the alarm registers (`0x0A`-`0x0D`). `None` fields are disabled
    /// (`AEN_x`=1); an alarm with every field `None` never fires.
    pub fn set_alarm(&mut self, alarm: Alarm) -> Result<(), I2C::Error> {
        let enc = |v: Option<u8>, mask: u8| v.map_or(0x80, |x| bin_to_bcd(x) & mask);
        let buf = [
            REG_MINUTE_ALARM,
            enc(alarm.minute, 0x7F),
            enc(alarm.hour, 0x3F),
            enc(alarm.day, 0x3F),
            alarm.weekday.map_or(0x80, |w| w & 0x07),
        ];
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    /// Configure and start Timer A with a countdown `value` (0-255) ticking
    /// at `source_clock`; `pulsed` selects a pulsed instead of a
    /// permanently-active interrupt.
    pub fn configure_timer_a(&mut self, mode: TimerAMode, value: u8, source_clock: SourceClock, pulsed: bool) -> Result<(), I2C::Error> {
        let tac = match mode {
            TimerAMode::Countdown => TMR_TAC_COUNTDOWN,
            TimerAMode::Watchdog => TMR_TAC_WATCHDOG,
        };
        self.inner.write_reg(REG_TMR_A_FREQ_CTRL, source_clock.bits())?;
        self.inner.write_reg(REG_TMR_A_REG, value)?;
        self.update_tmr_clkout(TMR_TAM | TMR_TAC_MASK, if pulsed { TMR_TAM } else { 0 } | tac)
    }

    /// Stop Timer A (`TAC`=00).
    pub fn disable_timer_a(&mut self) -> Result<(), I2C::Error> {
        self.update_tmr_clkout(TMR_TAC_MASK, 0)
    }

    /// Timer A's live countdown value (not the loaded one).
    pub fn read_timer_a(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_TMR_A_REG)
    }

    /// Configure and start Timer B (also drives `INT2`). `pulse_width_ms`
    /// is snapped to the nearest of the eight hardware widths
    /// (46.875-218.75 ms; default 46.875).
    pub fn configure_timer_b(&mut self, value: u8, source_clock: SourceClock, pulse_width_ms: f32, pulsed: bool) -> Result<(), I2C::Error> {
        let mut tbw = 0usize;
        for (i, w) in TBW_WIDTHS_MS.iter().enumerate() {
            if (w - pulse_width_ms).abs() < (TBW_WIDTHS_MS[tbw] - pulse_width_ms).abs() {
                tbw = i;
            }
        }
        self.inner.write_reg(REG_TMR_B_FREQ_CTRL, ((tbw as u8) << 4) | source_clock.bits())?;
        self.inner.write_reg(REG_TMR_B_REG, value)?;
        self.update_tmr_clkout(TMR_TBM | TMR_TBC, if pulsed { TMR_TBM } else { 0 } | TMR_TBC)
    }

    /// Stop Timer B (`TBC`=0).
    pub fn disable_timer_b(&mut self) -> Result<(), I2C::Error> {
        self.update_tmr_clkout(TMR_TBC, 0)
    }

    /// Timer B's live countdown value (not the loaded one).
    pub fn read_timer_b(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_TMR_B_REG)
    }

    /// Drive `CLKOUT` on the shared `INT1`/`CLKOUT` pin at one of 32768,
    /// 16384, 8192, 4096, 1024, 32 or 1 Hz (any other value disables it).
    pub fn set_clock_output(&mut self, frequency_hz: u32) -> Result<(), I2C::Error> {
        let cof = match frequency_hz {
            32768 => 0,
            16384 => 1,
            8192 => 2,
            4096 => 3,
            1024 => 4,
            32 => 5,
            1 => 6,
            _ => 7,
        };
        self.update_tmr_clkout(TMR_COF_MASK, cof << 3)
    }

    /// Disable `CLKOUT` (`COF`=111), freeing `INT1` for interrupts.
    pub fn disable_clock_output(&mut self) -> Result<(), I2C::Error> {
        self.update_tmr_clkout(TMR_COF_MASK, TMR_COF_MASK)
    }

    /// Read the offset calibration register: two's-complement correction
    /// (−64…+63 LSB) and its interval.
    pub fn get_offset(&mut self) -> Result<(i8, OffsetMode), I2C::Error> {
        let raw = self.inner.read_reg(REG_OFFSET)?;
        let offset = ((raw << 1) as i8) >> 1; // sign-extend bit 6
        let mode = if raw & 0x80 != 0 { OffsetMode::EveryMinute } else { OffsetMode::EveryTwoHours };
        Ok((offset, mode))
    }

    /// Write the offset calibration register (`offset` −64…+63 LSB).
    pub fn set_offset(&mut self, offset: i8, mode: OffsetMode) -> Result<(), I2C::Error> {
        let mode_bit = if mode == OffsetMode::EveryMinute { 0x80 } else { 0 };
        self.inner.write_reg(REG_OFFSET, mode_bit | (offset as u8 & 0x7F))
    }

    /// Select the battery switch-over mode (`PM[2:0]`), with or without
    /// battery-low detection.
    pub fn configure_battery_backup(&mut self, mode: BatteryMode, low_detection: bool) -> Result<(), I2C::Error> {
        let pm = match (mode, low_detection) {
            (BatteryMode::Standard, true) => 0x00,
            (BatteryMode::Direct, true) => 0x01,
            (BatteryMode::Disabled, true) => 0x02,
            (BatteryMode::Standard, false) => 0x04,
            (BatteryMode::Direct, false) => 0x05,
            (BatteryMode::Disabled, false) => 0x07,
        };
        let ctrl3 = self.inner.read_reg(REG_CONTROL_3)?;
        self.write_control_3((ctrl3 & !C3_PM_MASK) | (pm << 5))
    }

    /// `BSF` — a switch-over to `VBAT` occurred since it was last cleared.
    pub fn is_battery_switched_over(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONTROL_3)? & C3_BSF != 0)
    }

    /// Clear `BSF` only, leaving `PM` and the enable bits unchanged.
    pub fn clear_battery_switchover(&mut self) -> Result<(), I2C::Error> {
        let ctrl3 = self.inner.read_reg(REG_CONTROL_3)?;
        self.inner.write_reg(REG_CONTROL_3, ctrl3 & (C3_PM_MASK | C3_BSIE | C3_BLIE))
    }

    /// `BLF` (read-only) — `VBAT` is below the detection threshold.
    pub fn is_battery_low(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_CONTROL_3)? & C3_BLF != 0)
    }

    /// `OS` flag (bit 7 of `SECONDS`) — the time may be invalid; cleared by
    /// [`Self::set_datetime`].
    pub fn oscillator_stopped(&mut self) -> Result<bool, I2C::Error> {
        Ok(self.inner.read_reg(REG_SECONDS)? & SECONDS_OS != 0)
    }

    /// Send the software-reset sequence (`0x58` to `CONTROL_1`). Resets all
    /// control/configuration registers to power-on defaults — including
    /// `PM`=111 (battery backup disabled) — but keeps the time/date/alarm/
    /// timer values.
    pub fn software_reset(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_CONTROL_1, 0x58)
    }

    /// Read `CONTROL_2`/`CONTROL_3`, clear the set `CTAF`/`CTBF`/`SF`/`AF`/
    /// `BSF` flags (`WTAF`/`BLF` are read-only; enable bits untouched), and
    /// return the pre-clear status mask — test with the `SOURCE_*` constants.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 2];
        self.inner.i2c.write_read(self.inner.addr, &[REG_CONTROL_2], &mut buf)?;
        let (ctrl2, ctrl3) = (buf[0], buf[1]);
        let mut status = 0;
        if ctrl2 & C2_SF != 0 {
            status |= SOURCE_SECOND;
        }
        if ctrl2 & (C2_CTAF | C2_WTAF) != 0 {
            status |= SOURCE_TIMER_A;
        }
        if ctrl2 & C2_CTBF != 0 {
            status |= SOURCE_TIMER_B;
        }
        if ctrl2 & C2_AF != 0 {
            status |= SOURCE_ALARM;
        }
        if ctrl3 & C3_BSF != 0 {
            status |= SOURCE_BATTERY_SWITCH;
        }
        if ctrl3 & C3_BLF != 0 {
            status |= SOURCE_BATTERY_LOW;
        }
        // Write 0 only to the flags seen set, 1 to the rest, so a flag that
        // sets between the read and this write is not lost.
        if ctrl2 & C2_CLEARABLE != 0 {
            self.inner.write_reg(REG_CONTROL_2, (C2_CLEARABLE & !ctrl2) | (ctrl2 & C2_ENABLES))?;
        }
        if ctrl3 & C3_BSF != 0 {
            self.inner.write_reg(REG_CONTROL_3, ctrl3 & (C3_PM_MASK | C3_BSIE | C3_BLIE))?;
        }
        Ok(status)
    }

    /// Enable one or more interrupt sources (bitwise OR of `SOURCE_*`).
    /// [`SOURCE_TIMER_A`] sets `WTAIE` or `CTAIE` depending on Timer A's
    /// configured mode — call [`Self::configure_timer_a`] first.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.set_interrupt_enables(source, true)
    }

    /// Disable one or more interrupt sources (bitwise OR of `SOURCE_*`).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        self.set_interrupt_enables(source, false)
    }

    fn set_interrupt_enables(&mut self, source: u8, enable: bool) -> Result<(), I2C::Error> {
        let apply = |reg: u8, bits: u8| if enable { reg | bits } else { reg & !bits };
        if source & (SOURCE_SECOND | SOURCE_ALARM) != 0 {
            let mut bits = 0;
            if source & SOURCE_SECOND != 0 {
                bits |= C1_SIE;
            }
            if source & SOURCE_ALARM != 0 {
                bits |= C1_AIE;
            }
            let ctrl1 = self.inner.read_control_1()?;
            self.inner.write_reg(REG_CONTROL_1, apply(ctrl1, bits))?;
        }
        if source & (SOURCE_TIMER_A | SOURCE_TIMER_B) != 0 {
            let mut bits = 0;
            if source & SOURCE_TIMER_A != 0 {
                if enable {
                    let tac = self.inner.read_reg(REG_TMR_CLKOUT_CTRL)? & TMR_TAC_MASK;
                    bits |= if tac == TMR_TAC_WATCHDOG { C2_WTAIE } else { C2_CTAIE };
                } else {
                    bits |= C2_WTAIE | C2_CTAIE;
                }
            }
            if source & SOURCE_TIMER_B != 0 {
                bits |= C2_CTBIE;
            }
            let enables = self.inner.read_reg(REG_CONTROL_2)? & C2_ENABLES;
            self.write_control_2(apply(enables, bits))?;
        }
        if source & (SOURCE_BATTERY_SWITCH | SOURCE_BATTERY_LOW) != 0 {
            let mut bits = 0;
            if source & SOURCE_BATTERY_SWITCH != 0 {
                bits |= C3_BSIE;
            }
            if source & SOURCE_BATTERY_LOW != 0 {
                bits |= C3_BLIE;
            }
            let ctrl3 = self.inner.read_reg(REG_CONTROL_3)?;
            self.write_control_3(apply(ctrl3, bits))?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x68;

    fn init() -> Vec<I2cTransaction> {
        vec![
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_3, 0x00]),
        ]
    }

    #[test]
    fn minimal_datetime_stop_sequence() {
        let mut t = init();
        t.extend([
            // set_datetime: read CONTROL_1 (12-hour mode set), STOP=1 with 12_24 cleared.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x08]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x20]),
            I2cTransaction::write(ADDR, vec![REG_SECONDS, 0x45, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x00]),
            // get_datetime: OS flag set in SECONDS is masked off.
            I2cTransaction::write_read(ADDR, vec![REG_SECONDS], vec![0xC5, 0x30, 0x14, 0x23, 0x03, 0x09, 0x26]),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf8523Minimal::new(i2c.clone(), ADDR).expect("init");
        let dt = DateTime { year: 2026, month: 9, day: 23, weekday: 3, hour: 14, minute: 30, second: 45 };
        rtc.set_datetime(dt).expect("set_datetime");
        assert_eq!(rtc.get_datetime().expect("get_datetime"), dt);
        i2c.done();
    }

    #[test]
    fn full_alarm_offset_timers() {
        let mut t = init();
        t.extend([
            I2cTransaction::write(ADDR, vec![REG_MINUTE_ALARM, 0x45, 0x80, 0x80, 0x06]),
            I2cTransaction::write_read(ADDR, vec![REG_MINUTE_ALARM], vec![0x45, 0x80, 0x80, 0x06]),
            I2cTransaction::write(ADDR, vec![REG_OFFSET, 0xC0]),
            I2cTransaction::write_read(ADDR, vec![REG_OFFSET], vec![0xC0]),
            I2cTransaction::write_read(ADDR, vec![REG_OFFSET], vec![0x3F]),
            // configure_timer_a(Watchdog, 5, Hz1, pulsed).
            I2cTransaction::write(ADDR, vec![REG_TMR_A_FREQ_CTRL, 0x02]),
            I2cTransaction::write(ADDR, vec![REG_TMR_A_REG, 5]),
            I2cTransaction::write_read(ADDR, vec![REG_TMR_CLKOUT_CTRL], vec![0x38]),
            I2cTransaction::write(ADDR, vec![REG_TMR_CLKOUT_CTRL, 0xBC]),
            // configure_timer_b(30, Hz1_60, 130 ms -> 125 ms = TBW 100).
            I2cTransaction::write(ADDR, vec![REG_TMR_B_FREQ_CTRL, 0x43]),
            I2cTransaction::write(ADDR, vec![REG_TMR_B_REG, 30]),
            I2cTransaction::write_read(ADDR, vec![REG_TMR_CLKOUT_CTRL], vec![0xBC]),
            I2cTransaction::write(ADDR, vec![REG_TMR_CLKOUT_CTRL, 0xBD]),
            // configure_battery_backup(Direct, false) -> PM=101, BSF written 1.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_3], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_3, 0xA8]),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf8523Full::new(i2c.clone(), ADDR).expect("init");
        let alarm = Alarm { minute: Some(45), hour: None, day: None, weekday: Some(6) };
        rtc.set_alarm(alarm).expect("set_alarm");
        assert_eq!(rtc.get_alarm().expect("get_alarm"), alarm);
        rtc.set_offset(-64, OffsetMode::EveryMinute).expect("set_offset");
        assert_eq!(rtc.get_offset().expect("get_offset"), (-64, OffsetMode::EveryMinute));
        assert_eq!(rtc.get_offset().expect("get_offset"), (63, OffsetMode::EveryTwoHours));
        rtc.configure_timer_a(TimerAMode::Watchdog, 5, SourceClock::Hz1, true).expect("timer_a");
        rtc.configure_timer_b(30, SourceClock::Hz1_60, 130.0, false).expect("timer_b");
        rtc.configure_battery_backup(BatteryMode::Direct, false).expect("battery");
        i2c.done();
    }

    #[test]
    fn full_interrupts() {
        let mut t = init();
        t.extend([
            // poll_interrupt: WTAF, CTBF, AF set; CTAIE|CTBIE enabled; BSF, BLF, BSIE.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0xAB, 0x0E]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x53]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_3, 0x02]),
            // enable_interrupt(SECOND | ALARM | TIMER_A(watchdog) | BATTERY_LOW).
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_1], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x06]),
            I2cTransaction::write_read(ADDR, vec![REG_TMR_CLKOUT_CTRL], vec![0x3C]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_2], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_2, 0x7C]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_3], vec![0x00]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_3, 0x09]),
            // software_reset.
            I2cTransaction::write(ADDR, vec![REG_CONTROL_1, 0x58]),
        ]);
        let mut i2c = I2cMock::new(&t);
        let mut rtc = Pcf8523Full::new(i2c.clone(), ADDR).expect("init");
        let status = rtc.poll_interrupt().expect("poll");
        assert_eq!(status, SOURCE_TIMER_A | SOURCE_TIMER_B | SOURCE_ALARM | SOURCE_BATTERY_SWITCH | SOURCE_BATTERY_LOW);
        rtc.enable_interrupt(SOURCE_SECOND | SOURCE_ALARM | SOURCE_TIMER_A | SOURCE_BATTERY_LOW).expect("enable");
        rtc.software_reset().expect("reset");
        i2c.done();
    }
}
