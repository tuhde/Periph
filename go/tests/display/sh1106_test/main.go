//go:build linux && !tinygo

// SH1106 hardware test — Linux host.
//
// Reads/writes /dev/i2c-N (address I2C_ADDR, default 0x3C) and runs the SH1106 check sequence. The picture itself is
// verified by eye (see the demo example). Prints PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/display"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x3C"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}
	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

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

	chip, err := display.NewSH1106Full(conn, 0, 0, 2, true)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("geometry_128x64", chip.Width() == 128 && chip.Height() == 64)

	s, err := chip.ReadStatus()
	must("read_status", err)
	check("status_not_busy", s&0x80 == 0)
	check("status_display_on", s&0x40 == 0)

	must("sleep", chip.Sleep())
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("sleep_sets_off_bit", s&0x40 != 0)
	must("wake", chip.Wake())
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("wake_clears_off_bit", s&0x40 == 0)

	// RAM read-back: draw, show, read pages 0 and 3 back and compare with the framebuffer.
	chip.Fill(false)
	chip.DrawLine(0, 0, 31, 7, true)
	chip.DrawLine(0, 31, 31, 24, true)
	chip.DrawRect(40, 4, 20, 20, true, false)
	must("show", chip.Show())
	match0, match3 := true, true
	for part := 0; part < 4; part++ {
		ram, err := chip.ReadPage(0, 2+part*16, 16)
		must("read_page", err)
		for x := 0; x < 16 && len(ram) == 16; x++ {
			var expected byte
			for b := 0; b < 8; b++ {
				if chip.GetPixel(part*16+x, b) {
					expected |= 1 << uint(b)
				}
			}
			if ram[x] != expected {
				match0 = false
			}
		}
		ram, err = chip.ReadPage(3, 2+part*16, 16)
		must("read_page", err)
		for x := 0; x < 16 && len(ram) == 16; x++ {
			var expected byte
			for b := 0; b < 8; b++ {
				if chip.GetPixel(part*16+x, 24+b) {
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

	must("set_contrast", chip.SetContrast(0x40))
	must("set_contrast", chip.SetContrast(0x80))
	must("set_invert", chip.SetInvert(true))
	must("set_invert", chip.SetInvert(false))
	must("set_all_on", chip.SetAllOn(true))
	must("set_all_on", chip.SetAllOn(false))
	must("set_flip", chip.SetFlip(true, true))
	must("set_flip", chip.SetFlip(false, false))
	must("set_start_line", chip.SetStartLine(16))
	must("set_start_line", chip.SetStartLine(0))
	must("set_display_offset", chip.SetDisplayOffset(0))
	must("set_multiplex", chip.SetMultiplex(64))
	must("set_clock", chip.SetClock(1, 5))
	must("set_precharge", chip.SetPrecharge(2, 2))
	must("set_com_config", chip.SetComConfig(true))
	must("set_vcom_deselect", chip.SetVcomDeselect(0x35))
	must("set_pump_voltage", chip.SetPumpVoltage(0))
	must("show_page", chip.ShowPage(0))
	must("show", chip.Show())
	s, err = chip.ReadStatus()
	must("read_status", err)
	check("commands_accepted", s&0x80 == 0)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
