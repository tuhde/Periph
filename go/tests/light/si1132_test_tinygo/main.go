//go:build tinygo

// Si1132 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an Si1132 on I2C1 (GP4 = SDA,
// GP5 = SCL). Prints PASS/FAIL per check and ends with the standard
// ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/light"
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
	conn := connection.NewI2CConnection(i2c, 0x60, nil, nil)
	chip := light.NewSi1132Full(conn)

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Printf("PASS %s\n", label)
			passed++
		} else {
			fmt.Printf("FAIL %s\n", label)
			failed++
		}
	}
	check("init", chip.Init() == nil)
	uv, vis, ir, err := chip.Read()
	check("read ok", err == nil)
	check("read_uv_index_range", uv >= 0 && uv < 20)
	_, _ = vis, ir
	u, err := chip.ReadUVIndex()
	check("read_uv_index_single", err == nil && u < 20)
	_, err = chip.ReadVisible()
	check("read_visible_single", err == nil)
	_, err = chip.ReadInfrared()
	check("read_infrared_single", err == nil)
	_, _, _, err = chip.ReadRaw()
	check("read_raw", err == nil)
	_, err = chip.ReadTemperatureRaw()
	check("temperature_raw", err == nil)
	_, err = chip.ReadVddRaw()
	check("vdd_raw", err == nil)
	check("set_gain", chip.SetGain(2, 2) == nil)
	_, err = chip.ReadVisible()
	check("gain_read", err == nil)
	check("gain_default", chip.SetGain(0, 0) == nil)
	check("set_high_range", chip.SetHighRange(true, true) == nil)
	_, err = chip.ReadVisible()
	check("high_range_read", err == nil)
	check("high_range_off", chip.SetHighRange(false, false) == nil)
	check("channels_uv_only", chip.SetChannels(true, false, false, false) == nil)
	_, _, _, err = chip.Read()
	check("uv_only_read", err == nil)
	check("channels_all", chip.SetChannels(true, true, true, false) == nil)
	check("channels_invalid", chip.SetChannels(false, false, false, false) != nil)
	_, err = chip.ReadCalibration()
	check("read_calibration", err == nil)
	check("force", chip.Force() == nil)
	time.Sleep(50 * time.Millisecond)
	ready, err := chip.MeasurementReady()
	check("measurement_ready", err == nil && ready)
	_, code, err := chip.ReadResponse()
	check("response_no_error", err == nil && code == 0)
	_, _ = chip.PollInterrupt()
	check("autonomous", chip.StartAutonomous(0.1) == nil)
	time.Sleep(500 * time.Millisecond)
	ready, err = chip.MeasurementReady()
	check("autonomous_ready", err == nil && ready)
	check("pause", chip.Pause() == nil)
	check("interrupt_pin", chip.EnableInterrupt() == nil && chip.DisableInterrupt() == nil)
	check("reset", chip.Reset() == nil)
	_, _, _, err = chip.Read()
	check("read_after_reset", err == nil)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
