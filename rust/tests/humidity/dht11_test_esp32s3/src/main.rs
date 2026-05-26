#![no_std]
#![no_main]

use esp_hal::delay::Delay;
use esp_hal::gpio::IO;
use esp_hal::i2c::I2c;
use esp_hal::peripherals::I2C0;

#[entry]
fn main() -> ! {
    let io = IO::new();
    let i2c = I2c::new(peripherals.I2C0, pins.gpio1, pins.gpio2, 100_000);
    let _dht = Dht11Minimal::new(i2c, 0x40);

    loop {
        Delay::new(1_000_000).delay_ms(1000);
    }
}
