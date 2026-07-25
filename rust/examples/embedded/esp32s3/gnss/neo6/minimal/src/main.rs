#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::uart::{Config, Uart};
use esp_println::println;
use periph::chips::gnss::{Neo6Minimal, UartBus};
use periph::transport::uart_linux::LinuxUart;

esp_app_desc!();

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let uart = Uart::new(peripherals.UART1, Config::default().with_baudrate(9600))
        .unwrap()
        .with_tx(peripherals.GPIO17)
        .with_rx(peripherals.GPIO18)
        .into_blocking();
    let mut delay = Delay::new();

    let mut gps = Neo6Minimal::new(UartBus(uart)); // Create NEO-6 driver, (bus: UartBus/I2cBus/SpiBus)

    loop {
        if gps.update().expect("update") {
            // Read + parse one NMEA sentence, () → Result<bool, Error>
            println!("{:?} {:?} {:?}", gps.latitude(), gps.longitude(), gps.altitude());
        }
        delay.delay_ms(50);
    }
}
