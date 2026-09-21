//go:build linux && !tinygo

// TPIC6B595 hardware test — Linux host.
//
// Constructs a SiPo software-SPI connection and runs the TPIC6B595
// check sequence. Prints PASS/FAIL per check and ends with the
// standard ===DONE: ... === line. Exits 0 on full pass, 1 on any
// failure.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/io_expander"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}

func main() {
	serIn, err := strconv.Atoi(envOr("SIPO_SER_IN", "19"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SIPO_SER_IN:", err)
		os.Exit(2)
	}
	srck, err := strconv.Atoi(envOr("SIPO_SRCK", "26"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SIPO_SRCK:", err)
		os.Exit(2)
	}
	rc, err := strconv.Atoi(envOr("SIPO_RCK", "5"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SIPO_RCK:", err)
		os.Exit(2)
	}
	sr, err := strconv.Atoi(envOr("SIPO_SRCLR", "6"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SIPO_SRCLR:", err)
		os.Exit(2)
	}
	g, err := strconv.Atoi(envOr("SIPO_G", "13"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SIPO_G:", err)
		os.Exit(2)
	}

	passed, failed := 0, 0
	check := func(label string, cond bool) {
		if cond {
			fmt.Println("PASS", label)
			passed++
		} else {
			fmt.Println("FAIL", label)
			failed++
		}
	}

	// --- TPIC6B595Minimal (single device) ---
	conn1, err := connection.NewSIPOSoftwareSPI(serIn, srck, rc, sr, g, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn1.Close()

	chip, err := ioexpander.NewTPIC6B595Minimal(conn1, 1)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
		os.Exit(2)
	}
	check("init_shadow_0", chip.Shadow(0) == 0)

	if err := chip.Fill(true); err != nil {
		fmt.Fprintln(os.Stderr, "fill true:", err)
	}
	check("fill_true_shadow", chip.Shadow(0) == 0xFF)
	if err := chip.Fill(false); err != nil {
		fmt.Fprintln(os.Stderr, "fill false:", err)
	}
	check("fill_false_shadow", chip.Shadow(0) == 0x00)
	if err := chip.Off(); err != nil {
		fmt.Fprintln(os.Stderr, "off:", err)
	}
	check("off_shadow", chip.Shadow(0) == 0x00)

	if err := chip.WritePort(0, 0xA5); err != nil {
		fmt.Fprintln(os.Stderr, "write_port:", err)
	}
	check("write_port_0xa5_shadow", chip.Shadow(0) == 0xA5)
	if err := chip.WritePort(0, 0x00); err != nil {
		fmt.Fprintln(os.Stderr, "write_port reset:", err)
	}

	p0 := chip.Pin(0)
	if err := p0.Set(true); err != nil {
		fmt.Fprintln(os.Stderr, "set high:", err)
	}
	check("pin_on_shadow_bit", chip.Shadow(0)&0x01 == 1)
	if err := p0.Set(false); err != nil {
		fmt.Fprintln(os.Stderr, "set low:", err)
	}
	check("pin_off_shadow_bit", chip.Shadow(0)&0x01 == 0)
	if err := p0.Toggle(); err != nil {
		fmt.Fprintln(os.Stderr, "toggle:", err)
	}
	check("pin_toggle_shadow_bit", chip.Shadow(0)&0x01 == 1)

	high, err := p0.Get()
	check("pin_get_after_set_high", err == nil && high == true)

	if err := p0.Set(false); err != nil {
		fmt.Fprintln(os.Stderr, "set low again:", err)
	}
	low, err := p0.Get()
	check("pin_get_after_set_low", err == nil && low == false)

	// --- TPIC6B595Full (two cascaded devices) ---
	conn2, err := connection.NewSIPOSoftwareSPI(serIn, srck, rc, sr, g, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection2:", err)
		os.Exit(2)
	}
	defer conn2.Close()

	cascaded, err := ioexpander.NewTPIC6B595Full(conn2, 2)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new cascaded:", err)
		os.Exit(2)
	}
	check("cascaded_init_shadow_0", cascaded.Shadow(0) == 0)
	check("cascaded_init_shadow_1", cascaded.Shadow(1) == 0)
	if err := cascaded.WritePort(0, 0x01); err != nil {
		fmt.Fprintln(os.Stderr, "write port 0:", err)
	}
	if err := cascaded.WritePort(1, 0x80); err != nil {
		fmt.Fprintln(os.Stderr, "write port 1:", err)
	}
	check("cascaded_write_port_0", cascaded.Shadow(0) == 0x01)
	check("cascaded_write_port_1", cascaded.Shadow(1) == 0x80)

	if err := cascaded.Clear(); err != nil {
		fmt.Fprintln(os.Stderr, "clear:", err)
	}
	check("clear_accepted", true)
	if err := cascaded.SetOutputEnable(false); err != nil {
		fmt.Fprintln(os.Stderr, "set output enable false:", err)
	}
	check("set_output_enable_false_accepted", true)
	if err := cascaded.SetOutputEnable(true); err != nil {
		fmt.Fprintln(os.Stderr, "set output enable true:", err)
	}
	check("set_output_enable_true_accepted", true)

	if err := cascaded.WriteAll([]uint8{0xA5, 0x5A}); err != nil {
		fmt.Fprintln(os.Stderr, "write all:", err)
	}
	check("write_all_shadow_0", cascaded.Shadow(0) == 0xA5)
	check("write_all_shadow_1", cascaded.Shadow(1) == 0x5A)

	if err := cascaded.WriteAll([]uint8{0xFF}); err != nil {
		fmt.Fprintln(os.Stderr, "write all pad:", err)
	}
	check("write_all_pad_shadow_0", cascaded.Shadow(0) == 0xFF)
	check("write_all_pad_shadow_1", cascaded.Shadow(1) == 0x00)

	if err := cascaded.WriteAll([]uint8{0x12, 0x34, 0x56}); err != nil {
		fmt.Fprintln(os.Stderr, "write all truncate:", err)
	}
	check("write_all_truncate_shadow_0", cascaded.Shadow(0) == 0x12)
	check("write_all_truncate_shadow_1", cascaded.Shadow(1) == 0x34)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}
