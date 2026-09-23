//! DS3231 — Extremely accurate I²C-integrated RTC/TCXO/crystal (Analog
//! Devices / Maxim Integrated).
//!
//! Fixed I²C address `0x68`. Maintains seconds/minutes/hours/day/date/
//! month/year in battery-backed BCD registers, corrected every 64 s by an
//! integrated temperature-compensated crystal oscillator (±2 ppm accuracy),
//! and exposes the on-chip temperature reading used for that correction.
//! Two independent time-of-day/date alarms share the `INT/SQW` pin with a
//! selectable square-wave output.
//!
//! This driver always operates the hour registers in 24-hour mode and
//! defines the day-of-week register (`1`–`7`) as ISO 8601 (`1`=Monday …
//! `7`=Sunday) — see `specs/rtc/ds3231.md` for the full register map and
//! rationale.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Ds3231Full::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers) plus [`Ds3231Full::enable_interrupt`] /
//! [`Ds3231Full::disable_interrupt`] with [`SOURCE_ALARM1`] / [`SOURCE_ALARM2`].

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

const REG_SECONDS: u8 = 0x00;
const REG_MINUTES: u8 = 0x01;
const REG_HOURS: u8 = 0x02;
const REG_DAY: u8 = 0x03;
const REG_DATE: u8 = 0x04;
const REG_MONTH_CENTURY: u8 = 0x05;
const REG_YEAR: u8 = 0x06;
const REG_ALARM1_SECONDS: u8 = 0x07;
const REG_ALARM1_MINUTES: u8 = 0x08;
const REG_ALARM1_HOURS: u8 = 0x09;
const REG_ALARM1_DAY_DATE: u8 = 0x0A;
const REG_ALARM2_MINUTES: u8 = 0x0B;
const REG_ALARM2_HOURS: u8 = 0x0C;
const REG_ALARM2_DAY_DATE: u8 = 0x0D;
const REG_CONTROL: u8 = 0x0E;
const REG_CONTROL_STATUS: u8 = 0x0F;
const REG_AGING_OFFSET: u8 = 0x10;
const REG_TEMP_MSB: u8 = 0x11;

const CONTROL_INTCN: u8 = 0x04;
const CONTROL_A2IE: u8 = 0x02;
const CONTROL_A1IE: u8 = 0x01;
const CONTROL_CONV: u8 = 0x20;
const CONTROL_BBSQW: u8 = 0x40;
const CONTROL_RS_MASK: u8 = 0x18;
const STATUS_OSF: u8 = 0x80;
const STATUS_EN32KHZ: u8 = 0x08;
const STATUS_BSY: u8 = 0x04;
const STATUS_A2F: u8 = 0x02;
const STATUS_A1F: u8 = 0x01;

/// Interrupt source: Alarm 1 matched.
pub const SOURCE_ALARM1: u8 = 0x01;
/// Interrupt source: Alarm 2 matched.
pub const SOURCE_ALARM2: u8 = 0x02;

/// Calendar clock reading/target: BCD-decoded, 24-hour, ISO 8601 weekday.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DateTime {
    /// Full year, e.g. `2026` (register only stores 2000-2099).
    pub year: u16,
    /// Month, `1`-`12`.
    pub month: u8,
    /// Day of month, `1`-`31`.
    pub day: u8,
    /// Day of week, `1`=Monday … `7`=Sunday (ISO 8601).
    pub weekday: u8,
    /// Hour, `0`-`23`.
    pub hour: u8,
    /// Minute, `0`-`59`.
    pub minute: u8,
    /// Second, `0`-`59`.
    pub second: u8,
}

/// Alarm 1 match granularity (Table 2 of the datasheet).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Alarm1Match {
    /// Alarm once per second (mask bits A1M4:A1M1 = 1111).
    EverySecond,
    /// Alarm when seconds match (1110).
    Seconds,
    /// Alarm when minutes and seconds match (1100).
    MinutesSeconds,
    /// Alarm when hours, minutes, seconds match (1000).
    HoursMinutesSeconds,
    /// Alarm when date, hours, minutes, seconds match (0000, DY/DT=0).
    DateHoursMinutesSeconds,
    /// Alarm when day-of-week, hours, minutes, seconds match (0000, DY/DT=1).
    DayHoursMinutesSeconds,
}

/// Alarm 2 match granularity (Table 2 of the datasheet).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Alarm2Match {
    /// Alarm once per minute, at 00 seconds (mask bits A2M4:A2M2 = 111).
    EveryMinute,
    /// Alarm when minutes match (110).
    Minutes,
    /// Alarm when hours and minutes match (100).
    HoursMinutes,
    /// Alarm when date, hours, minutes match (000, DY/DT=0).
    DateHoursMinutes,
    /// Alarm when day-of-week, hours, minutes match (000, DY/DT=1).
    DayHoursMinutes,
}

fn bcd_to_bin(b: u8) -> u8 {
    (b >> 4) * 10 + (b & 0x0F)
}

fn bin_to_bcd(v: u8) -> u8 {
    ((v / 10) << 4) | (v % 10)
}

fn write_reg<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, value: u8) -> Result<(), I2C::Error> {
    i2c.write(addr, &[reg, value])
}

fn read_reg8<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8) -> Result<u8, I2C::Error> {
    let mut buf = [0u8; 1];
    i2c.write_read(addr, &[reg], &mut buf)?;
    Ok(buf[0])
}

fn read_regs<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

fn decode_alarm1_mask(a1m4: bool, a1m3: bool, a1m2: bool, a1m1: bool, dydt: bool) -> Alarm1Match {
    match (a1m4, a1m3, a1m2, a1m1) {
        (true, true, true, true) => Alarm1Match::EverySecond,
        (true, true, true, false) => Alarm1Match::Seconds,
        (true, true, false, false) => Alarm1Match::MinutesSeconds,
        (true, false, false, false) => Alarm1Match::HoursMinutesSeconds,
        (false, false, false, false) if dydt => Alarm1Match::DayHoursMinutesSeconds,
        _ => Alarm1Match::DateHoursMinutesSeconds,
    }
}

fn decode_alarm2_mask(a2m4: bool, a2m3: bool, a2m2: bool, dydt: bool) -> Alarm2Match {
    match (a2m4, a2m3, a2m2) {
        (true, true, true) => Alarm2Match::EveryMinute,
        (true, true, false) => Alarm2Match::Minutes,
        (true, false, false) => Alarm2Match::HoursMinutes,
        (false, false, false) if dydt => Alarm2Match::DayHoursMinutes,
        _ => Alarm2Match::DateHoursMinutes,
    }
}

/// DS3231 minimal driver — calendar clock plus the on-chip temperature
/// reading, no configuration required beyond the connection.
pub struct Ds3231Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Ds3231Minimal<I2C> {
    /// Create a new `Ds3231Minimal` and confirm the device answers on the bus.
    ///
    /// No register writes are made — every `CONTROL`/`CONTROL_STATUS` bit is
    /// already at a usable power-on default. Time/date registers are left
    /// untouched; call [`Self::set_datetime`] before trusting
    /// [`Self::get_datetime`] on a chip with no prior `VBAT`.
    ///
    /// # Arguments
    /// * `i2c` — Configured I²C bus implementing [`embedded_hal::i2c::I2c`].
    /// * `addr` — 7-bit I²C address (fixed `0x68`).
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        read_reg8(&mut i2c, addr, REG_CONTROL)?;
        Ok(Self { i2c, addr })
    }

    /// Read the current calendar clock.
    pub fn get_datetime(&mut self) -> Result<DateTime, I2C::Error> {
        let mut buf = [0u8; 7];
        read_regs(&mut self.i2c, self.addr, REG_SECONDS, &mut buf)?;
        Ok(DateTime {
            second: bcd_to_bin(buf[0] & 0x7F),
            minute: bcd_to_bin(buf[1] & 0x7F),
            hour: bcd_to_bin(buf[2] & 0x3F),
            weekday: bcd_to_bin(buf[3] & 0x07),
            day: bcd_to_bin(buf[4] & 0x3F),
            month: bcd_to_bin(buf[5] & 0x1F),
            year: 2000 + bcd_to_bin(buf[6]) as u16,
        })
    }

    /// Set the calendar clock. Writes all seven clock/calendar registers in
    /// one burst (24-hour mode forced), then clears `OSF` — the time is now
    /// known-good.
    pub fn set_datetime(&mut self, dt: DateTime) -> Result<(), I2C::Error> {
        let year2 = (dt.year % 100) as u8;
        let buf = [
            REG_SECONDS,
            bin_to_bcd(dt.second),
            bin_to_bcd(dt.minute),
            bin_to_bcd(dt.hour) & 0x3F,
            bin_to_bcd(dt.weekday),
            bin_to_bcd(dt.day),
            bin_to_bcd(dt.month) & 0x1F,
            bin_to_bcd(year2),
        ];
        self.i2c.write(self.addr, &buf)?;
        let status = read_reg8(&mut self.i2c, self.addr, REG_CONTROL_STATUS)?;
        write_reg(&mut self.i2c, self.addr, REG_CONTROL_STATUS, status & !STATUS_OSF)
    }

    /// Read the last completed temperature conversion, in °C.
    ///
    /// No wait — the chip converts autonomously every 64 s and on power-up,
    /// so this may be up to 64 s stale. See [`Ds3231Full::force_temperature_conversion`]
    /// for an on-demand reading.
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        let mut buf = [0u8; 2];
        read_regs(&mut self.i2c, self.addr, REG_TEMP_MSB, &mut buf)?;
        let msb = buf[0] as i8 as f32;
        let frac = ((buf[1] >> 6) & 0x03) as f32 * 0.25;
        Ok(msb + frac)
    }
}

/// DS3231 full driver — extends minimal with alarms, square-wave output,
/// 32 kHz output, oscillator control, forced temperature conversion, aging
/// offset trim, and the interrupt API.
pub struct Ds3231Full<I2C> {
    inner: Ds3231Minimal<I2C>,
}

impl<I2C: I2c> Ds3231Full<I2C> {
    /// Create a new `Ds3231Full`.
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Ds3231Minimal::new(i2c, addr)? })
    }

    /// Read the current calendar clock. Delegates to [`Ds3231Minimal::get_datetime`].
    pub fn get_datetime(&mut self) -> Result<DateTime, I2C::Error> {
        self.inner.get_datetime()
    }

    /// Set the calendar clock. Delegates to [`Ds3231Minimal::set_datetime`].
    pub fn set_datetime(&mut self, dt: DateTime) -> Result<(), I2C::Error> {
        self.inner.set_datetime(dt)
    }

    /// Read the last completed temperature conversion, in °C. Delegates to
    /// [`Ds3231Minimal::read_temperature`].
    pub fn read_temperature(&mut self) -> Result<f32, I2C::Error> {
        self.inner.read_temperature()
    }

    /// Read Alarm 1 (`second`, `minute`, `hour`, `day_or_date`, match mode).
    ///
    /// `day_or_date` is a day-of-week (`1`-`7`) or day-of-month (`1`-`31`)
    /// depending on the returned [`Alarm1Match`] variant; it's meaningless
    /// for the first four (mask-only) variants.
    pub fn get_alarm1(&mut self) -> Result<(u8, u8, u8, u8, Alarm1Match), I2C::Error> {
        let mut buf = [0u8; 4];
        read_regs(&mut self.inner.i2c, self.inner.addr, REG_ALARM1_SECONDS, &mut buf)?;
        let second = bcd_to_bin(buf[0] & 0x7F);
        let minute = bcd_to_bin(buf[1] & 0x7F);
        let hour = bcd_to_bin(buf[2] & 0x3F);
        let dydt = (buf[3] & 0x40) != 0;
        let day_or_date = bcd_to_bin(buf[3] & 0x3F);
        let mode = decode_alarm1_mask(
            (buf[3] & 0x80) != 0,
            (buf[2] & 0x80) != 0,
            (buf[1] & 0x80) != 0,
            (buf[0] & 0x80) != 0,
            dydt,
        );
        Ok((second, minute, hour, day_or_date, mode))
    }

    /// Set Alarm 1. `mode` selects which of `second`/`minute`/`hour`/
    /// `day_or_date` participate in the match (see [`Alarm1Match`]).
    pub fn set_alarm1(&mut self, second: u8, minute: u8, hour: u8, day_or_date: u8, mode: Alarm1Match) -> Result<(), I2C::Error> {
        let (a1m1, a1m2, a1m3, a1m4, dydt) = match mode {
            Alarm1Match::EverySecond => (true, true, true, true, false),
            Alarm1Match::Seconds => (false, true, true, true, false),
            Alarm1Match::MinutesSeconds => (false, false, true, true, false),
            Alarm1Match::HoursMinutesSeconds => (false, false, false, true, false),
            Alarm1Match::DateHoursMinutesSeconds => (false, false, false, false, false),
            Alarm1Match::DayHoursMinutesSeconds => (false, false, false, false, true),
        };
        let buf = [
            REG_ALARM1_SECONDS,
            bin_to_bcd(second) | if a1m1 { 0x80 } else { 0 },
            bin_to_bcd(minute) | if a1m2 { 0x80 } else { 0 },
            (bin_to_bcd(hour) & 0x3F) | if a1m3 { 0x80 } else { 0 },
            (bin_to_bcd(day_or_date) & 0x3F) | if dydt { 0x40 } else { 0 } | if a1m4 { 0x80 } else { 0 },
        ];
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    /// Read Alarm 2 (`minute`, `hour`, `day_or_date`, match mode).
    pub fn get_alarm2(&mut self) -> Result<(u8, u8, u8, Alarm2Match), I2C::Error> {
        let mut buf = [0u8; 3];
        read_regs(&mut self.inner.i2c, self.inner.addr, REG_ALARM2_MINUTES, &mut buf)?;
        let minute = bcd_to_bin(buf[0] & 0x7F);
        let hour = bcd_to_bin(buf[1] & 0x3F);
        let dydt = (buf[2] & 0x40) != 0;
        let day_or_date = bcd_to_bin(buf[2] & 0x3F);
        let mode = decode_alarm2_mask((buf[2] & 0x80) != 0, (buf[1] & 0x80) != 0, (buf[0] & 0x80) != 0, dydt);
        Ok((minute, hour, day_or_date, mode))
    }

    /// Set Alarm 2. `mode` selects which of `minute`/`hour`/`day_or_date`
    /// participate in the match (see [`Alarm2Match`]).
    pub fn set_alarm2(&mut self, minute: u8, hour: u8, day_or_date: u8, mode: Alarm2Match) -> Result<(), I2C::Error> {
        let (a2m2, a2m3, a2m4, dydt) = match mode {
            Alarm2Match::EveryMinute => (true, true, true, false),
            Alarm2Match::Minutes => (false, true, true, false),
            Alarm2Match::HoursMinutes => (false, false, true, false),
            Alarm2Match::DateHoursMinutes => (false, false, false, false),
            Alarm2Match::DayHoursMinutes => (false, false, false, true),
        };
        let buf = [
            REG_ALARM2_MINUTES,
            bin_to_bcd(minute) | if a2m2 { 0x80 } else { 0 },
            (bin_to_bcd(hour) & 0x3F) | if a2m3 { 0x80 } else { 0 },
            (bin_to_bcd(day_or_date) & 0x3F) | if dydt { 0x40 } else { 0 } | if a2m4 { 0x80 } else { 0 },
        ];
        self.inner.i2c.write(self.inner.addr, &buf)
    }

    /// Enable the square-wave output on `INT/SQW`.
    ///
    /// Sets `INTCN`=0, the rate-select bits per `rate_hz` (`1`/`1024`/`4096`/
    /// `8192`, default `8192` for any other value), and `BBSQW`=`battery_backed`.
    /// Mutually exclusive with alarm interrupts — shares the `INTCN` bit with
    /// [`Self::enable_interrupt`]; whichever call happens last wins.
    pub fn enable_square_wave(&mut self, rate_hz: u32, battery_backed: bool) -> Result<(), I2C::Error> {
        let rs = match rate_hz {
            1 => 0x00,
            1024 => 0x08,
            4096 => 0x10,
            _ => 0x18,
        };
        let mut ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        ctrl &= !(CONTROL_INTCN | CONTROL_RS_MASK | CONTROL_BBSQW);
        ctrl |= rs;
        if battery_backed {
            ctrl |= CONTROL_BBSQW;
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)
    }

    /// Disable the square wave, returning `INT/SQW` to interrupt mode (`INTCN`=1).
    pub fn disable_square_wave(&mut self) -> Result<(), I2C::Error> {
        let mut ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        ctrl |= CONTROL_INTCN;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)
    }

    /// Whether the separate `32kHz` pin output is enabled.
    pub fn is_32khz_enabled(&mut self) -> Result<bool, I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS)?;
        Ok((status & STATUS_EN32KHZ) != 0)
    }

    /// Enable the separate `32kHz` pin output.
    pub fn enable_32khz_output(&mut self) -> Result<(), I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS, status | STATUS_EN32KHZ)
    }

    /// Disable the separate `32kHz` pin output.
    pub fn disable_32khz_output(&mut self) -> Result<(), I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS, status & !STATUS_EN32KHZ)
    }

    /// Whether the oscillator has stopped since the last check (`OSF`) —
    /// `true` means timekeeping data may be invalid.
    pub fn oscillator_stopped(&mut self) -> Result<bool, I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS)?;
        Ok((status & STATUS_OSF) != 0)
    }

    /// Clear the Oscillator Stop Flag, preserving `EN32kHz`.
    pub fn clear_oscillator_stopped(&mut self) -> Result<(), I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS, status & !STATUS_OSF)
    }

    /// Keep the oscillator running on `VBAT` (clears `EOSC`; power-on default).
    pub fn enable_battery_oscillator(&mut self) -> Result<(), I2C::Error> {
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl & !0x80)
    }

    /// Stop the oscillator when switched to `VBAT` (sets `EOSC`), saving
    /// battery current.
    pub fn disable_battery_oscillator(&mut self) -> Result<(), I2C::Error> {
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl | 0x80)
    }

    /// Force an immediate temperature conversion and wait for it to
    /// complete (max 200 ms; see `specs/rtc/ds3231.md`'s Timing Constraints).
    ///
    /// Afterward, [`Self::read_temperature`] returns the fresh reading.
    pub fn force_temperature_conversion<D: DelayNs>(&mut self, delay: &mut D) -> Result<(), I2C::Error> {
        let ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl | CONTROL_CONV)?;
        for _ in 0..20 {
            let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS)?;
            if (status & STATUS_BSY) == 0 {
                return Ok(());
            }
            delay.delay_ms(10);
        }
        Ok(())
    }

    /// Raw signed oscillator trim code from `AGING_OFFSET`. Not a physically
    /// scaled unit — see `specs/rtc/ds3231.md`'s Implementation Notes.
    pub fn get_aging_offset(&mut self) -> Result<i8, I2C::Error> {
        let raw = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_AGING_OFFSET)?;
        Ok(raw as i8)
    }

    /// Write the raw signed oscillator trim code to `AGING_OFFSET`.
    pub fn set_aging_offset(&mut self, offset: i8) -> Result<(), I2C::Error> {
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_AGING_OFFSET, offset as u8)
    }

    /// Read `CONTROL_STATUS` and clear `A1F`/`A2F` (leaving `OSF`/`EN32kHz`/
    /// `BSY` untouched); returns the pre-clear byte — mask with
    /// [`SOURCE_ALARM1`]/[`SOURCE_ALARM2`] to test each source.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        let status = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL_STATUS)?;
        write_reg(
            &mut self.inner.i2c,
            self.inner.addr,
            REG_CONTROL_STATUS,
            status & !(STATUS_A1F | STATUS_A2F),
        )?;
        Ok(status)
    }

    /// Enable an alarm to assert `INT/SQW` (sets `A1IE`/`A2IE` and `INTCN`=1).
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let mut ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        ctrl |= CONTROL_INTCN;
        if source & SOURCE_ALARM1 != 0 {
            ctrl |= CONTROL_A1IE;
        }
        if source & SOURCE_ALARM2 != 0 {
            ctrl |= CONTROL_A2IE;
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)
    }

    /// Disable an alarm's ability to assert `INT/SQW` (clears `A1IE`/`A2IE`;
    /// leaves `INTCN` untouched).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let mut ctrl = read_reg8(&mut self.inner.i2c, self.inner.addr, REG_CONTROL)?;
        if source & SOURCE_ALARM1 != 0 {
            ctrl &= !CONTROL_A1IE;
        }
        if source & SOURCE_ALARM2 != 0 {
            ctrl &= !CONTROL_A2IE;
        }
        write_reg(&mut self.inner.i2c, self.inner.addr, REG_CONTROL, ctrl)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x68;

    #[test]
    fn minimal_datetime_and_temperature() {
        let transactions = vec![
            // new(): presence check reads CONTROL.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![0x1C]),
            // set_datetime(2026-09-22 Tue 14:30:00, weekday=2): burst write.
            I2cTransaction::write(ADDR, vec![
                REG_SECONDS, 0x00, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26,
            ]),
            // set_datetime clears OSF: read CONTROL_STATUS then write back.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_STATUS], vec![0x88]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_STATUS, 0x08]),
            // get_datetime(): burst read of 7 bytes.
            I2cTransaction::write_read(ADDR, vec![REG_SECONDS], vec![0x00, 0x30, 0x14, 0x02, 0x22, 0x09, 0x26]),
            // read_temperature(): 25.25 C -> MSB=0x19, LSB bits7:6=01 (0.25).
            I2cTransaction::write_read(ADDR, vec![REG_TEMP_MSB], vec![0x19, 0x40]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut rtc = Ds3231Minimal::new(i2c.clone(), ADDR).expect("init");

        rtc.set_datetime(DateTime { year: 2026, month: 9, day: 22, weekday: 2, hour: 14, minute: 30, second: 0 })
            .expect("set_datetime");

        let dt = rtc.get_datetime().expect("get_datetime");
        assert_eq!(dt, DateTime { year: 2026, month: 9, day: 22, weekday: 2, hour: 14, minute: 30, second: 0 });

        let temp = rtc.read_temperature().expect("read_temperature");
        assert!((temp - 25.25).abs() < 1e-6);
        i2c.done();
    }

    #[test]
    fn full_alarms_and_interrupts() {
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![0x1C]),
            // set_alarm1(0, 0, 0, 0, EverySecond): all four mask bits set.
            I2cTransaction::write(ADDR, vec![REG_ALARM1_SECONDS, 0x80, 0x80, 0x80, 0x80]),
            // get_alarm1() for the EverySecond write above.
            I2cTransaction::write_read(ADDR, vec![REG_ALARM1_SECONDS], vec![0x80, 0x80, 0x80, 0x80]),
            // set_alarm1(30, 15, 9, 0, HoursMinutesSeconds): only A1M4 (bit7
            // of the DAY_DATE byte) set; distinguishes field ordering from
            // EverySecond, which would otherwise mask an argument-order bug.
            I2cTransaction::write(ADDR, vec![REG_ALARM1_SECONDS, 0x30, 0x15, 0x09, 0x80]),
            I2cTransaction::write_read(ADDR, vec![REG_ALARM1_SECONDS], vec![0x30, 0x15, 0x09, 0x80]),
            // enable_interrupt(SOURCE_ALARM1): read CONTROL, write back with INTCN|A1IE.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![0x1C]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL, 0x1D]),
            // poll_interrupt(): A1F set, clears it.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL_STATUS], vec![0x09]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL_STATUS, 0x08]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut rtc = Ds3231Full::new(i2c.clone(), ADDR).expect("init");

        rtc.set_alarm1(0, 0, 0, 0, Alarm1Match::EverySecond).expect("set_alarm1");
        let (_, _, _, _, mode) = rtc.get_alarm1().expect("get_alarm1");
        assert_eq!(mode, Alarm1Match::EverySecond);

        rtc.set_alarm1(30, 15, 9, 0, Alarm1Match::HoursMinutesSeconds).expect("set_alarm1");
        let (second, minute, hour, _, mode) = rtc.get_alarm1().expect("get_alarm1");
        assert_eq!(mode, Alarm1Match::HoursMinutesSeconds);
        assert_eq!((second, minute, hour), (30, 15, 9));

        rtc.enable_interrupt(SOURCE_ALARM1).expect("enable_interrupt");

        let status = rtc.poll_interrupt().expect("poll_interrupt");
        assert_eq!(status & SOURCE_ALARM1, SOURCE_ALARM1);
        i2c.done();
    }
}
