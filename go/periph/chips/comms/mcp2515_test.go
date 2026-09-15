package comms

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// Supports the access patterns the MCP2515 driver uses:
//   - Register-addressed reads (WriteRead([]byte{instr, reg}, n)): backed by
//     a byte-addressable registers map. Preload static values with
//     setRegister; preload a one-shot sequence of values for a single
//     register read with queueRegisterRead (the next read of that register
//     pops one byte, then falls back to registers).
//   - Plain streamed reads (WriteRead([]byte{instr}, n) when len(data)==1,
//     or Read(n)): backed by a FIFO queue. Preload responses with
//     queueRead; each call pops the next one. Falls back to n zero bytes
//     if the queue is empty.
//
// Every Write call (register writes, plain command writes, and full
// multi-byte transfers alike) is appended to writes for assertions.
type mockConnection struct {
	registers       map[byte]byte
	writes          [][]byte
	readQueue       [][]byte
	registerReadSeq map[byte][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{
		registers:       map[byte]byte{},
		registerReadSeq: map[byte][]byte{},
	}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mockConnection) queueRead(data []byte) {
	m.readQueue = append(m.readQueue, data)
}

// queueRegisterRead enqueues a sequence of byte values to be returned one
// per read of reg. Useful when a register is polled and must transition
// (e.g. CANSTAT going from Config to Normal during waitOpMode).
func (m *mockConnection) queueRegisterRead(reg byte, values ...byte) {
	m.registerReadSeq[reg] = append(m.registerReadSeq[reg], values...)
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	// Update the registers map so post-write assertions see the new state.
	// - WRITE (0x02): [instr, reg, value]
	// - BIT MODIFY (0x05): [instr, reg, mask, value] — only bits in mask flip
	if len(data) >= 3 {
		switch data[0] {
		case instrWrite:
			m.registers[data[1]] = data[2]
		case instrBitModify:
			cur := m.registers[data[1]]
			m.registers[data[1]] = (cur &^ data[2]) | (data[3] & data[2])
		}
	}
	return nil
}

func (m *mockConnection) Read(n int) ([]byte, error) {
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		out := make([]byte, n)
		copy(out, front)
		return out, nil
	}
	return make([]byte, n), nil
}

func (m *mockConnection) WriteRead(data []byte, n int) ([]byte, error) {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	out := make([]byte, n)
	// Register-addressed reads (READ instruction: data=[instr, reg]).
	if len(data) >= 2 {
		reg := data[1]
		for i := 0; i < n; i++ {
			addr := reg + byte(i)
			if seq, ok := m.registerReadSeq[addr]; ok && len(seq) > 0 {
				out[i] = seq[0]
				m.registerReadSeq[addr] = seq[1:]
			} else {
				out[i] = m.registers[addr]
			}
		}
		return out, nil
	}
	// Instruction-only reads (READ STATUS, READ RX BUFFER): use the FIFO.
	if len(m.readQueue) > 0 {
		front := m.readQueue[0]
		m.readQueue = m.readQueue[1:]
		copy(out, front)
		return out, nil
	}
	return out, nil
}

func (m *mockConnection) Close() error                    { return nil }
func (m *mockConnection) Enable()                         {}
func (m *mockConnection) Disable()                        {}
func (m *mockConnection) IsEnabled() bool                 { return true }
func (m *mockConnection) IntPin() connection.InputPin      { return nil }
func (m *mockConnection) EnPin() connection.OutputPin      { return nil }

// lastWriteWithInstruction returns the last write whose first byte equals
// the given SPI instruction.
func lastWriteWithInstruction(writes [][]byte, instr uint8) []byte {
	var last []byte
	for _, w := range writes {
		if len(w) >= 1 && w[0] == instr {
			last = w
		}
	}
	return last
}

// initModeForTest sets up the mock so that NewMCP2515Minimal's Init
// sequence completes successfully without timing out. After Init,
// CANSTAT returns Normal; before Init (after RESET), it returns Config.
//
// The Init flow polls CANSTAT twice: first for Config (after RESET),
// then for Normal (after requesting the mode change). We queue the two
// target values so the first poll of each waitOpMode succeeds
// immediately. Steady-state reads fall through to registers, which
// holds Normal.
func initModeForTest(conn *mockConnection) {
	// First waitOpMode (looking for Config) reads Config from the queue.
	// Second waitOpMode (looking for Normal) reads Normal from the queue.
	conn.queueRegisterRead(regCANSTAT, OPMODConfig, OPMODNormal)
	// Steady-state: CANSTAT reads return Normal after Init completes.
	conn.registers[regCANSTAT] = OPMODNormal
}

func TestMCP2515NewRejectsBadBitrate(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	if _, err := NewMCP2515Minimal(conn, 100, 8); err == nil {
		t.Errorf("expected error for unsupported bitrate 100 kbit/s")
	}
	if _, err := NewMCP2515Minimal(conn, 125, 12); err == nil {
		t.Errorf("expected error for unsupported oscillator 12 MHz")
	}
}

func TestMCP2515NewIssuesResetAndConfigures(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)

	if _, err := NewMCP2515Minimal(conn, 125, 8); err != nil {
		t.Fatalf("NewMCP2515Minimal: %v", err)
	}

	// RESET instruction was issued.
	if w := lastWriteWithInstruction(conn.writes, instrReset); w == nil {
		t.Errorf("expected RESET instruction in writes")
	}

	// CNF1, CNF2, CNF3 were written with 125 kbit/s @ 8 MHz values.
	if conn.registers[regCNF1] != 0x01 || conn.registers[regCNF2] != 0xBA || conn.registers[regCNF3] != 0x03 {
		t.Errorf("expected CNF={0x01,0xBA,0x03}, got CNF1=0x%02X CNF2=0x%02X CNF3=0x%02X",
			conn.registers[regCNF1], conn.registers[regCNF2], conn.registers[regCNF3])
	}

	// RXM0 and RXM1 were set to all-ones (exact filter match).
	for i := uint8(0); i < 4; i++ {
		if conn.registers[regRXM0SIDH+i] != 0xFF {
			t.Errorf("expected RXM0SIDH+%d=0xFF, got 0x%02X", i, conn.registers[regRXM0SIDH+i])
		}
		if conn.registers[regRXM1SIDH+i] != 0xFF {
			t.Errorf("expected RXM1SIDH+%d=0xFF, got 0x%02X", i, conn.registers[regRXM1SIDH+i])
		}
	}

	// RXB0CTRL has RXM[1:0]=11 (0x60) and BUKT=1 (0x04) = 0x64.
	if conn.registers[regRXB0CTRL] != 0x64 {
		t.Errorf("expected RXB0CTRL=0x64, got 0x%02X", conn.registers[regRXB0CTRL])
	}
	// RXB1CTRL has RXM[1:0]=11 (0x60).
	if conn.registers[regRXB1CTRL] != 0x60 {
		t.Errorf("expected RXB1CTRL=0x60, got 0x%02X", conn.registers[regRXB1CTRL])
	}

	// CANINTE left at 0x00 (polled).
	if conn.registers[regCANINTE] != 0x00 {
		t.Errorf("expected CANINTE=0x00, got 0x%02X", conn.registers[regCANINTE])
	}

	// TXBnCTRL priority TXP=11 for all three buffers.
	for _, base := range txbCtrlBases {
		if (conn.registers[base] & 0x03) != 0x03 {
			t.Errorf("expected TXB at 0x%02X to have TXP=11, got 0x%02X", base, conn.registers[base])
		}
	}
}

func TestMCP2515PackUnpackStandard(t *testing.T) {
	c := &MCP2515Minimal{}
	id := uint32(0x4A3)
	sidh, sidl, eid8, eid0 := c.packID(id, false)
	if sidh != 0x94 || sidl != 0x60 || eid8 != 0x00 || eid0 != 0x00 {
		t.Errorf("packID(0x4A3, std) = (%02X,%02X,%02X,%02X), want (94,60,00,00)", sidh, sidl, eid8, eid0)
	}
	if got := c.unpackID(sidh, sidl, eid8, eid0, false); got != id {
		t.Errorf("unpackID round-trip = 0x%X, want 0x%X", got, id)
	}
}

func TestMCP2515PackUnpackExtended(t *testing.T) {
	c := &MCP2515Minimal{}
	id := uint32(0x1ABCDEF0)
	sidh, sidl, eid8, eid0 := c.packID(id, true)
	// EXIDE bit (0x08) must be set in SIDL.
	if sidl&txbsidlEXIDE == 0 {
		t.Errorf("packID(ext): EXIDE bit not set in SIDL=0x%02X", sidl)
	}
	if got := c.unpackID(sidh, sidl, eid8, eid0, true); got != id {
		t.Errorf("unpackID round-trip = 0x%X, want 0x%X", got, id)
	}

	// Boundary: max 29-bit ID.
	id = 0x1FFFFFFF
	sidh, sidl, eid8, eid0 = c.packID(id, true)
	if got := c.unpackID(sidh, sidl, eid8, eid0, true); got != id {
		t.Errorf("unpackID(0x1FFFFFFF) = 0x%X, want 0x%X", got, id)
	}
}

func TestMCP2515SendIssuesLoadAndRTS(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	// TXREQ clears (bit 3 in TXB0CTRL = 0).
	conn.registers[regTXB0CTRL] = 0x03 // TXP=11, TXREQ=0

	chip, err := NewMCP2515Minimal(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Minimal: %v", err)
	}
	conn.writes = nil // discard init writes
	if err := chip.Send(0x123, []byte{0xDE, 0xAD, 0xBE, 0xEF}, false); err != nil {
		t.Fatalf("Send: %v", err)
	}

	// First the LOAD TX BUFFER command (cmd byte 0x40, SIDH=0x24, SIDL=0x60,
	// EID8=0x00, EID0=0x00, DLC=4, data=DEADBEEF, padded to 8 zeros).
	// sidh = 0x123 >> 3 = 0x24, sidl = (0x123 & 0x7) << 5 = 0x60.
	load := lastWriteWithInstruction(conn.writes, instrLoadTxBuf)
	if load == nil {
		t.Fatalf("expected LOAD TX BUFFER instruction in writes")
	}
	if load[1] != 0x24 || load[2] != 0x60 {
		t.Errorf("LOAD TX BUFFER ID bytes: got SIDH=0x%02X SIDL=0x%02X, want 0x24 0x60", load[1], load[2])
	}
	if load[5] != 0x04 {
		t.Errorf("LOAD TX BUFFER DLC: got 0x%02X, want 0x04", load[5])
	}
	if load[6] != 0xDE || load[7] != 0xAD || load[8] != 0xBE || load[9] != 0xEF {
		t.Errorf("LOAD TX BUFFER data: got %02X %02X %02X %02X, want DE AD BE EF",
			load[6], load[7], load[8], load[9])
	}

	// Then the RTS instruction with bit 0 set (TXB0).
	rts := lastWriteWithInstruction(conn.writes, instrRTS)
	if rts == nil {
		t.Fatalf("expected RTS instruction in writes")
	}
	if rts[0] != (instrRTS | 0x01) {
		t.Errorf("RTS cmd byte = 0x%02X, want 0x%02X", rts[0], instrRTS|0x01)
	}
}

func TestMCP2515SendExtendedSetsEXIDE(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	conn.registers[regTXB0CTRL] = 0x03
	chip, err := NewMCP2515Minimal(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Minimal: %v", err)
	}
	conn.writes = nil
	if err := chip.Send(0x1ABCDE, []byte{0x01}, true); err != nil {
		t.Fatalf("Send: %v", err)
	}
	load := lastWriteWithInstruction(conn.writes, instrLoadTxBuf)
	if load == nil {
		t.Fatalf("expected LOAD TX BUFFER instruction")
	}
	// SIDL must have EXIDE bit (0x08) set for extended frames.
	if load[2]&txbsidlEXIDE == 0 {
		t.Errorf("extended frame: SIDL=0x%02X missing EXIDE bit", load[2])
	}
}

func TestMCP2515SendRejectsTooLong(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	chip, err := NewMCP2515Minimal(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Minimal: %v", err)
	}
	if err := chip.Send(0x100, make([]byte, 9), false); err == nil {
		t.Errorf("expected error for 9-byte payload")
	}
}

func TestMCP2515SendRejectsBadID(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	chip, err := NewMCP2515Minimal(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Minimal: %v", err)
	}
	if err := chip.Send(0x800, []byte{0x00}, false); err == nil {
		t.Errorf("expected error for standard id > 11 bits")
	}
	if err := chip.Send(0x20000000, []byte{0x00}, true); err == nil {
		t.Errorf("expected error for extended id > 29 bits")
	}
}

func TestMCP2515RecvParsesFrame(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	chip, err := NewMCP2515Minimal(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Minimal: %v", err)
	}
	conn.writes = nil

	// First READ STATUS returns RX0IF set; subsequent ones return 0.
	conn.queueRead([]byte{readStatusRX0IF})
	// Then READ RX BUFFER (offset 0): SIDH, SIDL, EID8, EID0, DLC, data[8].
	// id=0x123 standard: SIDH=0x24, SIDL=0x60, EID8=0, EID0=0, DLC=4, data=DEADBEEF 0000.
	conn.queueRead([]byte{0x24, 0x60, 0x00, 0x00, 0x04, 0xDE, 0xAD, 0xBE, 0xEF, 0, 0, 0, 0})

	frame, err := chip.Recv(100)
	if err != nil {
		t.Fatalf("Recv: %v", err)
	}
	if frame == nil {
		t.Fatalf("Recv returned nil, expected frame")
	}
	if frame.ID != 0x123 {
		t.Errorf("frame.ID = 0x%X, want 0x123", frame.ID)
	}
	if frame.Extended {
		t.Errorf("frame.Extended = true, want false")
	}
	if len(frame.Data) != 4 || frame.Data[0] != 0xDE || frame.Data[3] != 0xEF {
		t.Errorf("frame.Data = %v, want DEADBEEF (4 bytes)", frame.Data)
	}
}

func TestMCP2515RecvNoFrameReturnsNil(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	chip, err := NewMCP2515Minimal(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Minimal: %v", err)
	}
	// Recv(10) polls every 5 ms for up to 10 ms — that's up to 2-3 READ STATUS
	// calls. Queue enough zero-byte responses to cover all polls; fall
	// through to zero bytes if exhausted.
	for i := 0; i < 5; i++ {
		conn.queueRead([]byte{0x00})
	}
	frame, err := chip.Recv(10)
	if err != nil {
		t.Fatalf("Recv: %v", err)
	}
	if frame != nil {
		t.Errorf("Recv returned %v, want nil on no frame", frame)
	}
}

func TestMCP2515SetFilterWritesAtBase(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	full, err := NewMCP2515Full(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Full: %v", err)
	}
	// SetFilter transparently switches to Configuration and back. Queue
	// one Config value for the SetMode(Config) poll, then back to Normal
	// (already the steady-state value).
	conn.queueRegisterRead(regCANSTAT, OPMODConfig)
	conn.writes = nil
	if err := full.SetFilter(2, 0x123, false); err != nil {
		t.Fatalf("SetFilter: %v", err)
	}
	// filter 2 base is 0x08; SIDH=0x24, SIDL=0x60.
	if conn.registers[0x08] != 0x24 || conn.registers[0x09] != 0x60 {
		t.Errorf("SetFilter(2): registers 0x08=0x%02X 0x09=0x%02X, want 0x24 0x60",
			conn.registers[0x08], conn.registers[0x09])
	}
}

func TestMCP2515SetMaskWritesAtRXM0(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	full, err := NewMCP2515Full(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Full: %v", err)
	}
	conn.queueRegisterRead(regCANSTAT, OPMODConfig)
	conn.writes = nil
	if err := full.SetMask(0, 0x7FF, false); err != nil {
		t.Fatalf("SetMask: %v", err)
	}
	// mask=0x7FF standard: SIDH=0xFF, SIDL=(0x7<<5)=0xE0.
	if conn.registers[regRXM0SIDH] != 0xFF || conn.registers[regRXM0SIDH+1] != 0xE0 {
		t.Errorf("SetMask(0): registers 0x%02X=0x%02X 0x%02X=0x%02X, want 0xFF 0xE0",
			regRXM0SIDH, conn.registers[regRXM0SIDH], regRXM0SIDH+1, conn.registers[regRXM0SIDH+1])
	}
}

func TestMCP2515SetRxModeIssuesBitModify(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	full, err := NewMCP2515Full(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Full: %v", err)
	}
	conn.writes = nil
	if err := full.SetRxMode(0, 0x03); err != nil {
		t.Fatalf("SetRxMode: %v", err)
	}
	bm := lastWriteWithInstruction(conn.writes, instrBitModify)
	if bm == nil {
		t.Fatalf("expected BIT MODIFY instruction")
	}
	if bm[1] != regRXB0CTRL {
		t.Errorf("BIT MODIFY reg = 0x%02X, want 0x%02X", bm[1], regRXB0CTRL)
	}
	if bm[2] != 0x60 || bm[3] != 0x60 {
		t.Errorf("BIT MODIFY mask=0x%02X data=0x%02X, want 0x60 0x60", bm[2], bm[3])
	}
}

func TestMCP2515SetOneShotTogglesOSM(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	full, err := NewMCP2515Full(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Full: %v", err)
	}
	conn.writes = nil

	if err := full.SetOneShot(true); err != nil {
		t.Fatalf("SetOneShot(true): %v", err)
	}
	bm := lastWriteWithInstruction(conn.writes, instrBitModify)
	if bm == nil || bm[2] != canctrlOSM || bm[3] != canctrlOSM {
		t.Errorf("SetOneShot(true) expected BIT MODIFY 0x%02X 0x%02X, got %v", canctrlOSM, canctrlOSM, bm)
	}

	if err := full.SetOneShot(false); err != nil {
		t.Fatalf("SetOneShot(false): %v", err)
	}
	bm = lastWriteWithInstruction(conn.writes, instrBitModify)
	if bm == nil || bm[2] != canctrlOSM || bm[3] != 0x00 {
		t.Errorf("SetOneShot(false) expected BIT MODIFY 0x%02X 0x00, got %v", canctrlOSM, bm)
	}
}

func TestMCP2515ClearOverflowWritesEFLG(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	full, err := NewMCP2515Full(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Full: %v", err)
	}
	conn.writes = nil

	if err := full.ClearOverflow(0); err != nil {
		t.Fatalf("ClearOverflow(0): %v", err)
	}
	bm := lastWriteWithInstruction(conn.writes, instrBitModify)
	if bm == nil || bm[1] != regEFLG {
		t.Errorf("ClearOverflow(0) expected BIT MODIFY on EFLG, got %v", bm)
	}
	if bm[2] != eflgRX0OVR || bm[3] != 0x00 {
		t.Errorf("ClearOverflow(0) expected mask=0x%02X data=0x00, got 0x%02X 0x%02X", eflgRX0OVR, bm[2], bm[3])
	}

	if err := full.ClearOverflow(1); err != nil {
		t.Fatalf("ClearOverflow(1): %v", err)
	}
	bm = lastWriteWithInstruction(conn.writes, instrBitModify)
	if bm == nil || bm[2] != eflgRX1OVR || bm[3] != 0x00 {
		t.Errorf("ClearOverflow(1) expected mask=0x%02X data=0x00, got 0x%02X 0x%02X", eflgRX1OVR, bm[2], bm[3])
	}
}

func TestMCP2515ReadErrors(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	conn.registers[regTEC] = 0x05
	conn.registers[regREC] = 0x03
	conn.registers[regEFLG] = 0x01
	full, err := NewMCP2515Full(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Full: %v", err)
	}
	tec, rec, eflg, err := full.ReadErrors()
	if err != nil {
		t.Fatalf("ReadErrors: %v", err)
	}
	if tec != 5 || rec != 3 || eflg != 1 {
		t.Errorf("ReadErrors = (%d, %d, 0x%02X), want (5, 3, 0x01)", tec, rec, eflg)
	}
}

func TestMCP2515GetMode(t *testing.T) {
	conn := newMockConnection()
	initModeForTest(conn)
	full, err := NewMCP2515Full(conn, 125, 8)
	if err != nil {
		t.Fatalf("NewMCP2515Full: %v", err)
	}
	// SetMode(Loopback): the chip transitions CANSTAT to Loopback. Queue
	// the read of CANSTAT during the SetMode poll, and update the static
	// register so GetMode sees the same value.
	conn.queueRegisterRead(regCANSTAT, OPMODLoopback)
	if err := full.SetMode(OPMODLoopback); err != nil {
		t.Fatalf("SetMode(Loopback): %v", err)
	}
	conn.registers[regCANSTAT] = OPMODLoopback
	mode, err := full.GetMode()
	if err != nil {
		t.Fatalf("GetMode: %v", err)
	}
	if mode != OPMODLoopback {
		t.Errorf("GetMode = 0x%02X, want 0x%02X", mode, OPMODLoopback)
	}
}
