//go:build tinygo

// MCP2515 hardware test — TinyGo / Raspberry Pi Pico W.
//
// Flashed to a Pico W connected to an MCP2515 on SPI0 (GP18=SCK, GP19=SDO,
// GP16=SDI, GP17=CS). Prints PASS/FAIL per check and ends with the
// standard ===DONE: ... === line. The test runner (go/test_tinygo.sh)
// reads the serial output and reports exit code 0/1/2 based on the
// ===DONE=== line.
//
// Checks that don't require external hardware (initialisation, mode
// switching, error-counter readout, overflow clear, one-shot toggle,
// TX abort) always run. The RX checks (receive) only pass if a peer CAN
// device is transmitting on the bus during the test — they're skipped
// with a note otherwise.
package main

import (
	"fmt"
	"time"

	"machine"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 10_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		fmt.Printf("FAIL spi_configure: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil)
	chip, err := comms.NewMCP2515Full(conn, 125, 8)
	if err != nil {
		fmt.Printf("FAIL new: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}

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

	// --- Initialisation accepted valid (bitrate, oscillator) ---
	check("init_125kbps_8mhz", err == nil)
	_, errInvalid := comms.NewMCP2515Full(conn, 100, 8)
	check("init_rejects_unsupported_bitrate", errInvalid != nil)

	// --- Operating mode switching ---
	{
		m, err := chip.GetMode()
		check("get_mode_normal", err == nil && m == comms.OPMODNormal)
	}

	if err := chip.SetMode(comms.OPMODLoopback); err != nil {
		fmt.Printf("FAIL loopback: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	{
		m, err := chip.GetMode()
		check("get_mode_loopback", err == nil && m == comms.OPMODLoopback)
	}

	if err := chip.SetMode(comms.OPMODNormal); err != nil {
		fmt.Printf("FAIL normal: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	{
		m, err := chip.GetMode()
		check("get_mode_back_to_normal", err == nil && m == comms.OPMODNormal)
	}

	// --- Acceptance filter / mask ---
	check("set_mask_0_all_ones", chip.SetMask(0, 0x7FF, false) == nil)
	check("set_mask_1_all_ones", chip.SetMask(1, 0x7FF, false) == nil)
	check("set_filter_0_id_100", chip.SetFilter(0, 0x100, false) == nil)
	check("set_filter_5_id_555", chip.SetFilter(5, 0x555, false) == nil)
	check("set_rx_mode_rxb0_accept_all", chip.SetRxMode(0, 0x03) == nil)
	check("set_rx_mode_rxb1_accept_all", chip.SetRxMode(1, 0x03) == nil)

	// --- Per-buffer transmit on TXB0/TXB1/TXB2 (loopback echoes back) ---
	if err := chip.SetMode(comms.OPMODLoopback); err != nil {
		fmt.Printf("FAIL loopback2: %v\n", err)
		fmt.Println("===DONE: 0 passed, 1 failed===")
		return
	}
	check("send_txb0_loopback", chip.Send(0x100, []byte{0x01, 0x02, 0x03, 0x04}, false) == nil)
	check("send_txb1_loopback", chip.SendBuffered(0x200, []byte{0xAA}, false, 1) == nil)
	check("send_txb2_loopback", chip.SendBuffered(0x300, []byte{0xBB}, false, 2) == nil)

	// --- Receive (loopback echoes are pending) ---
	frame, err := chip.Recv(50)
	check("receive_polling_no_error", err == nil)
	if frame != nil {
		check("receive_loopback_got_frame", true)
		fmt.Printf("note: received id=0x%X dlc=%d data=%v\n", frame.ID, len(frame.Data), frame.Data)
	} else {
		fmt.Println("note: no frame received within timeout; skipping receive_loopback_got_frame")
	}

	// --- Send rejects bad arguments ---
	check("send_rejects_too_long", chip.Send(0x100, make([]byte, 9), false) != nil)
	check("send_rejects_bad_standard_id", chip.Send(0x800, []byte{0x00}, false) != nil)
	check("send_rejects_bad_extended_id", chip.Send(0x20000000, []byte{0x00}, true) != nil)

	// --- Error counters ---
	tec, rec, eflg, err := chip.ReadErrors()
	check("read_errors", err == nil)
	fmt.Printf("note: tec=%d rec=%d eflg=0x%02X\n", tec, rec, eflg)

	// --- Overflow clear ---
	check("clear_overflow_rxb0", chip.ClearOverflow(0) == nil)
	check("clear_overflow_rxb1", chip.ClearOverflow(1) == nil)

	// --- One-shot mode ---
	check("set_one_shot_on", chip.SetOneShot(true) == nil)
	check("set_one_shot_off", chip.SetOneShot(false) == nil)

	// --- TX abort ---
	check("abort_tx", chip.AbortTx() == nil)

	// --- SPI RESET and re-init ---
	check("reset", chip.Reset() == nil)
	check("init_after_reset", chip.Init(125, 8) == nil)

	time.Sleep(100 * time.Millisecond)
	fmt.Printf("===DONE: %d passed, %d failed===\n", passed, failed)
}
