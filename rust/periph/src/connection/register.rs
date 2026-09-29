//! Register-addressed read/write helpers, free functions generic over
//! `embedded-hal` traits — no periph-owned wrapper type required.
//!
//! Rust chip drivers stay generic directly over `embedded_hal::i2c::I2c` /
//! `embedded_hal::spi::SpiDevice` (see `specs/feature_connection_design.md`
//! §4.5), so register access is a set of free functions rather than methods
//! on a mandatory wrapper. [`Connection`](super::connection::Connection)'s
//! own `read`/`write` are implemented in terms of these.

use embedded_hal::i2c::I2c;
use embedded_hal::spi::{Operation, SpiDevice};

/// Interpret the low `bits` bits of `value` as two's-complement signed.
pub fn to_signed(value: u32, bits: u32) -> i32 {
    let sign_bit = 1u32 << (bits - 1);
    ((value & (sign_bit - 1)).wrapping_sub(value & sign_bit)) as i32
}

/// Write `reg` then read back `buf.len()` bytes over I²C (repeated start).
pub fn read_register<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, buf: &mut [u8]) -> Result<(), I2C::Error> {
    i2c.write_read(addr, &[reg], buf)
}

/// Write `reg` followed by `data` over I²C in one transaction.
pub fn write_register<I2C: I2c>(i2c: &mut I2C, addr: u8, reg: u8, data: &[u8]) -> Result<(), I2C::Error> {
    let mut buf = [0u8; 17];
    buf[0] = reg;
    buf[1..=data.len()].copy_from_slice(data);
    i2c.write(addr, &buf[..=data.len()])
}

/// SPI register convention: which bits the command byte sets for a read / burst transfer.
pub struct SpiRegisterConvention {
    pub read_bit: u8,
    pub multi_byte_bit: Option<u8>,
}

/// Read `buf.len()` bytes starting at register `reg` over SPI, building the command byte from `conv`.
pub fn spi_read_register<SPI: SpiDevice>(
    spi: &mut SPI, conv: &SpiRegisterConvention, reg: u8, buf: &mut [u8],
) -> Result<(), SPI::Error> {
    let mut cmd = reg | conv.read_bit;
    if buf.len() > 1 { if let Some(mb) = conv.multi_byte_bit { cmd |= mb; } }
    spi.transaction(&mut [Operation::Write(&[cmd]), Operation::Read(buf)])
}

/// Write `data` to register `reg` over SPI, building the command byte from `conv`.
pub fn spi_write_register<SPI: SpiDevice>(
    spi: &mut SPI, conv: &SpiRegisterConvention, reg: u8, data: &[u8],
) -> Result<(), SPI::Error> {
    let mut cmd = reg;
    if data.len() > 1 { if let Some(mb) = conv.multi_byte_bit { cmd |= mb; } }
    spi.transaction(&mut [Operation::Write(&[cmd]), Operation::Write(data)])
}
