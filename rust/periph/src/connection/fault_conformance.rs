//! Conformance test for connection error handling (#767, spec §5): a bus fault
//! injected with `embedded-hal-mock` (`Transaction::with_error`) must surface as
//! an `Err` from the chip driver, never as a stale or zero reading.
//!
//! Rust chip drivers are generic over `embedded-hal` buses directly, so
//! `embedded-hal-mock`'s error injection is the fault-injection mock here.

use embedded_hal::i2c::ErrorKind as I2cErrorKind;
use embedded_hal::spi::ErrorKind as SpiErrorKind;
use embedded_hal_mock::eh1::i2c::{Mock as I2cMock, Transaction as I2cTransaction};

use crate::chips::power::ina226::Ina226Minimal;
use crate::connection::neopixel::{NeoPixelConnection, NeoPixelError};

const ADDR: u8 = 0x40;

fn ina226_init() -> Vec<I2cTransaction> {
    vec![
        I2cTransaction::write(ADDR, vec![0x00, 0x41, 0x27]), // CONFIG default
        I2cTransaction::write(ADDR, vec![0x05, 0x03, 0x46]), // CAL for 0.1 Ω / 2 A
    ]
}

#[test]
fn i2c_nack_in_constructor_is_an_error() {
    let mut i2c = I2cMock::new(&[
        I2cTransaction::write(ADDR, vec![0x00, 0x41, 0x27]).with_error(I2cErrorKind::Other),
    ]);
    assert!(Ina226Minimal::new(i2c.clone(), ADDR, 0.1, 2.0).is_err());
    i2c.done();
}

#[test]
fn i2c_nack_on_read_is_an_error_not_a_reading() {
    let mut t = ina226_init();
    t.push(I2cTransaction::write_read(ADDR, vec![0x02], vec![0x19, 0x00])); // 8.0 V
    t.push(I2cTransaction::write_read(ADDR, vec![0x02], vec![0x00, 0x00]).with_error(I2cErrorKind::Other));
    t.push(I2cTransaction::write_read(ADDR, vec![0x02], vec![0x19, 0x00])); // recovers
    let mut i2c = I2cMock::new(&t);
    let mut ina = Ina226Minimal::new(i2c.clone(), ADDR, 0.1, 2.0).expect("init");
    assert!((ina.voltage().expect("first read") - 8.0).abs() < 1e-3);
    assert_eq!(ina.voltage(), Err(I2cErrorKind::Other));
    assert!((ina.voltage().expect("after fault") - 8.0).abs() < 1e-3);
    i2c.done();
}

/// An `SpiBus` whose every transfer fails: `embedded-hal-mock` 0.11 has no
/// error injection for SPI transactions.
struct FailingSpi;

impl embedded_hal::spi::ErrorType for FailingSpi {
    type Error = SpiErrorKind;
}

impl embedded_hal::spi::SpiBus for FailingSpi {
    fn read(&mut self, _: &mut [u8]) -> Result<(), SpiErrorKind> { Err(SpiErrorKind::Other) }
    fn write(&mut self, _: &[u8]) -> Result<(), SpiErrorKind> { Err(SpiErrorKind::Other) }
    fn transfer(&mut self, _: &mut [u8], _: &[u8]) -> Result<(), SpiErrorKind> { Err(SpiErrorKind::Other) }
    fn transfer_in_place(&mut self, _: &mut [u8]) -> Result<(), SpiErrorKind> { Err(SpiErrorKind::Other) }
    fn flush(&mut self) -> Result<(), SpiErrorKind> { Ok(()) }
}

#[test]
fn spi_fault_surfaces_from_neopixel_write() {
    let mut conn = NeoPixelConnection::new(FailingSpi);
    assert_eq!(conn.write(&[0, 0, 0]), Err(NeoPixelError::Spi(SpiErrorKind::Other)));
}
