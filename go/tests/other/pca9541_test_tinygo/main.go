//go:build tinygo

// PCA9541 hardware test — TinyGo (Raspberry Pi Pico W).
package main

import (
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/other"
	"github.com/tuhde/Periph/go/periph/connection"
)

var passed, failed int

func check(label string, cond bool) {
	if cond {
		println("PASS", label)
		passed++
	} else {
		println("FAIL", label)
		failed++
	}
}

func fail(what string, err error) {
	println(what+":", err)
}

func near(a, b, tol float32) bool {
	d := a - b
	if d < 0 {
		d = -d
	}
	return d < tol
}

func main() {
	machine.I2C0.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz})
	conn := connection.NewI2CConnection(machine.I2C0, other.PCA9541I2CAddress, nil, nil)
	defer conn.Close()

	selector, err := other.NewPCA9541Minimal(conn)
	if err != nil {
		fail("init", err)
		return
	}
	check("construct_minimal", selector != nil)
	has, _ := selector.HasControl()
	check("has_control_is_bool", has || !has)

	full, err := other.NewPCA9541Full(conn)
	if err != nil {
		fail("init full", err)
		return
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

	print("===DONE: ", passed, " passed, ", failed, " failed===\n")
}
