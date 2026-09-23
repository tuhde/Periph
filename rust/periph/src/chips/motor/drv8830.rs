//! DRV8830 — Low-voltage motor driver with I²C interface (Texas Instruments).
//!
//! H-bridge driver for a single brushed DC motor, controlled entirely over
//! I²C — there are no PWM/direction pins. The host commands a target output
//! *voltage*; the chip PWM-regulates the bridge to hold that average voltage
//! across the winding regardless of supply sag. Nine selectable addresses
//! (`0x60`–`0x68`) via the tri-state `A0`/`A1` strap pins.
//!
//! Voltage conversion follows the datasheet's Table 1:
//! `voltage = VREF * vset / 16` with `VREF` = 1.285 V (typical, ±4%), usable
//! range 0.48 V (`vset` 6) to 5.06 V (`vset` 63) — see
//! `specs/motor/drv8830.md`.
//!
//! ## Interrupts
//!
//! Rust exposes only [`Drv8830Full::poll_interrupt`] (no callback
//! subscription — polling is always caller-managed in this crate's `no_std`
//! Rust drivers). Faults are never cleared implicitly: a latched OCP/ILIMIT
//! fault also disables the H-bridge, so clearing is always an explicit
//! [`Drv8830Full::clear_fault`].

use embedded_hal::i2c::I2c;

const REG_CONTROL: u8 = 0x00;
const REG_FAULT: u8 = 0x01;

const CTRL_IN1: u8 = 0x01;
const CTRL_IN2: u8 = 0x02;

const FAULT_FAULT: u8 = 0x01;
const FAULT_OCP: u8 = 0x02;
const FAULT_UVLO: u8 = 0x04;
const FAULT_OTS: u8 = 0x08;
const FAULT_ILIMIT: u8 = 0x10;
const FAULT_CLEAR: u8 = 0x80;

/// Default 7-bit I²C address (`A0` = `A1` = GND). Valid range `0x60`–`0x68`.
pub const DRV8830_I2C_ADDRESS: u8 = 0x60;
/// Internal reference voltage, typical (datasheet: 1.235–1.335 V).
pub const DRV8830_VREF: f32 = 1.285;
/// Lowest valid `VSET` code (`0x00`–`0x05` are reserved).
pub const DRV8830_VSET_MIN: u8 = 6;
/// Highest `VSET` code (≈5.06 V).
pub const DRV8830_VSET_MAX: u8 = 63;

/// H-bridge state decoded from `IN1`/`IN2`.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Direction {
    /// `IN1`=0, `IN2`=0 — outputs high-Z (standby).
    Coast,
    /// `IN1`=1, `IN2`=0.
    Forward,
    /// `IN1`=0, `IN2`=1.
    Reverse,
    /// `IN1`=1, `IN2`=1 — both outputs high.
    Brake,
}

/// Decoded `CONTROL` register, returned by [`Drv8830Full::read_output`].
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Output {
    /// Commanded magnitude in V (`0.0` for a reserved `VSET` code).
    pub voltage: f32,
    /// H-bridge state.
    pub direction: Direction,
}

/// Decoded `FAULT` register, returned by [`Drv8830Full::read_fault`].
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct Fault {
    /// Any fault condition exists.
    pub fault: bool,
    /// Overcurrent (short-circuit) event.
    pub ocp: bool,
    /// Undervoltage lockout.
    pub uvlo: bool,
    /// Overtemperature shutdown.
    pub ots: bool,
    /// Extended current-limit event.
    pub ilimit: bool,
}

/// Errors from [`Drv8830Full::set_output`].
#[derive(Debug)]
pub enum Drv8830Error<E> {
    /// The underlying I²C bus returned an error.
    Bus(E),
    /// `vset` was outside `6`–`63` (codes `0`–`5` are reserved).
    InvalidVset,
}

impl<E> From<E> for Drv8830Error<E> {
    fn from(e: E) -> Self {
        Drv8830Error::Bus(e)
    }
}

/// Map |voltage| to a `VSET` code; `0` means coast (below the `vset`=6 floor).
fn voltage_to_vset(voltage: f32) -> u8 {
    let mag = if voltage < 0.0 { -voltage } else { voltage };
    let vset = (mag * 16.0 / DRV8830_VREF + 0.5) as u32;
    if vset < DRV8830_VSET_MIN as u32 {
        0
    } else if vset > DRV8830_VSET_MAX as u32 {
        DRV8830_VSET_MAX
    } else {
        vset as u8
    }
}

fn vset_to_voltage(vset: u8) -> f32 {
    if vset < DRV8830_VSET_MIN {
        0.0
    } else {
        DRV8830_VREF * vset as f32 / 16.0
    }
}

/// DRV8830 — minimal interface: drive at a regulated voltage, brake, coast.
pub struct Drv8830Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> Drv8830Minimal<I2C> {
    /// Construct the driver and confirm the device answers (one `CONTROL`
    /// read; the chip has no identity register). Makes no register writes —
    /// the POR default already leaves the motor in standby/coast.
    ///
    /// `addr` is `0x60`–`0x68` per the board's `A0`/`A1` strapping.
    pub fn new(mut i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        let mut buf = [0u8; 1];
        i2c.write_read(addr, &[REG_CONTROL], &mut buf)?;
        Ok(Self { i2c, addr })
    }

    fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        self.i2c.write(self.addr, &[reg, value])
    }

    fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        self.i2c.write_read(self.addr, &[reg], &mut buf)?;
        Ok(buf[0])
    }

    /// Drive the motor at a regulated output voltage.
    ///
    /// `voltage` is signed, in V: positive = forward, negative = reverse,
    /// `0.0` = standby/coast. Writes `VSET` and `IN1`/`IN2` together in one
    /// `CONTROL` write; a magnitude below the ~0.48 V floor coasts, above
    /// ~5.06 V it is clamped.
    pub fn drive(&mut self, voltage: f32) -> Result<(), I2C::Error> {
        let vset = voltage_to_vset(voltage);
        let value = if vset == 0 {
            0x00
        } else if voltage > 0.0 {
            (vset << 2) | CTRL_IN1
        } else {
            (vset << 2) | CTRL_IN2
        };
        self.write_reg(REG_CONTROL, value)
    }

    /// Short-brake the motor (`IN1` = `IN2` = 1, both outputs high).
    pub fn brake(&mut self) -> Result<(), I2C::Error> {
        self.write_reg(REG_CONTROL, CTRL_IN1 | CTRL_IN2)
    }

    /// Put the bridge in standby/coast (`IN1` = `IN2` = 0) — same as `drive(0.0)`.
    pub fn stop(&mut self) -> Result<(), I2C::Error> {
        self.write_reg(REG_CONTROL, 0x00)
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.i2c
    }
}

/// DRV8830 — full interface: extends [`Drv8830Minimal`] with raw `CONTROL`
/// access, output read-back, fault reporting/clearing, and fault polling.
pub struct Drv8830Full<I2C> {
    inner: Drv8830Minimal<I2C>,
}

impl<I2C: I2c> Drv8830Full<I2C> {
    /// Construct the driver; same presence check as [`Drv8830Minimal::new`].
    pub fn new(i2c: I2C, addr: u8) -> Result<Self, I2C::Error> {
        Ok(Self { inner: Drv8830Minimal::new(i2c, addr)? })
    }

    /// Drive at a regulated voltage. Delegates to [`Drv8830Minimal::drive`].
    pub fn drive(&mut self, voltage: f32) -> Result<(), I2C::Error> {
        self.inner.drive(voltage)
    }

    /// Short-brake. Delegates to [`Drv8830Minimal::brake`].
    pub fn brake(&mut self) -> Result<(), I2C::Error> {
        self.inner.brake()
    }

    /// Standby/coast. Delegates to [`Drv8830Minimal::stop`].
    pub fn stop(&mut self) -> Result<(), I2C::Error> {
        self.inner.stop()
    }

    /// Write the `CONTROL` register from raw fields.
    ///
    /// `vset` must be `6`–`63`; reserved codes return
    /// [`Drv8830Error::InvalidVset`] without touching the bus.
    pub fn set_output(&mut self, vset: u8, in1: bool, in2: bool) -> Result<(), Drv8830Error<I2C::Error>> {
        if !(DRV8830_VSET_MIN..=DRV8830_VSET_MAX).contains(&vset) {
            return Err(Drv8830Error::InvalidVset);
        }
        let value = (vset << 2) | if in1 { CTRL_IN1 } else { 0 } | if in2 { CTRL_IN2 } else { 0 };
        self.inner.write_reg(REG_CONTROL, value)?;
        Ok(())
    }

    /// Read back and decode the `CONTROL` register.
    pub fn read_output(&mut self) -> Result<Output, I2C::Error> {
        let ctrl = self.inner.read_reg(REG_CONTROL)?;
        let direction = match ctrl & 0x03 {
            0 => Direction::Coast,
            1 => Direction::Forward,
            2 => Direction::Reverse,
            _ => Direction::Brake,
        };
        Ok(Output { voltage: vset_to_voltage(ctrl >> 2), direction })
    }

    /// Read the `FAULT` register without clearing it.
    pub fn read_fault(&mut self) -> Result<Fault, I2C::Error> {
        let f = self.inner.read_reg(REG_FAULT)?;
        Ok(Fault {
            fault: f & FAULT_FAULT != 0,
            ocp: f & FAULT_OCP != 0,
            uvlo: f & FAULT_UVLO != 0,
            ots: f & FAULT_OTS != 0,
            ilimit: f & FAULT_ILIMIT != 0,
        })
    }

    /// Clear all fault status bits (`CLEAR` = 1); re-enables the H-bridge if
    /// an OCP/ILIMIT fault had latched it off.
    pub fn clear_fault(&mut self) -> Result<(), I2C::Error> {
        self.inner.write_reg(REG_FAULT, FAULT_CLEAR)
    }

    /// Read the fault status — equivalent to [`Self::read_fault`], does not clear.
    pub fn poll_interrupt(&mut self) -> Result<Fault, I2C::Error> {
        self.read_fault()
    }

    /// Consume the driver and return the underlying I²C bus.
    pub fn release(self) -> I2C {
        self.inner.release()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

    const ADDR: u8 = 0x60;

    #[test]
    fn minimal_drive_brake_stop() {
        let transactions = vec![
            // new(): presence check reads CONTROL.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![0x00]),
            // drive(3.0): VSET 37, forward.
            I2cTransaction::write(ADDR, vec![REG_CONTROL, (37 << 2) | 0x01]),
            // drive(-2.0): VSET 25, reverse.
            I2cTransaction::write(ADDR, vec![REG_CONTROL, (25 << 2) | 0x02]),
            // drive(0.4): below the floor -> coast.
            I2cTransaction::write(ADDR, vec![REG_CONTROL, 0x00]),
            // drive(0.48): VSET 6.
            I2cTransaction::write(ADDR, vec![REG_CONTROL, (6 << 2) | 0x01]),
            // drive(9.0): clamped to VSET 63.
            I2cTransaction::write(ADDR, vec![REG_CONTROL, (63 << 2) | 0x01]),
            // brake(), stop().
            I2cTransaction::write(ADDR, vec![REG_CONTROL, 0x03]),
            I2cTransaction::write(ADDR, vec![REG_CONTROL, 0x00]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut motor = Drv8830Minimal::new(i2c.clone(), ADDR).expect("init");
        motor.drive(3.0).expect("drive fwd");
        motor.drive(-2.0).expect("drive rev");
        motor.drive(0.4).expect("drive floor");
        motor.drive(0.48).expect("drive vset6");
        motor.drive(9.0).expect("drive clamp");
        motor.brake().expect("brake");
        motor.stop().expect("stop");
        i2c.done();
    }

    #[test]
    fn full_output_and_faults() {
        let transactions = vec![
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![0x00]),
            // set_output(20, false, true).
            I2cTransaction::write(ADDR, vec![REG_CONTROL, (20 << 2) | 0x02]),
            // set_output(5, ..) is rejected without a bus transaction.
            // read_output(): VSET 63 forward, VSET 16 reverse, brake.
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![(63 << 2) | 0x01]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![(16 << 2) | 0x02]),
            I2cTransaction::write_read(ADDR, vec![REG_CONTROL], vec![0x03]),
            // read_fault(): FAULT + ILIMIT; poll_interrupt(): FAULT + OCP + UVLO + OTS.
            I2cTransaction::write_read(ADDR, vec![REG_FAULT], vec![0x11]),
            I2cTransaction::write_read(ADDR, vec![REG_FAULT], vec![0x0F]),
            // clear_fault().
            I2cTransaction::write(ADDR, vec![REG_FAULT, 0x80]),
        ];
        let mut i2c = I2cMock::new(&transactions);
        let mut motor = Drv8830Full::new(i2c.clone(), ADDR).expect("init");

        motor.set_output(20, false, true).expect("set_output");
        assert!(matches!(motor.set_output(5, true, false), Err(Drv8830Error::InvalidVset)));

        let o = motor.read_output().expect("read_output");
        assert_eq!(o.direction, Direction::Forward);
        assert!((o.voltage - 5.06).abs() < 0.01);
        let o = motor.read_output().expect("read_output");
        assert_eq!(o.direction, Direction::Reverse);
        assert!((o.voltage - 1.285).abs() < 0.001);
        let o = motor.read_output().expect("read_output");
        assert_eq!(o, Output { voltage: 0.0, direction: Direction::Brake });

        assert_eq!(
            motor.read_fault().expect("read_fault"),
            Fault { fault: true, ocp: false, uvlo: false, ots: false, ilimit: true }
        );
        assert_eq!(
            motor.poll_interrupt().expect("poll"),
            Fault { fault: true, ocp: true, uvlo: true, ots: true, ilimit: false }
        );
        motor.clear_fault().expect("clear_fault");
        i2c.done();
    }
}
