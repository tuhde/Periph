use esp_hal::i2c::I2c;
use esp_hal::peripherals::I2C0;
use periph::chips::io_expander::Pcf8575Minimal;

#[main]
fn main() {
    let p = esp_hal::init(esp_hal::Config::default());
    let i2c = I2c::new(p.I2C0, p.pins.gpio1, p.pins.gpio0, 400_000);

    let chip = Pcf8575Minimal::new(i2c, 0x20).expect("init PCF8575");
    let mut p0 = chip.pin(0);
    p0.set_low().unwrap();
    loop {}
}