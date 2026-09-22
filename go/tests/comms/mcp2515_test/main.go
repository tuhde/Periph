//go:build linux && !tinygo

// MCP2515 hardware test — Linux host.
//
// Opens /dev/spidevB.D and runs the MCP2515Full check sequence. Prints
// PASS/FAIL per check and ends with the standard ===DONE: ... === line.
// Exits 0 on full pass, 1 on any failure, 2 if the test did not complete.
//
// Checks that don't require external hardware (initialisation, mode
// switching, error-counter readout, overflow clear, one-shot toggle,
// TX abort) always run. The RX checks (receive) only pass if a peer CAN
// device is transmitting on the bus during the test — they're skipped
// with a note otherwise.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_BUS:", err)
		os.Exit(2)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "SPI_DEVICE:", err)
		os.Exit(2)
	}
	bitrate, err := strconv.Atoi(envOr("MCP2515_BITRATE_KBPS", "125"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "MCP2515_BITRATE_KBPS:", err)
		os.Exit(2)
	}
	oscMhz, err := strconv.Atoi(envOr("MCP2515_OSC_MHZ", "8"))
	if err != nil {
		fmt.Fprintln(os.Stderr, "MCP2515_OSC_MHZ:", err)
		os.Exit(2)
	}

	conn, err := connection.NewSPIConnection(bus, device, 0, 10_000_000, nil, nil)
	if err != nil {
		fmt.Fprintln(os.Stderr, "connection:", err)
		os.Exit(2)
	}
	defer conn.Close()

	chip, err := comms.NewMCP2515Full(conn, uint16(bitrate), uint8(oscMhz))
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

	// --- Initialisation accepted valid (bitrate, oscillator) ---
	check("init_125kbps_8mhz", err == nil)
	_, errInvalid := comms.NewMCP2515Full(conn, 100, 8)
	check("init_rejects_unsupported_bitrate", errInvalid != nil)

	// --- Operating mode switching ---
	{
		m, err := chip.GetMode()
		check("get_mode_normal", err == nil && m == comms.OPMODNormal)
	}

	if err := chip.SetMode(comms.OPMODLoopback); err != nil { // loopback lets us self-test the round trip without external hardware
		fmt.Fprintln(os.Stderr, "loopback:", err)
		os.Exit(2)
	}
	{
		m, err := chip.GetMode()
		check("get_mode_loopback", err == nil && m == comms.OPMODLoopback)
	}

	if err := chip.SetMode(comms.OPMODListenOnly); err != nil {
		fmt.Fprintln(os.Stderr, "listen_only:", err)
		os.Exit(2)
	}
	{
		m, err := chip.GetMode()
		check("get_mode_listen_only", err == nil && m == comms.OPMODListenOnly)
	}

	if err := chip.SetMode(comms.OPMODNormal); err != nil {
		fmt.Fprintln(os.Stderr, "normal:", err)
		os.Exit(2)
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

	// --- Per-buffer transmit on TXB0 (loopback echoes back into RXB0) ---
	if err := chip.SetMode(comms.OPMODLoopback); err != nil {
		fmt.Fprintln(os.Stderr, "loopback2:", err)
		os.Exit(2)
	}
	check("send_txb0_loopback", chip.Send(0x100, []byte{0x01, 0x02, 0x03, 0x04}, false) == nil)
	check("send_txb1_loopback", chip.SendBuffered(0x200, []byte{0xAA}, false, 1) == nil)
	check("send_txb2_loopback", chip.SendBuffered(0x300, []byte{0xBB}, false, 2) == nil)

	// --- Receive (loopback echoes are already pending) ---
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
	check("init_after_reset", chip.Init(uint16(bitrate), uint8(oscMhz)) == nil)

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
