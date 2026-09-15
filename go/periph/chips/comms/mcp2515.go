// Package comms contains drivers for Wireless and wired communication modules (FM tuner, etc.).
package comms

import (
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SPI instruction set.
const (
	instrReset     = 0xC0
	instrRead      = 0x03
	instrReadRxBuf = 0x90
	instrWrite     = 0x02
	instrLoadTxBuf = 0x40
	instrRTS       = 0x80
	instrReadStatus = 0xA0
	instrRxStatus   = 0xB0
	instrBitModify  = 0x05
)

// Register addresses.
const (
	regCANSTAT  = 0x0E
	regCANCTRL  = 0x0F
	regCNF3     = 0x28
	regCNF2     = 0x29
	regCNF1     = 0x2A
	regCANINTE  = 0x2B
	regCANINTF  = 0x2C
	regEFLG     = 0x2D
	regTEC      = 0x1C
	regREC      = 0x1D
	regRXB0CTRL = 0x60
	regRXB1CTRL = 0x70
	regTXB0CTRL = 0x30
	regTXB1CTRL = 0x40
	regTXB2CTRL = 0x50
	regRXM0SIDH = 0x20
	regRXM1SIDH = 0x24
)

// OPMOD values (CANSTAT[7:5] and CANCTRL[7:5]).
const (
	OPMODNormal     = 0x00
	OPMODSleep      = 0x20
	OPMODLoopback   = 0x40
	OPMODListenOnly = 0x60
	OPMODConfig     = 0x80
)

// Acceptance filter bases (SIDH address for each of the 6 filters).
var filterBases = [6]uint8{0x00, 0x04, 0x08, 0x10, 0x14, 0x18}

// TX buffer control register bases.
var txbCtrlBases = [3]uint8{regTXB0CTRL, regTXB1CTRL, regTXB2CTRL}

// CANINTF flag bits.
const (
	canintfRX0IF = 0x01
	canintfRX1IF = 0x02
	canintfTX0IF = 0x04
	canintfTX1IF = 0x08
	canintfTX2IF = 0x10
)

// EFLG flag bits.
const (
	eflgRX0OVR = 0x40
	eflgRX1OVR = 0x80
)

// CANCTRL bits (in addition to OPMOD).
const (
	canctrlABAT = 0x10
	canctrlOSM  = 0x08
)

// TXBnCTRL bits.
const (
	txbctrlTXREQ = 0x08
)

// TXBnSIDL bits.
const (
	txbsidlEXIDE = 0x08
)

// READ STATUS byte bits.
const (
	readStatusRX0IF = 0x01
	readStatusRX1IF = 0x02
)

// Pre-computed CNF register values for the supported (bitrate, oscillator)
// combinations, keyed by (bitrateKbps<<8)|oscMhz.
//
// Values are BTLMODE=1, SAM=0, SJW=1 TQ, taken from §Bit Timing of the spec.
var cnfTable = map[uint32][3]uint8{
	// 125 kbit/s @ 8 MHz: PropSeg=3, PS1=8, PS2=4 (TQ=500 ns)
	(uint32(125) << 8) | 8: {0x01, 0xBA, 0x03},
	// 250 kbit/s @ 8 MHz: PropSeg=3, PS1=8, PS2=4 (TQ=250 ns)
	(uint32(250) << 8) | 8: {0x00, 0xBA, 0x03},
	// 500 kbit/s @ 8 MHz: PropSeg=2, PS1=3, PS2=2 (TQ=250 ns)
	(uint32(500) << 8) | 8: {0x00, 0x91, 0x01},
	// 1 Mbit/s @ 8 MHz: PropSeg=1, PS1=1, PS2=1 (TQ=250 ns)
	(uint32(1000) << 8) | 8: {0x00, 0x80, 0x00},
	// 125 kbit/s @ 16 MHz: PropSeg=3, PS1=8, PS2=4 (TQ=500 ns)
	(uint32(125) << 8) | 16: {0x03, 0xBA, 0x03},
	// 250 kbit/s @ 16 MHz: PropSeg=3, PS1=8, PS2=4 (TQ=250 ns)
	(uint32(250) << 8) | 16: {0x01, 0xBA, 0x03},
	// 500 kbit/s @ 16 MHz: PropSeg=3, PS1=8, PS2=4 (TQ=125 ns)
	(uint32(500) << 8) | 16: {0x00, 0xBA, 0x03},
	// 1 Mbit/s @ 16 MHz: PropSeg=2, PS1=3, PS2=2 (TQ=125 ns)
	(uint32(1000) << 8) | 16: {0x00, 0x91, 0x01},
}

// CanFrame is a single CAN 2.0B data or remote frame.
//
// Standard frames have an 11-bit ID (top bits zero); extended frames have a
// 29-bit ID. Data is 0–8 bytes.
type CanFrame struct {
	// ID is the 11-bit (standard) or 29-bit (extended) CAN identifier.
	ID uint32
	// Data is the payload, 0–8 bytes.
	Data []byte
	// Extended is true for 29-bit identifiers.
	Extended bool
	// RTR is true for remote transmission request frames.
	RTR bool
}

// MCP2515Minimal is the stand-alone MCP2515 CAN 2.0B controller driver —
// minimal interface: send and receive CAN frames in polled mode with
// zero-configuration defaults.
//
// Default configuration baked in at construction: 125 kbit/s, 8 MHz
// oscillator; RXM[1:0]=11 in both RXB0CTRL and RXB1CTRL (accept all); BUKT=1
// (rollover from RXB0 to RXB1 on overflow); CANINTE=0x00 (polled); OSM=0
// (retransmit on error or arbitration loss); TXB0 used for transmit with
// priority TXP=11 (highest).
type MCP2515Minimal struct {
	conn        connection.Connection
	bitrateKbps uint16
	oscMhz      uint8
	rxDataBuf   [8]byte
}

// NewMCP2515Minimal creates a new MCP2515Minimal, issues RESET, verifies
// Configuration mode, programs bit timing, and enters Normal mode.
//
// conn must be a configured SPI connection (mode 0 or mode 3, ≤10 MHz,
// MSB first). bitrateKbps must be one of 125, 250, 500, 1000. oscMhz
// must be 8 or 16.
func NewMCP2515Minimal(conn connection.Connection, bitrateKbps uint16, oscMhz uint8) (*MCP2515Minimal, error) {
	c := &MCP2515Minimal{
		conn:        conn,
		bitrateKbps: bitrateKbps,
		oscMhz:      oscMhz,
	}
	if err := c.Init(bitrateKbps, oscMhz); err != nil {
		return nil, err
	}
	return c, nil
}

// Init runs the full MCP2515 bring-up sequence: SPI RESET, verify
// Configuration mode, program CNF1/CNF2/CNF3, set acceptance masks to
// all-ones (exact filter match), set RXB0CTRL/RXB1CTRL to accept-all,
// enable BUKT rollover, leave CANINTE at 0x00 (polled), set TXB0 priority
// to TXP=11, and finally request Normal mode.
//
// bitrateKbps must be one of 125, 250, 500, 1000. oscMhz must be 8 or 16.
func (c *MCP2515Minimal) Init(bitrateKbps uint16, oscMhz uint8) error {
	cnf, ok := cnfTable[(uint32(bitrateKbps)<<8)|uint32(oscMhz)]
	if !ok {
		return fmt.Errorf("mcp2515: unsupported (bitrate_kbps=%d, osc_mhz=%d): expected bitrate in {125,250,500,1000} and osc_mhz in {8,16}", bitrateKbps, oscMhz)
	}
	c.bitrateKbps = bitrateKbps
	c.oscMhz = oscMhz

	if err := c.reset(); err != nil {
		return err
	}
	// Wait >= 2 us after RESET before further SPI traffic.
	time.Sleep(100 * time.Microsecond)

	if err := c.waitOpMode(OPMODConfig, 100); err != nil {
		return err
	}

	if err := c.writeReg(regCNF1, cnf[0]); err != nil {
		return err
	}
	if err := c.writeReg(regCNF2, cnf[1]); err != nil {
		return err
	}
	if err := c.writeReg(regCNF3, cnf[2]); err != nil {
		return err
	}

	// RXM0 = RXM1 = 0xFF,0xFF,0xFF,0xFF: only exact filter match.
	for _, base := range []uint8{regRXM0SIDH, regRXM1SIDH} {
		for i := uint8(0); i < 4; i++ {
			if err := c.writeReg(base+i, 0xFF); err != nil {
				return err
			}
		}
	}

	// RXM[1:0]=11 (accept all) | BUKT=1 on RXB0CTRL (0x60 | 0x04 = 0x64).
	if err := c.modifyReg(regRXB0CTRL, 0x60|0x04, 0x60|0x04); err != nil {
		return err
	}
	// RXM[1:0]=11 (accept all) on RXB1CTRL.
	if err := c.modifyReg(regRXB1CTRL, 0x60, 0x60); err != nil {
		return err
	}

	// CANINTE = 0x00 (polled).
	if err := c.writeReg(regCANINTE, 0x00); err != nil {
		return err
	}

	// TXBnCTRL priority 3 (TXP=11) on all three TX buffers.
	for _, base := range txbCtrlBases {
		if err := c.modifyReg(base, 0x03, 0x03); err != nil {
			return err
		}
	}

	return c.setMode(OPMODNormal)
}

// Send transmits a CAN data frame on TXB0: loads the ID and payload,
// asserts TXREQ, and polls until TXREQ clears (max 10 ms).
//
// id is 11 bits (Extended=false) or 29 bits (Extended=true). data is
// 0–8 bytes; longer payloads are truncated. RTR is set to false.
func (c *MCP2515Minimal) Send(id uint32, data []byte, extended bool) error {
	return c.send(id, data, extended, false, 0)
}

// Recv polls the READ STATUS byte for RX0IF/RX1IF and, when set, reads the
// matching RX buffer into a CanFrame. Returns (nil, nil) on timeoutMs
// expiration with no frame; returns (nil, error) on bus error.
//
// When timeoutMs is 0 the call is non-blocking and returns immediately if
// no frame is pending. When timeoutMs > 0 the call blocks polling every
// 5 ms up to timeoutMs milliseconds.
func (c *MCP2515Minimal) Recv(timeoutMs uint32) (*CanFrame, error) {
	return c.pollRx(timeoutMs)
}

// MCP2515Full is the stand-alone MCP2515 CAN 2.0B controller driver —
// full interface. Extends MCP2515Minimal with per-buffer transmit,
// acceptance filter/mask configuration, RX buffer mode selection,
// operating-mode switching, error counters, overflow clearing, TX
// abort, and one-shot mode.
type MCP2515Full struct {
	MCP2515Minimal
}

// NewMCP2515Full creates a new MCP2515Full and runs the same Init sequence
// as NewMCP2515Minimal.
func NewMCP2515Full(conn connection.Connection, bitrateKbps uint16, oscMhz uint8) (*MCP2515Full, error) {
	m, err := NewMCP2515Minimal(conn, bitrateKbps, oscMhz)
	if err != nil {
		return nil, err
	}
	return &MCP2515Full{MCP2515Minimal: *m}, nil
}

// SendBuffered transmits a CAN frame on a specific TX buffer (0, 1, or 2).
// buf selects the TX buffer; see Minimal.Send for the rest of the
// semantics. RTS is issued via the SPI RTS instruction so the controller
// schedules transmission across all three buffers at once if multiple bits
// are set.
func (f *MCP2515Full) SendBuffered(id uint32, data []byte, extended bool, buf uint8) error {
	return f.send(id, data, extended, false, buf)
}

// SetFilter programs one of the six acceptance filters (0–5). Filters 0–1
// are associated with RXM0 (RXB0); filters 2–5 with RXM1 (RXB1). The
// device must be in Configuration mode (call SetMode("config") first).
func (f *MCP2515Full) SetFilter(filterNum uint8, id uint32, extended bool) error {
	return f.setFilter(filterNum, id, extended)
}

// SetMask programs one of the two acceptance masks. maskNum=0 covers
// filters 0–1 (RXB0); maskNum=1 covers filters 2–5 (RXB1). Mask bit = 1
// means the corresponding ID bit must match the filter; mask bit = 0 means
// "don't care". The device must be in Configuration mode.
func (f *MCP2515Full) SetMask(maskNum uint8, mask uint32, extended bool) error {
	return f.setMask(maskNum, mask, extended)
}

// SetRxMode sets RXM[1:0] on RXBnCTRL for buf=0 (RXB0CTRL) or buf=1
// (RXB1CTRL). mode is the 2-bit RXM value: 0=standard filter match,
// 1=extended filter match, 3=accept all (other values reserved per
// datasheet §10.2). Other RXBnCTRL bits are preserved via BIT MODIFY.
func (f *MCP2515Full) SetRxMode(buf uint8, mode uint8) error {
	return f.setRxMode(buf, mode)
}

// SetMode requests an operating mode. mode is one of OPMODNormal,
// OPMODSleep, OPMODLoopback, OPMODListenOnly, OPMODConfig. Polls CANSTAT
// until OPMOD matches the requested value (max 100 ms).
func (f *MCP2515Full) SetMode(mode uint8) error {
	return f.setMode(mode)
}

// GetMode reads CANSTAT and returns OPMOD[7:5] — one of OPMODNormal,
// OPMODSleep, OPMODLoopback, OPMODListenOnly, OPMODConfig.
func (f *MCP2515Full) GetMode() (uint8, error) {
	return f.getMode()
}

// Reset issues the SPI RESET instruction. The device returns to
// Configuration mode and all registers are restored to their POR values.
// Call Init again afterwards to re-program bit timing.
func (f *MCP2515Full) Reset() error { return f.reset() }

// ReadErrors reads TEC (transmit error counter), REC (receive error
// counter), and EFLG (error flags register). Returns (tec, rec, eflg, err).
func (f *MCP2515Full) ReadErrors() (tec, rec, eflg uint8, err error) {
	tec, err = f.readReg(regTEC)
	if err != nil {
		return 0, 0, 0, err
	}
	rec, err = f.readReg(regREC)
	if err != nil {
		return 0, 0, 0, err
	}
	eflg, err = f.readReg(regEFLG)
	if err != nil {
		return 0, 0, 0, err
	}
	return tec, rec, eflg, nil
}

// ClearOverflow clears the RX0OVR (buf=0) or RX1OVR (buf=1) flag in EFLG.
// Must be called before the next frame can be received into the
// overflowed buffer.
func (f *MCP2515Full) ClearOverflow(buf uint8) error {
	return f.clearOverflow(buf)
}

// AbortTx sets ABAT in CANCTRL to abort all pending TX. Polls ABAT until
// the hardware clears it.
func (f *MCP2515Full) AbortTx() error {
	return f.abortTx()
}

// SetOneShot sets (enable=true) or clears (enable=false) OSM in CANCTRL.
// OSM=1 disables retransmission on error or loss of arbitration.
func (f *MCP2515Full) SetOneShot(enable bool) error {
	return f.setOneShot(enable)
}

// --- private helpers on MCP2515Minimal ---

// readReg reads a single register.
func (c *MCP2515Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := c.conn.WriteRead([]byte{instrRead, reg}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// writeReg writes a single register.
func (c *MCP2515Minimal) writeReg(reg, value uint8) error {
	return c.conn.Write([]byte{instrWrite, reg, value})
}

// modifyReg performs a BIT MODIFY on reg: only bits set in mask are
// affected, set to the corresponding bits in value. Other register bits
// are preserved.
func (c *MCP2515Minimal) modifyReg(reg, mask, value uint8) error {
	return c.conn.Write([]byte{instrBitModify, reg, mask, value})
}

// readStatus issues READ STATUS and returns the byte.
func (c *MCP2515Minimal) readStatus() (uint8, error) {
	buf, err := c.conn.WriteRead([]byte{instrReadStatus}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

// rts issues RTS with the given mask (bit 0=TXB0, 1=TXB1, 2=TXB2).
func (c *MCP2515Minimal) rts(mask uint8) error {
	return c.conn.Write([]byte{instrRTS | (mask & 0x07)})
}

// waitOpMode polls CANSTAT until OPMOD[7:5] matches target (==). Polls
// every 5 ms up to timeoutMs.
func (c *MCP2515Minimal) waitOpMode(target uint8, timeoutMs uint32) error {
	deadline := time.Duration(timeoutMs) * time.Millisecond
	const step = 5 * time.Millisecond
	elapsed := time.Duration(0)
	for {
		mode, err := c.getMode()
		if err != nil {
			return err
		}
		if mode == target {
			return nil
		}
		if elapsed >= deadline {
			return fmt.Errorf("mcp2515: waitOpMode(0x%02X) timed out after %d ms (got 0x%02X)", target, timeoutMs, mode)
		}
		time.Sleep(step)
		elapsed += step
	}
}

// send is the common send routine: pack id, load TX buffer bufIndex, RTS.
// rtr sets the SRTR/RTR bit; Minimal.Send always passes rtr=false.
func (c *MCP2515Minimal) send(id uint32, data []byte, extended, rtr bool, bufIndex uint8) error {
	if bufIndex > 2 {
		return fmt.Errorf("mcp2515: buf_index %d out of range [0,2]", bufIndex)
	}
	if len(data) > 8 {
		return fmt.Errorf("mcp2515: data length %d exceeds 8 bytes", len(data))
	}
	if extended && id > 0x1FFFFFFF {
		return fmt.Errorf("mcp2515: extended id 0x%08X exceeds 29 bits", id)
	}
	if !extended && id > 0x7FF {
		return fmt.Errorf("mcp2515: standard id 0x%X exceeds 11 bits", id)
	}

	sidh, sidl, eid8, eid0 := c.packID(id, extended)
	dlc := uint8(len(data))
	if rtr {
		dlc |= 0x40
	}

	ctrlBase := txbCtrlBases[bufIndex]

	// LOAD TX BUFFER: command byte = instrLoadTxBuf | offset; offset 0 → SIDH
	// at the chosen buffer's SIDH base. We always load starting at SIDH.
	load := []byte{instrLoadTxBuf | 0x00, sidh, sidl, eid8, eid0, dlc}
	load = append(load, data...)
	// Pad data to 8 bytes so the chip's TX buffer is in a defined state.
	for len(load) < 6+8 {
		load = append(load, 0x00)
	}
	if err := c.conn.Write(load); err != nil {
		return err
	}

	// Request to send on this buffer.
	if err := c.rts(1 << bufIndex); err != nil {
		return err
	}

	// Poll TXBnCTRL until TXREQ clears, max 10 ms.
	deadline := 10 * time.Millisecond
	const step = 1 * time.Millisecond
	elapsed := time.Duration(0)
	for {
		ctrl, err := c.readReg(ctrlBase)
		if err != nil {
			return err
		}
		if ctrl&txbctrlTXREQ == 0 {
			return nil
		}
		if elapsed >= deadline {
			return fmt.Errorf("mcp2515: TXB%d TXREQ did not clear within 10 ms", bufIndex)
		}
		time.Sleep(step)
		elapsed += step
	}
}

// pollRx polls READ STATUS for RX0IF/RX1IF and reads the corresponding
// RX buffer using the READ RX BUFFER fast instruction (which also clears
// the IR flag at the falling edge of CS).
func (c *MCP2515Minimal) pollRx(timeoutMs uint32) (*CanFrame, error) {
	deadline := time.Duration(timeoutMs) * time.Millisecond
	const step = 5 * time.Millisecond
	elapsed := time.Duration(0)

	for {
		status, err := c.readStatus()
		if err != nil {
			return nil, err
		}
		if status&readStatusRX0IF != 0 {
			return c.readRxBuf(0)
		}
		if status&readStatusRX1IF != 0 {
			return c.readRxBuf(1)
		}
		if elapsed >= deadline {
			return nil, nil
		}
		time.Sleep(step)
		elapsed += step
	}
}

// readRxBuf reads an RX buffer using READ RX BUFFER. n=0 reads RXB0SIDH,
// n=2 reads RXB0D0; n=4 reads RXB1SIDH, n=6 reads RXB1D0.
func (c *MCP2515Minimal) readRxBuf(buf uint8) (*CanFrame, error) {
	var offset uint8
	if buf == 0 {
		offset = 0x00
	} else {
		offset = 0x04
	}
	// RX buffer layout: SIDH(1) SIDL(1) EID8(1) EID0(1) DLC(1) data(8) = 13 bytes
	buf13, err := c.conn.WriteRead([]byte{instrReadRxBuf | offset}, 13)
	if err != nil {
		return nil, err
	}
	sidh := buf13[0]
	sidl := buf13[1]
	eid8 := buf13[2]
	eid0 := buf13[3]
	dlc := buf13[4]
	copy(c.rxDataBuf[:], buf13[5:13])

	extended := sidl&txbsidlEXIDE != 0
	var rtr bool
	var dataLen uint8
	if extended {
		rtr = dlc&0x40 != 0
		dataLen = dlc & 0x0F
	} else {
		rtr = sidl&0x10 != 0
		dataLen = dlc & 0x0F
	}
	if dataLen > 8 {
		dataLen = 8
	}

	id := c.unpackID(sidh, sidl, eid8, eid0, extended)
	out := make([]byte, dataLen)
	copy(out, c.rxDataBuf[:dataLen])
	return &CanFrame{
		ID:       id,
		Data:     out,
		Extended: extended,
		RTR:      rtr,
	}, nil
}

// setMode writes CANCTRL REQOP[7:5] = mode & 0xE0 and polls CANSTAT until
// the device confirms the transition.
func (c *MCP2515Minimal) setMode(mode uint8) error {
	if err := c.modifyReg(regCANCTRL, 0xE0, mode&0xE0); err != nil {
		return err
	}
	return c.waitOpMode(mode, 100)
}

// getMode reads CANSTAT and returns OPMOD[7:5] (the bits in mask 0xE0).
func (c *MCP2515Minimal) getMode() (uint8, error) {
	stat, err := c.readReg(regCANSTAT)
	if err != nil {
		return 0, err
	}
	return stat & 0xE0, nil
}

// setFilter writes one acceptance filter at filterBases[filterNum]. The
// device must be in Configuration mode; the function transparently
// switches to Configuration, writes the filter, and returns to the
// previous operating mode.
func (c *MCP2515Minimal) setFilter(n uint8, id uint32, extended bool) error {
	if n > 5 {
		return fmt.Errorf("mcp2515: filter_num %d out of range [0,5]", n)
	}
	prevMode, err := c.getMode()
	if err != nil {
		return err
	}
	if err := c.setMode(OPMODConfig); err != nil {
		return err
	}
	sidh, sidl, eid8, eid0 := c.packID(id, extended)
	base := filterBases[n]
	if err := c.writeReg(base+0, sidh); err != nil {
		return err
	}
	if err := c.writeReg(base+1, sidl); err != nil {
		return err
	}
	if err := c.writeReg(base+2, eid8); err != nil {
		return err
	}
	if err := c.writeReg(base+3, eid0); err != nil {
		return err
	}
	return c.setMode(prevMode)
}

// setMask writes one acceptance mask at RXM0SIDH or RXM1SIDH. The device
// must be in Configuration mode; the function transparently switches to
// Configuration, writes the mask, and returns to the previous operating
// mode.
func (c *MCP2515Minimal) setMask(n uint8, mask uint32, extended bool) error {
	if n > 1 {
		return fmt.Errorf("mcp2515: mask_num %d out of range [0,1]", n)
	}
	prevMode, err := c.getMode()
	if err != nil {
		return err
	}
	if err := c.setMode(OPMODConfig); err != nil {
		return err
	}
	sidh, sidl, eid8, eid0 := c.packID(mask, extended)
	var base uint8
	if n == 0 {
		base = regRXM0SIDH
	} else {
		base = regRXM1SIDH
	}
	if err := c.writeReg(base+0, sidh); err != nil {
		return err
	}
	if err := c.writeReg(base+1, sidl); err != nil {
		return err
	}
	if err := c.writeReg(base+2, eid8); err != nil {
		return err
	}
	if err := c.writeReg(base+3, eid0); err != nil {
		return err
	}
	return c.setMode(prevMode)
}

// setRxMode sets RXM[1:0] on RXBnCTRL for the given buf.
func (c *MCP2515Minimal) setRxMode(buf uint8, mode uint8) error {
	var reg uint8
	if buf == 0 {
		reg = regRXB0CTRL
	} else if buf == 1 {
		reg = regRXB1CTRL
	} else {
		return fmt.Errorf("mcp2515: rx_buf %d out of range [0,1]", buf)
	}
	if mode > 0x03 {
		return fmt.Errorf("mcp2515: rx mode %d out of range [0,3]", mode)
	}
	return c.modifyReg(reg, 0x60, (mode&0x03)<<5)
}

// reset issues the SPI RESET instruction. The device enters Configuration
// mode; the caller must wait >= 2 us before further SPI traffic.
func (c *MCP2515Minimal) reset() error {
	return c.conn.Write([]byte{instrReset})
}

// setOneShot toggles OSM in CANCTRL.
func (c *MCP2515Minimal) setOneShot(enable bool) error {
	if enable {
		return c.modifyReg(regCANCTRL, canctrlOSM, canctrlOSM)
	}
	return c.modifyReg(regCANCTRL, canctrlOSM, 0x00)
}

// clearOverflow clears RX0OVR or RX1OVR in EFLG via BIT MODIFY.
func (c *MCP2515Minimal) clearOverflow(buf uint8) error {
	var bit uint8
	if buf == 0 {
		bit = eflgRX0OVR
	} else if buf == 1 {
		bit = eflgRX1OVR
	} else {
		return fmt.Errorf("mcp2515: rx_buf %d out of range [0,1]", buf)
	}
	// EFLG is bit-modifiable per the datasheet's Table 11-1.
	return c.modifyReg(regEFLG, bit, 0x00)
}

// abortTx sets ABAT in CANCTRL and polls until the hardware clears it.
func (c *MCP2515Minimal) abortTx() error {
	if err := c.modifyReg(regCANCTRL, canctrlABAT, canctrlABAT); err != nil {
		return err
	}
	deadline := 10 * time.Millisecond
	const step = 1 * time.Millisecond
	elapsed := time.Duration(0)
	for {
		ctrl, err := c.readReg(regCANCTRL)
		if err != nil {
			return err
		}
		if ctrl&canctrlABAT == 0 {
			return nil
		}
		if elapsed >= deadline {
			return fmt.Errorf("mcp2515: ABAT did not clear within 10 ms")
		}
		time.Sleep(step)
		elapsed += step
	}
}

// packID returns (SIDH, SIDL, EID8, EID0) for the given id.
//
// For a standard frame: SIDH = id >> 3, SIDL = (id & 0x7) << 5 with
// EXIDE=0 and the EID bits zero. For an extended frame: the SIDL EXIDE
// bit is set and the ID is split across the four ID registers per §Data
// Conversion in the spec.
func (c *MCP2515Minimal) packID(id uint32, extended bool) (sidh, sidl, eid8, eid0 uint8) {
	if !extended {
		sidh = uint8((id >> 3) & 0xFF)
		sidl = uint8(id&0x07) << 5
		return sidh, sidl, 0x00, 0x00
	}
	sidh = uint8((id >> 21) & 0xFF)
	sidl = uint8((id>>18)&0x07)<<5 | txbsidlEXIDE | uint8((id>>16)&0x03)
	eid8 = uint8((id >> 8) & 0xFF)
	eid0 = uint8(id & 0xFF)
	return sidh, sidl, eid8, eid0
}

// unpackID reverses packID, given the SIDH/SIDL/EID8/EID0 quartet and
// whether the frame is extended (taken from the SIDL EXIDE bit, not from
// the caller).
func (c *MCP2515Minimal) unpackID(sidh, sidl, eid8, eid0 uint8, ide bool) uint32 {
	if !ide {
		return uint32(sidh)<<3 | uint32(sidl)>>5
	}
	return uint32(sidh)<<21 |
		(uint32(sidl)&0xE0)<<13 |
		(uint32(sidl)&0x03)<<16 |
		uint32(eid8)<<8 |
		uint32(eid0)
}
