//! PCA9541 — 2-to-1 I²C-bus master selector (NXP).
//!
//! Two upstream masters share one downstream bus of slave devices through the
//! PCA9541's pass gates; this driver is used by **one** of the two masters (the
//! same software runs on both). There is no arbitration: whichever master writes
//! its CONTROL register last before a STOP wins. Each master sees its own copy of
//! the IE, CONTROL and ISTAT registers behind the same addresses. The 7-bit I²C
//! address is `0x70` + A (A3..A0); prefer `0x70`–`0x77`.
//!
//! The driver uses register access (1-byte command code: `0x00` IE, `0x01`
//! CONTROL, `0x02` ISTAT, `+0x10` for auto-increment). The CONTROL byte to write
//! depends on the read-only complement bits of the master's own register, so
//! every change reads CONTROL first and applies the datasheet's Table 12 rule.
//! A delay provider is needed only for [`PCA9541Minimal::acquire`] (the wait for
//! the switch).

use embedded_hal::delay::DelayNs;
use embedded_hal::i2c::I2c;

use crate::connection::register;

/// 7-bit I²C address with A3..A0 = 0.
pub const PCA9541_I2C_ADDRESS: u8 = 0x70;

/// ISTAT bit 0 / IE mask bit 0: downstream interrupt on `INT_IN`.
pub const PCA9541_SOURCE_INT_IN: u8 = 0x01;
/// ISTAT bit 1 / IE mask bit 1: requested bus initialization finished.
pub const PCA9541_SOURCE_BUS_INIT: u8 = 0x02;
/// ISTAT bit 2 / IE mask bit 2: downstream bus was not idle at the switch.
pub const PCA9541_SOURCE_BUS_NOT_IDLE: u8 = 0x04;
/// ISTAT bit 3 / IE mask bit 3: the other master took the bus.
pub const PCA9541_SOURCE_BUS_LOST: u8 = 0x08;
/// ISTAT bit 6: this master's own functional test interrupt.
pub const PCA9541_SOURCE_TEST: u8 = 0x40;
/// ISTAT bit 7: the other master's functional test interrupt.
pub const PCA9541_SOURCE_OTHER_TEST: u8 = 0x80;

const REG_IE: u8 = 0x00;
const REG_CONTROL: u8 = 0x01;
const REG_ISTAT: u8 = 0x02;
const AI: u8 = 0x10;

const C_MYBUS: u8 = 0x01;
const C_NMYBUS: u8 = 0x02;
const C_BUSON: u8 = 0x04;
const C_NBUSON: u8 = 0x08;
const C_BUSINIT: u8 = 0x10;
const C_TESTON: u8 = 0x40;
const C_NTESTON: u8 = 0x80;

fn has_control_bits(c: u8) -> bool {
    ((c & C_MYBUS) != 0) == ((c & C_NMYBUS) != 0)
}

fn connected_bits(c: u8) -> bool {
    ((c & C_BUSON) != 0) != ((c & C_NBUSON) != 0)
}

fn nbuson(c: u8) -> u8 {
    u8::from(c & C_NBUSON != 0)
}

fn nmybus(c: u8) -> u8 {
    u8::from(c & C_NMYBUS != 0)
}

/// Decoded CONTROL register.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PCA9541Status {
    /// `MYBUS` equals `NMYBUS` (control, regardless of the connection).
    pub has_control: bool,
    /// `BUSON` differs from `NBUSON` (some master is connected).
    pub connected: bool,
    /// `BUSINIT` is set.
    pub bus_init_requested: bool,
    /// `TESTON`: this master's INT is driven low.
    pub own_test: bool,
    /// `NTESTON`: the other master's INT is driven low.
    pub other_test: bool,
}

/// PCA9541 — minimal interface: take, give up and check ownership of the
/// downstream bus.
pub struct PCA9541Minimal<I2C> {
    i2c: I2C,
    addr: u8,
}

impl<I2C: I2c> PCA9541Minimal<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once).
    /// `addr` is `0x70`–`0x77` ([`PCA9541_I2C_ADDRESS`]).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { i2c, addr }
    }

    pub(crate) fn read_reg(&mut self, reg: u8) -> Result<u8, I2C::Error> {
        let mut buf = [0u8; 1];
        register::read_register(&mut self.i2c, self.addr, reg.into(), 1, &mut buf)?;
        Ok(buf[0])
    }

    pub(crate) fn write_reg(&mut self, reg: u8, value: u8) -> Result<(), I2C::Error> {
        register::write_register(&mut self.i2c, self.addr, reg.into(), 1, &[value])
    }

    /// Run the initialization sequence: read CONTROL as a bus check (the error is
    /// returned if the chip does not acknowledge) and ISTAT once to clear stale
    /// flags. Writes nothing.
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        self.read_reg(REG_CONTROL)?;
        self.read_reg(REG_ISTAT)?;
        Ok(())
    }

    /// Whether this master's channel is connected to the downstream bus
    /// (`MYBUS` equals `NMYBUS` and `BUSON` differs from `NBUSON`).
    pub fn has_control(&mut self) -> Result<bool, I2C::Error> {
        let c = self.read_reg(REG_CONTROL)?;
        Ok(has_control_bits(c) && connected_bits(c))
    }

    /// Take the downstream bus. Reads CONTROL and writes `MYBUS` / `BUSON` per the
    /// datasheet rule; the write ends with a STOP, which makes the switch. With
    /// `bus_init` the chip first sends nine clocks, a NACK and a STOP on the
    /// downstream bus and flags `BUSINIT` in ISTAT. Returns `true` if this master
    /// has control afterwards, `false` if not within `timeout_ms`.
    pub fn acquire<D: DelayNs>(&mut self, delay: &mut D, bus_init: bool, timeout_ms: u32) -> Result<bool, I2C::Error> {
        let c = self.read_reg(REG_CONTROL)?;
        let mut w = ((1 - nbuson(c)) << 2) | nmybus(c);
        if bus_init {
            w |= C_BUSINIT;
        }
        self.write_reg(REG_CONTROL, w)?;
        for _ in 0..=timeout_ms {
            if bus_init && self.read_reg(REG_ISTAT)? & 0x02 == 0 {
                delay.delay_ms(1);
                continue;
            }
            if self.has_control()? {
                return Ok(true);
            }
            delay.delay_ms(1);
        }
        Ok(false)
    }

    /// Give the downstream bus to the other master (`MYBUS` = NOT `NMYBUS`; the
    /// bus stays connected).
    pub fn release(&mut self) -> Result<(), I2C::Error> {
        let c = self.read_reg(REG_CONTROL)?;
        self.write_reg(REG_CONTROL, ((1 - nbuson(c)) << 2) | (1 - nmybus(c)))
    }
}

/// PCA9541 — full interface: raw control access, connect / disconnect, bus
/// recovery, interrupt masks, the INT functional test and ISTAT polling.
pub struct PCA9541Full<I2C> {
    inner: PCA9541Minimal<I2C>,
}

impl<I2C: I2c> PCA9541Full<I2C> {
    /// Construct the driver; performs no I²C traffic (call [`Self::init`] once).
    pub fn new(i2c: I2C, addr: u8) -> Self {
        Self { inner: PCA9541Minimal::new(i2c, addr) }
    }

    /// Run the initialization sequence. See [`PCA9541Minimal::init`].
    pub fn init(&mut self) -> Result<(), I2C::Error> {
        self.inner.init()
    }

    /// See [`PCA9541Minimal::has_control`].
    pub fn has_control(&mut self) -> Result<bool, I2C::Error> {
        self.inner.has_control()
    }

    /// See [`PCA9541Minimal::acquire`].
    pub fn acquire<D: DelayNs>(&mut self, delay: &mut D, bus_init: bool, timeout_ms: u32) -> Result<bool, I2C::Error> {
        self.inner.acquire(delay, bus_init, timeout_ms)
    }

    /// See [`PCA9541Minimal::release`].
    pub fn release(&mut self) -> Result<(), I2C::Error> {
        self.inner.release()
    }

    /// Read the raw CONTROL byte.
    pub fn get_control(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_CONTROL)
    }

    /// Whether the downstream bus is connected to some master (`BUSON` differs
    /// from `NBUSON`).
    pub fn is_connected(&mut self) -> Result<bool, I2C::Error> {
        Ok(connected_bits(self.inner.read_reg(REG_CONTROL)?))
    }

    /// Decode the CONTROL register.
    pub fn status(&mut self) -> Result<PCA9541Status, I2C::Error> {
        let c = self.inner.read_reg(REG_CONTROL)?;
        Ok(PCA9541Status {
            has_control: has_control_bits(c),
            connected: connected_bits(c),
            bus_init_requested: c & C_BUSINIT != 0,
            own_test: c & C_TESTON != 0,
            other_test: c & C_NTESTON != 0,
        })
    }

    /// Turn the connection on without changing ownership (`BUSON` = NOT
    /// `NBUSON`; `MYBUS` as read).
    pub fn connect(&mut self) -> Result<(), I2C::Error> {
        let c = self.inner.read_reg(REG_CONTROL)?;
        self.inner.write_reg(REG_CONTROL, ((1 - nbuson(c)) << 2) | (c & C_MYBUS))
    }

    /// Disconnect both masters from the downstream bus (`BUSON` = `NBUSON`;
    /// ownership bits left).
    pub fn disconnect(&mut self) -> Result<(), I2C::Error> {
        let c = self.inner.read_reg(REG_CONTROL)?;
        self.inner.write_reg(REG_CONTROL, (nbuson(c) << 2) | (c & C_MYBUS))
    }

    /// Recover a hung downstream bus and take it ([`Self::acquire`] with bus
    /// initialization: nine clocks, a NACK and a STOP, then connect).
    pub fn recover_bus<D: DelayNs>(&mut self, delay: &mut D, timeout_ms: u32) -> Result<bool, I2C::Error> {
        self.inner.acquire(delay, true, timeout_ms)
    }

    /// Write the IE register (`true` = masked, no interrupt); all-`false`
    /// re-enables every interrupt.
    pub fn set_interrupt_mask(&mut self, bus_lost: bool, bus_not_idle: bool, bus_init: bool, int_in: bool) -> Result<(), I2C::Error> {
        let v = (u8::from(bus_lost) << 3) | (u8::from(bus_not_idle) << 2) | (u8::from(bus_init) << 1) | u8::from(int_in);
        self.inner.write_reg(REG_IE, v)
    }

    /// Read the IE register (bit set = interrupt masked).
    pub fn get_interrupt_mask(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_IE)
    }

    /// Drive the INT lines for the functional test: `own` drives this master's
    /// INT low (`TESTON`), `other` the other master's (`NTESTON`); both `false`
    /// clears the test. The bus bits are written back unchanged.
    pub fn test_interrupt(&mut self, own: bool, other: bool) -> Result<(), I2C::Error> {
        let c = self.inner.read_reg(REG_CONTROL)?;
        let w = (c & (C_BUSON | C_MYBUS)) | if own { C_TESTON } else { 0 } | if other { C_NTESTON } else { 0 };
        self.inner.write_reg(REG_CONTROL, w)
    }

    /// Read IE, CONTROL and ISTAT in one auto-increment transaction (clears the
    /// self-clearing ISTAT flags): `(ie, control, istat)`.
    pub fn read_all(&mut self) -> Result<(u8, u8, u8), I2C::Error> {
        let mut buf = [0u8; 3];
        register::read_register(&mut self.inner.i2c, self.inner.addr, (AI | REG_IE).into(), 1, &mut buf)?;
        Ok((buf[0], buf[1], buf[2]))
    }

    /// Enable an interrupt source (clear its mask bit in IE); the test sources
    /// need no enable.
    pub fn enable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let bit = source & 0x0F;
        if bit != 0 {
            let ie = self.inner.read_reg(REG_IE)?;
            self.inner.write_reg(REG_IE, ie & !bit)?;
        }
        Ok(())
    }

    /// Disable an interrupt source (set its mask bit in IE).
    pub fn disable_interrupt(&mut self, source: u8) -> Result<(), I2C::Error> {
        let bit = source & 0x0F;
        if bit != 0 {
            let ie = self.inner.read_reg(REG_IE)?;
            self.inner.write_reg(REG_IE, ie | bit)?;
        }
        Ok(())
    }

    /// Read ISTAT (clears `BUSLOST` / `BUSOK` / `BUSINIT`); bits as the
    /// `PCA9541_SOURCE_*` constants. Wire your own GPIO to this master's INT pin
    /// and call this from its falling-edge handler.
    pub fn poll_interrupt(&mut self) -> Result<u8, I2C::Error> {
        self.inner.read_reg(REG_ISTAT)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use embedded_hal::i2c::ErrorKind;
    use embedded_hal_mock::eh1::delay::NoopDelay;
    use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as T};

    const ADDR: u8 = PCA9541_I2C_ADDRESS;

    fn w(reg: u8, v: u8) -> T {
        T::write(ADDR, vec![reg, v])
    }

    fn r(reg: u8, v: u8) -> T {
        T::write_read(ADDR, vec![reg], vec![v])
    }

    fn init_t() -> Vec<T> {
        vec![r(1, 0x04), r(2, 0x00)]
    }

    fn full(extra: Vec<T>) -> (I2cMock, PCA9541Full<I2cMock>) {
        let mut t = init_t();
        t.extend(extra);
        let i2c = I2cMock::new(&t);
        let mut chip = PCA9541Full::new(i2c.clone(), ADDR);
        chip.init().unwrap();
        (i2c, chip)
    }

    #[test]
    fn init_reads_control_and_istat_and_writes_nothing() {
        let (mut i2c, _chip) = full(vec![]);
        i2c.done();
    }

    #[test]
    fn init_fails_when_not_acknowledged() {
        let mut i2c = I2cMock::new(&[r(1, 0).with_error(ErrorKind::Other)]);
        let mut chip = PCA9541Minimal::new(i2c.clone(), ADDR);
        assert!(chip.init().is_err());
        i2c.done();
    }

    #[test]
    fn acquire_follows_table_12() {
        let table: [(u8, u8); 12] = [(0x0, 0x4), (0x1, 0x4), (0x2, 0x5), (0x3, 0x5), (0x5, 0x4), (0x6, 0x5),
                                     (0x9, 0x0), (0xA, 0x1), (0xC, 0x0), (0xD, 0x0), (0xE, 0x1), (0xF, 0x1)];
        for (read, write) in table {
            // After the write the chip recomputes the N bits; a CONTROL of 0x04 reads back as "has control, bus on".
            let (mut i2c, mut chip) = full(vec![r(1, read), w(1, write), r(1, 0x04)]);
            assert!(chip.acquire(&mut NoopDelay::new(), false, 0).unwrap(), "read {read:#x}");
            i2c.done();
        }
    }

    #[test]
    fn acquire_times_out_without_control() {
        let (mut i2c, mut chip) = full(vec![r(1, 0x0A), w(1, 0x01), r(1, 0x0A), r(1, 0x0A)]);
        assert!(!chip.acquire(&mut NoopDelay::new(), false, 1).unwrap());
        i2c.done();
    }

    #[test]
    fn acquire_with_bus_init_waits_for_businit() {
        let (mut i2c, mut chip) = full(vec![r(1, 0x0A), w(1, 0x11), r(2, 0x00), r(2, 0x02), r(1, 0x04)]);
        assert!(chip.recover_bus(&mut NoopDelay::new(), 5).unwrap());
        i2c.done();
    }

    #[test]
    fn has_control_release_connect_disconnect() {
        let (mut i2c, mut chip) = full(vec![
            r(1, 0x04), r(1, 0x0A), r(1, 0x00), r(1, 0x07), r(1, 0x05),   // has_control values
            r(1, 0x04), w(1, 0x05),                                      // release
            r(1, 0x0D), w(1, 0x01),                                      // connect
            r(1, 0x04), w(1, 0x00),                                      // disconnect
        ]);
        assert!(chip.has_control().unwrap());
        assert!(!chip.has_control().unwrap());
        assert!(!chip.has_control().unwrap());
        assert!(chip.has_control().unwrap());
        assert!(!chip.has_control().unwrap());
        chip.release().unwrap();
        chip.connect().unwrap();
        chip.disconnect().unwrap();
        i2c.done();
    }

    #[test]
    fn status_and_connection() {
        let (mut i2c, mut chip) = full(vec![r(1, 0xD4), r(1, 0xD4), r(1, 0x0A), r(1, 0x00)]);
        assert_eq!(chip.get_control().unwrap(), 0xD4);
        assert_eq!(chip.status().unwrap(), PCA9541Status { has_control: true, connected: true, bus_init_requested: true, own_test: true, other_test: true });
        assert!(chip.is_connected().unwrap());
        assert!(!chip.is_connected().unwrap());
        i2c.done();
    }

    #[test]
    fn interrupt_mask_and_sources() {
        let (mut i2c, mut chip) = full(vec![
            w(0, 0x09), r(0, 0x09), w(0, 0x00),
            r(0, 0x00), w(0, 0x02),              // disable BUS_INIT
            r(0, 0x03), w(0, 0x01),              // enable BUS_INIT (IE was 0x03)
        ]);
        chip.set_interrupt_mask(true, false, false, true).unwrap();
        assert_eq!(chip.get_interrupt_mask().unwrap(), 0x09);
        chip.set_interrupt_mask(false, false, false, false).unwrap();
        chip.disable_interrupt(PCA9541_SOURCE_BUS_INIT).unwrap();
        chip.enable_interrupt(PCA9541_SOURCE_BUS_INIT).unwrap();
        chip.enable_interrupt(PCA9541_SOURCE_TEST).unwrap();   // no bus traffic
        i2c.done();
    }

    #[test]
    fn test_interrupt_keeps_bus_bits() {
        let (mut i2c, mut chip) = full(vec![
            r(1, 0x0D), w(1, 0x45), r(1, 0x0D), w(1, 0x85), r(1, 0x0D), w(1, 0xC5), r(1, 0x0D), w(1, 0x05),
        ]);
        chip.test_interrupt(true, false).unwrap();
        chip.test_interrupt(false, true).unwrap();
        chip.test_interrupt(true, true).unwrap();
        chip.test_interrupt(false, false).unwrap();
        i2c.done();
    }

    #[test]
    fn read_all_and_poll_interrupt() {
        let (mut i2c, mut chip) = full(vec![
            T::write_read(ADDR, vec![0x10], vec![0x03, 0x04, 0x08]),
            r(2, 0x0A),
        ]);
        assert_eq!(chip.read_all().unwrap(), (0x03, 0x04, 0x08));
        assert_eq!(chip.poll_interrupt().unwrap(), 0x0A);
        i2c.done();
    }
}
