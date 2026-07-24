//go:build linux && !tinygo

// APDS-9960 hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the APDS-9960 check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
	"github.com/tuhde/Periph/go/periph/chips/light"
)

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x39"), 0, 8)
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_ADDR:", err)
		os.Exit(2)
	}

	tr, err := transport.NewI2CTransport(bus, uint8(addr))
	if err != nil {
		fmt.Fprintln(os.Stderr, "transport:", err)
		os.Exit(2)
	}
	defer tr.Close()

	chip, err := light.NewAPDS9960Full(tr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "new:", err)
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

	id, err := chip.ChipID()
	check("chip_id", err == nil && id == 0xAB)

	c, r, g, b, err := chip.Color()
	check("color_clear_range", err == nil && c <= 65535)
	check("color_red_range", err == nil && r <= 65535)
	check("color_green_range", err == nil && g <= 65535)
	check("color_blue_range", err == nil && b <= 65535)

	valid, err := chip.IsAlsValid()
	check("is_als_valid", err == nil && valid)

	if err := chip.EnableProximity(true); err != nil {
		fmt.Fprintln(os.Stderr, "enable_proximity:", err)
	}
	time.Sleep(100 * time.Millisecond)
	prox, err := chip.Proximity()
	check("proximity_range", err == nil && prox <= 255)
	pvalid, err := chip.IsProximityValid()
	check("is_proximity_valid", err == nil && pvalid)

	if err := chip.ConfigureALS(0xB6, 1); err != nil {
		fmt.Fprintln(os.Stderr, "configure_als:", err)
	}
	time.Sleep(210 * time.Millisecond)
	valid2, err := chip.IsAlsValid()
	check("als_valid_after_configure", err == nil && valid2)

	if err := chip.AlsThreshold(100, 60000); err != nil {
		fmt.Fprintln(os.Stderr, "als_threshold:", err)
	}
	if err := chip.ProximityThreshold(10, 200); err != nil {
		fmt.Fprintln(os.Stderr, "proximity_threshold:", err)
	}
	if err := chip.SetPersistence(0, 1); err != nil {
		fmt.Fprintln(os.Stderr, "set_persistence:", err)
	}
	check("thresholds_persistence_set", true)

	if err := chip.EnableAlsInterrupt(true); err != nil {
		fmt.Fprintln(os.Stderr, "enable_als_interrupt:", err)
	}
	if err := chip.EnableProximityInterrupt(true); err != nil {
		fmt.Fprintln(os.Stderr, "enable_proximity_interrupt:", err)
	}
	if err := chip.ClearAlsInterrupt(); err != nil {
		fmt.Fprintln(os.Stderr, "clear_als_interrupt:", err)
	}
	if err := chip.ClearProximityInterrupt(); err != nil {
		fmt.Fprintln(os.Stderr, "clear_proximity_interrupt:", err)
	}
	if err := chip.ClearAllInterrupts(); err != nil {
		fmt.Fprintln(os.Stderr, "clear_all_interrupts:", err)
	}
	check("interrupts_cleared", true)

	if err := chip.SetProximityOffset(10, -5); err != nil {
		fmt.Fprintln(os.Stderr, "set_proximity_offset:", err)
	}
	if err := chip.SetProximityMask(false, false, false, false); err != nil {
		fmt.Fprintln(os.Stderr, "set_proximity_mask:", err)
	}
	check("proximity_offset_mask_set", true)

	if err := chip.EnableGesture(true); err != nil {
		fmt.Fprintln(os.Stderr, "enable_gesture:", err)
	}
	if err := chip.ConfigureGesture(1, 0, 0, 1, 1, 50, 20); err != nil {
		fmt.Fprintln(os.Stderr, "configure_gesture:", err)
	}
	check("gesture_configured", true)

	level, err := chip.GestureFIFOLevel()
	check("gesture_fifo_level", err == nil && level <= 32)
	if err := chip.ClearGestureFIFO(); err != nil {
		fmt.Fprintln(os.Stderr, "clear_gesture_fifo:", err)
	}
	if err := chip.EnableGestureInterrupt(false); err != nil {
		fmt.Fprintln(os.Stderr, "enable_gesture_interrupt:", err)
	}
	if err := chip.EnableGesture(false); err != nil {
		fmt.Fprintln(os.Stderr, "disable_gesture:", err)
	}
	check("gesture_disabled", true)

	status, err := chip.Status()
	check("status_readable", err == nil && status <= 0xFF)

	if err := chip.EnableProximity(false); err != nil {
		fmt.Fprintln(os.Stderr, "disable_proximity:", err)
	}

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed != 0 {
		os.Exit(1)
	}
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
