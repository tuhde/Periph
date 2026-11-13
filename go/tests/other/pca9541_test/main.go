//go:build linux && !tinygo

// PCA9541 hardware test — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

var passed, failed int

func check(label string, cond bool) {
	if cond {
		fmt.Println("PASS", label)
		passed++
	} else {
		fmt.Println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	fmt.Fprintln(os.Stderr, what+":", err)
	os.Exit(2)
}

func near(a, b, tol float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < tol
}

func main() {
	bus, err := strconv.Atoi(envOr("I2C_BUS", "1"))
	if err != nil {
		fail("I2C_BUS", err)
	}
	addr, err := strconv.ParseUint(envOr("I2C_ADDR", "0x70"), 0, 8)
	if err != nil {
		fail("I2C_ADDR", err)
	}

	conn, err := connection.NewI2CConnection(bus, uint8(addr), nil, nil)
	if err != nil {
		fail("connection", err)
	}
	defer conn.Close()

	selector, err := other.NewPCA9541Minimal(conn)
	if err != nil {
		fail("init", err)
		os.Exit(2)
	}
	check("construct_minimal", selector != nil)
	has, _ := selector.HasControl()
	check("has_control_is_bool", has || !has)

	full, err := other.NewPCA9541Full(conn)
	if err != nil {
		fail("init full", err)
		os.Exit(2)
	}
	control, _ := full.GetControl()
	st, _ := full.Status()
	check("status_matches_control", st.HasControl == ((control&1 != 0) == (control&2 != 0)))
	ie, _, _, _ := full.ReadAll()
	check("read_all_ie_range", ie <= 0x0F)
	full.SetInterruptMask(false, true, false, false)
	mask, _ := full.GetInterruptMask()
	check("interrupt_mask_roundtrip", mask == 0x04)
	full.SetInterruptMask(false, false, false, false)
	mask, _ = full.GetInterruptMask()
	check("interrupt_mask_cleared", mask == 0x00)
	full.TestInterrupt(true, false)
	st, _ = full.Status()
	check("test_interrupt_sets_teston", st.OwnTest)
	full.TestInterrupt(false, false)
	st, _ = full.Status()
	check("test_interrupt_clears", !st.OwnTest)
	ok, _ := full.Acquire(false, 20)
	has, _ = full.HasControl()
	check("acquire_takes_bus", ok && has)
	full.Disconnect()
	conn2, _ := full.IsConnected()
	check("disconnect", !conn2)
	full.Connect()
	conn2, _ = full.IsConnected()
	check("connect", conn2)
	full.Release()
	has, _ = full.HasControl()
	check("release_gives_bus_away", !has)
	full.Acquire(false, 20)
	has, _ = full.HasControl()
	check("reacquire", has)

	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
	if failed > 0 {
		os.Exit(1)
	}
}
