#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use esp_hal::delay::Delay;
use esp_hal::uart::{Config, Uart};
use esp_println::println;
use periph::chips::gnss::{Neo6Full, UartBus};
use periph::connection::uart_linux::LinuxUart;

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

    let mut gps = Neo6Full::new(UartBus(uart)); // Create NEO-6 driver, (bus: UartBus/I2cBus/SpiBus)

    gps.set_rate(1).expect("set rate");             // Set navigation update rate, (hz) → Result<(), Error>
                                                      // writes CFG-RATE with measRate = 1000/hz ms
    gps.set_platform(0).expect("set platform");      // Set dynamic platform model, (model 0-8) → Result<(), Error>
                                                      // writes CFG-NAV5 with mask=dynModel only
    gps.save_config().expect("save config");         // Persist current configuration, () → Result<(), Error>
                                                      // writes CFG-CFG with saveMask=all, deviceMask=BBR|Flash|EEPROM

    for _ in 0..20 {
        if gps.update().expect("update") {
            // Read + parse one NMEA sentence, () → Result<bool, Error>
            println!("{:?} {:?} {:?}", gps.latitude(), gps.longitude(), gps.altitude());
            println!("{:?} {:?}", gps.speed(), gps.course()); // Speed / course over ground, () → Option<f32> m/s / deg
            println!("{:?} {:?}", gps.utc_time(), gps.utc_date()); // UTC time / date, () → Option<&str>
            println!("{:?}", gps.hdop()); // Horizontal dilution of precision, () → Option<f32>
        }
        delay.delay_ms(50);
    }

    let nav_status = gps.poll_ubx(0x01, 0x03).expect("poll NAV-STATUS"); // Poll a UBX message, (msg_class, msg_id) → Result<UbxPayload, Error>
    println!("NAV-STATUS payload: {:?}", nav_status.as_slice());

    gps.cold_start().expect("cold start"); // Force a cold start via CFG-RST, () → Result<(), Error>
    loop {}
}
