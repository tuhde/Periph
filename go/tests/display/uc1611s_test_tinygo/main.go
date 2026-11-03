//go:build tinygo

// UC1611s hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an EA DOGXL240-7 on I2C1 (GP4 = SDA, GP5 = SCL, command address 0x38, data 0x39).
// Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/display"
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
	command := connection.NewI2CConnection(i2c, 0x38, nil, nil)
	data := connection.NewI2CConnection(i2c, 0x39, nil, nil)

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
	must := func(label string, err error) {
		if err != nil {
			fmt.Printf("FAIL %s: %v\n", label, err)
			failed++
		}
	}

	chip, err := display.NewUC1611sFull(command, data, 0, 0, false, 0x8F)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("geometry_240x128", chip.Width() == 240 && chip.Height() == 128)

	s, err := chip.ReadStatus()
	must("read_status", err)
	check("status_version_bit", s[0]&0x80 != 0)
	check("status_product_code_1", s[2]>>4 == 1)
	check("status_display_enabled", s[0]&0x08 != 0)
	check("status_mirror_x_bottom_view", s[0]&0x40 != 0 && s[0]&0x20 == 0)

	must("sleep", chip.Sleep())
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("sleep_clears_display_enable", s[0]&0x08 == 0)
	must("wake", chip.Wake())
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("wake_sets_display_enable", s[0]&0x08 != 0)

	must("set_flip", chip.SetFlip(false, true))
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("flip_my_status", s[0]&0x40 == 0 && s[0]&0x20 != 0)
	must("set_flip", chip.SetFlip(true, false))
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("flip_mx_status", s[0]&0x40 != 0 && s[0]&0x20 == 0)

	// RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
	chip.Fill(false)
	chip.DrawLine(0, 0, 31, 7, true)
	chip.DrawLine(0, 31, 31, 24, true)
	chip.DrawRect(40, 4, 20, 20, true, false)
	must("show", chip.Show())
	match0, match3 := true, true
	for half := 0; half < 2; half++ {
		ram, err := chip.ReadRAM(0, half*32, 32)
		must("read_ram", err)
		for x := 0; x < 32; x++ {
			var expected byte
			for b := 0; b < 8; b++ {
				if chip.GetPixel(half*32+x, b) {
					expected |= 1 << uint(b)
				}
			}
			if ram[x] != expected {
				match0 = false
			}
		}
		ram, err = chip.ReadRAM(3, half*32, 32)
		must("read_ram", err)
		for x := 0; x < 32; x++ {
			var expected byte
			for b := 0; b < 8; b++ {
				if chip.GetPixel(half*32+x, 24+b) {
					expected |= 1 << uint(b)
				}
			}
			if ram[x] != expected {
				match3 = false
			}
		}
	}
	check("ram_readback_page0", match0)
	check("ram_readback_page3", match3)

	must("set_contrast", chip.SetContrast(0x70))
	must("set_contrast", chip.SetContrast(0x8F))
	must("set_invert", chip.SetInvert(true))
	must("set_invert", chip.SetInvert(false))
	must("set_all_on", chip.SetAllOn(true))
	must("set_all_on", chip.SetAllOn(false))
	must("set_scroll_line", chip.SetScrollLine(16))
	must("set_scroll_line", chip.SetScrollLine(0))
	must("set_fixed_lines", chip.SetFixedLines(0))
	must("set_bias_ratio", chip.SetBiasRatio(2))
	must("set_temperature_compensation", chip.SetTemperatureCompensation(1))
	must("set_line_rate", chip.SetLineRate(3))
	must("set_pump", chip.SetPump(true))
	must("set_panel_loading", chip.SetPanelLoading(true))
	must("set_partial_display", chip.SetPartialDisplay(true, 0, 127))
	must("set_partial_display", chip.SetPartialDisplay(false, 0, -1))
	must("set_nline_inversion", chip.SetNLineInversion(0, false))
	must("set_isolation_clock", chip.SetIsolationClock(1, 0))
	chip.SetWritePattern(1)
	must("show_page", chip.ShowPage(0))
	chip.SetWritePattern(0)
	must("set_display_pattern", chip.SetDisplayPattern(1))
	must("set_display_pattern", chip.SetDisplayPattern(0))
	must("show", chip.Show())
	check("commands_accepted", true)

	must("reset", chip.Reset())
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("reset_restores_display_enable", s[0]&0x08 != 0)
	fmt.Printf("INFO driver retries %d, unrecovered %d\n", chip.RetryCount(), chip.ErrorCount())
	check("no_unrecovered_transfer_errors", chip.OK())

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
