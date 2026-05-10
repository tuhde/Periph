#![no_std]

use embedded_hal::spi::SpiBus;

fn encode(data: &[u8]) -> heapless::Vec<u8, 256> {
    let mut out = heapless::Vec::new();
    for &byte in data {
        for bit in (0..8).rev() {
            out.push(if (byte >> bit) & 1 == 1 { 0b110 } else { 0b100 }).ok();
        }
    }
    for _ in 0..16 {
        out.push(0x00).ok();
    }
    out
}

pub struct NeoPixelTransport<SPI> {
    spi: SPI,
}

impl<SPI: SpiBus> NeoPixelTransport<SPI> {
    pub fn new(spi: SPI) -> Self {
        Self { spi }
    }

    pub fn write(&mut self, data: &[u8]) -> Result<(), SPI::Error> {
        let encoded = encode(data);
        self.spi.write(&encoded)
    }
}