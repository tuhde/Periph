use gpio_cdev::{Chip, LineRequestFlags};
use linux_embedded_hal::CdevPin;
use periph::chips::adc_dac::{Hx711Full, Hx711Minimal};
use periph::transport::hx711::{HX711Error, HX711Transport};

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond {
            println!("PASS {}", $label);
            $passed += 1;
        } else {
            println!("FAIL {}", $label);
            $failed += 1;
        }
    };
}

fn main() {
    let chip_path = std::env::var("GPIO_CHIP").unwrap_or_else(|_| "/dev/gpiochip0".into());
    let dout_offset: u32 = std::env::var("HX711_DOUT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(5);
    let pd_sck_offset: u32 = std::env::var("HX711_PD_SCK")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(6);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut chip = Chip::new(&chip_path).expect("open gpio chip");

    // --- Hx711Minimal ---
    {
        let dout_handle = chip.get_line(dout_offset).expect("get dout line")
            .request(LineRequestFlags::INPUT, 0, "hx711_test_minimal").expect("request dout");
        let pd_sck_handle = chip.get_line(pd_sck_offset).expect("get pd_sck line")
            .request(LineRequestFlags::OUTPUT, 0, "hx711_test_minimal").expect("request pd_sck");

        let dout   = CdevPin::new(dout_handle).expect("dout pin");
        let pd_sck = CdevPin::new(pd_sck_handle).expect("pd_sck pin");

        let transport = HX711Transport::new(dout, pd_sck);
        let mut minimal = Hx711Minimal::new(transport).expect("init Hx711Minimal");

        let ready = minimal.is_ready();
        check_true!(ready.is_ok(), "minimal is_ready ok", passed, failed);

        let raw = minimal.read_raw();
        check_true!(raw.is_ok(), "minimal read_raw ok", passed, failed);
        if let Ok(v) = raw {
            check_true!(
                v >= -8_388_608 && v <= 8_388_607,
                "minimal read_raw in 24-bit signed range",
                passed,
                failed
            );
        }
    }

    // --- Hx711Full ---
    {
        let dout_handle = chip.get_line(dout_offset).expect("get dout line")
            .request(LineRequestFlags::INPUT, 0, "hx711_test_full").expect("request dout");
        let pd_sck_handle = chip.get_line(pd_sck_offset).expect("get pd_sck line")
            .request(LineRequestFlags::OUTPUT, 0, "hx711_test_full").expect("request pd_sck");

        let dout   = CdevPin::new(dout_handle).expect("dout pin");
        let pd_sck = CdevPin::new(pd_sck_handle).expect("pd_sck pin");

        let transport = HX711Transport::new(dout, pd_sck);
        let mut full = Hx711Full::new(transport).expect("init Hx711Full");

        check_true!(full.is_ready().is_ok(), "full is_ready ok", passed, failed);

        let raw = full.read_raw();
        check_true!(raw.is_ok(), "full read_raw ok", passed, failed);
        if let Ok(v) = raw {
            check_true!(
                v >= -8_388_608 && v <= 8_388_607,
                "full read_raw in 24-bit signed range",
                passed,
                failed
            );
        }

        check_true!(full.set_gain(64).is_ok(),  "set_gain 64 ok",  passed, failed);
        check_true!(full.set_gain(32).is_ok(),  "set_gain 32 ok",  passed, failed);
        check_true!(full.set_gain(128).is_ok(), "set_gain 128 ok", passed, failed);
        check_true!(
            matches!(full.set_gain(99), Err(HX711Error::InvalidPulseCount)),
            "set_gain 99 returns InvalidPulseCount",
            passed,
            failed
        );

        let avg = full.read_average(3);
        check_true!(avg.is_ok(), "read_average(3) ok", passed, failed);
        if let Ok(v) = avg {
            check_true!(
                v >= -8_388_608 && v <= 8_388_607,
                "read_average(3) in 24-bit signed range",
                passed,
                failed
            );
        }

        check_true!(full.tare(3).is_ok(), "tare(3) ok", passed, failed);
        let offset = full.get_offset();
        check_true!(
            offset >= -8_388_608 && offset <= 8_388_607,
            "get_offset in 24-bit signed range",
            passed,
            failed
        );

        full.set_scale(420.0);
        check_true!((full.get_scale() - 420.0).abs() < 0.001, "get_scale 420.0", passed, failed);

        let weight = full.read_weight(3);
        check_true!(weight.is_ok(), "read_weight(3) ok", passed, failed);

        check_true!(full.power_down().is_ok(), "power_down ok", passed, failed);
        std::thread::sleep(std::time::Duration::from_micros(65));
        check_true!(full.power_up().is_ok(), "power_up ok", passed, failed);

        let raw_after = full.read_raw();
        check_true!(raw_after.is_ok(), "read_raw after power_up ok", passed, failed);
    }

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
