//go:build linux && !tinygo

// RDA5807M hardware test — Linux host.
//
// Reads from /dev/i2c-N and runs the RDA5807M check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

// FM_READY deasserts on any register write and takes ~20 ms to settle back;
// not documented in the datasheet, measured on real hardware.
const settleMs = 30

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "I2C_BUS:", err)
		os.Exit(2)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x10"), 0, 8)
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

	fm, err := comms.NewRda5807mFull(conn, 100.0, 8)
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

	time.Sleep(settleMs * time.Millisecond)
	ready, err := fm.IsReady()
	check("is_ready", err == nil && ready)

	f, err := fm.Frequency()
	check("frequency_near_100mhz", err == nil && f > 99.8 && f < 100.2)

	if err := fm.SetFrequency(97.5); err != nil {
		fmt.Fprintln(os.Stderr, "set_frequency:", err)
	}
	f, err = fm.Frequency()
	check("set_frequency_near_97_5mhz", err == nil && f > 97.3 && f < 97.7)

	if err := fm.SetVolume(10); err != nil {
		fmt.Fprintln(os.Stderr, "set_volume:", err)
	}
	rssi, err := fm.SignalStrength()
	check("signal_strength_in_range", err == nil && rssi <= 127)

	if err := fm.Mute(true); err != nil {
		fmt.Fprintln(os.Stderr, "mute:", err)
	}
	if err := fm.Mute(false); err != nil {
		fmt.Fprintln(os.Stderr, "unmute:", err)
	}
	time.Sleep(settleMs * time.Millisecond)
	ready, err = fm.IsReady()
	check("mute_unmute_is_ready", err == nil && ready)

	_, err = fm.Seek(true)
	check("seek_returns_ok", err == nil)

	if err := fm.EnableRds(true); err != nil {
		fmt.Fprintln(os.Stderr, "rds:", err)
	}
	_, err = fm.RdsReady()
	check("rds_ready_ok", err == nil)

	band := comms.BandWorld
	space := comms.Space100K
	if err := fm.Configure(&band, &space, nil, nil, nil, nil, nil, nil); err != nil {
		fmt.Fprintln(os.Stderr, "configure:", err)
	}
	time.Sleep(settleMs * time.Millisecond)
	ready, err = fm.IsReady()
	check("after_configure_is_ready", err == nil && ready)

	if err := fm.Standby(true); err != nil {
		fmt.Fprintln(os.Stderr, "standby:", err)
	}
	time.Sleep(10 * time.Millisecond)
	if err := fm.Standby(false); err != nil {
		fmt.Fprintln(os.Stderr, "wake:", err)
	}
	ready, err = fm.IsReady()
	check("after_standby_cycle_is_ready", err == nil && ready)

	if err := fm.SoftReset(); err != nil {
		fmt.Fprintln(os.Stderr, "soft_reset:", err)
	}
	ready, err = fm.IsReady()
	check("after_soft_reset_is_ready", err == nil && ready)

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
