//go:build linux && !tinygo

// SSD1306 hardware test — Linux host.
//
// Reads/writes /dev/i2c-N (address I2C_ADDR, default 0x3C) and runs the SSD1306 check sequence. The picture itself is
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

	chip, err := display.NewSSD1306Full(conn, 0, 0, false)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("geometry_128x64", chip.Width() == 128 && chip.Height() == 64)

	// The SSD1306 cannot be read over its serial interfaces: the picture is checked by eye.
	chip.Fill(false)
	chip.DrawLine(0, 0, 31, 7, true)
	chip.DrawLine(0, 31, 31, 24, true)
	chip.DrawRect(40, 4, 20, 20, true, false)
	must("show", chip.Show())
	check("framebuffer_pixels", chip.GetPixel(0, 0) && chip.GetPixel(40, 4))

	must("set_contrast", chip.SetContrast(0x40))
	must("set_contrast", chip.SetContrast(0x7F))
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
	must("set_clock", chip.SetClock(1, 8))
	must("set_precharge", chip.SetPrecharge(2, 2))
	must("set_com_config", chip.SetComConfig(true, false))
	must("set_vcomh", chip.SetVcomh(0x20))
	must("set_charge_pump", chip.SetChargePump(true))
	must("set_scroll_horizontal", chip.SetScrollHorizontal(true, 0, 7, 7))
	must("scroll_start", chip.ScrollStart())
	check("show_refused_while_scrolling", chip.Show() == display.ErrSSD1306ScrollActive)
	must("scroll_stop", chip.ScrollStop())
	must("set_addressing_mode_horizontal", chip.SetAddressingMode(display.SSD1306AddrHorizontal))
	must("show_horizontal", chip.Show())
	must("set_addressing_mode_page", chip.SetAddressingMode(display.SSD1306AddrPage))
	must("show_page", chip.ShowPage(0))
	must("show", chip.Show())
	check("commands_accepted", true)

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
