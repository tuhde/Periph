//go:build tinygo

// AS7262 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to a AS7262 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/color"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		fmt.Printf("FAIL i2c_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	conn := connection.NewI2CConnection(i2c, 0x49, nil, nil)
	passed, failed := 0, 0
	check := func(label string, ok bool) {
		if ok {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	chip, err := color.NewAS7262Full(conn)
	if err != nil {
		fmt.Println("FAIL init:", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	dt, err := chip.DeviceType()
	check("device type", err == nil && dt == 0x40)
	ht, hv, err := chip.HardwareVersion()
	check("hardware version", err == nil && ht == 0x40 && hv == 0x3E)
	temp, err := chip.Temperature()
	check("temperature range", err == nil && temp >= -40 && temp <= 125)

	d, err := chip.Read()
	check("calibrated channels", err == nil && d.Violet >= 0 && d.Red >= 0)
	r, err := chip.ReadRaw()
	check("raw channels", err == nil && len(r.Array()) == 6)
	raw2, err := chip.ReadChannelRaw(2)
	check("read_channel_raw", err == nil && raw2 == raw2)

	check("set gain 64", chip.SetGain(64) == nil)
	g, err := chip.GetGain()
	check("gain", err == nil && g == 64)
	check("set gain 16", chip.SetGain(16) == nil)
	check("set integration time", chip.SetIntegrationTime(50) == nil)
	ms, err := chip.GetIntegrationTime()
	check("integration time", err == nil && ms > 50.3 && ms < 50.5)
	check("set mode 2", chip.SetMode(2) == nil)
	m, err := chip.GetMode()
	check("mode", err == nil && m == 2)
	check("set mode 3", chip.SetMode(3) == nil)

	check("measure", chip.Measure() == nil)
	time.Sleep(600 * time.Millisecond)
	ok, err := chip.DataReady()
	check("data ready", err == nil && ok)
	_, err = chip.ReadCalibrated()
	check("last calibrated", err == nil)

	check("indicator on", chip.SetIndicatorLED(true, 4) == nil)
	check("indicator off", chip.SetIndicatorLED(false, 1) == nil)
	check("soft reset", chip.SoftReset() == nil)
	ms, err = chip.GetIntegrationTime()
	check("reset int_t", err == nil && ms > 700)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
