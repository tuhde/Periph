package light

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// mockConnection is an in-memory fake connection.Connection for unit tests —
// no hardware, no bus.
//
// Supports the two access patterns chip drivers in this repo use:
//   - Register-addressed reads (WriteRead([]byte{reg}, n)): backed by a
//     byte-addressable registers map. Preload it with setRegister.
//   - Plain streamed reads (Read(n), no register address): backed by a FIFO
//     queue. Preload responses with queueRead; each Read(n) call pops the
//     next one. Falls back to n zero bytes if the queue is empty.
//
// Every Write call (register writes and plain command writes alike) is
// appended to writes for assertions, and 2+ byte writes are also applied to
// registers so a later WriteRead sees them.
type mockConnection struct {
	registers map[byte]byte
	writes    [][]byte
	readQueue [][]byte
}

func newMockConnection() *mockConnection {
	return &mockConnection{registers: map[byte]byte{}}
}

func (m *mockConnection) setRegister(reg byte, values ...byte) {
	for i, v := range values {
		m.registers[reg+byte(i)] = v
	}
}

func (m *mockConnection) Write(data []byte) error {
	cp := append([]byte(nil), data...)
	m.writes = append(m.writes, cp)
	if len(data) >= 2 {
		reg := data[0]
		for i := 1; i < len(data); i++ {
			m.registers[reg+byte(i-1)] = data[i]
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
	reg := data[0]
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		out[i] = m.registers[reg+byte(i)]
	}
	return out, nil
}

func (m *mockConnection) Close() error                { return nil }
func (m *mockConnection) Enable()                     {}
func (m *mockConnection) Disable()                    {}
func (m *mockConnection) IsEnabled() bool             { return true }
func (m *mockConnection) IntPin() connection.InputPin { return nil }
func (m *mockConnection) EnPin() connection.OutputPin { return nil }

func TestAPDS9960FullAPI(t *testing.T) {
	conn := newMockConnection()
	// ID register must read back 0xAB or construction fails.
	conn.setRegister(apdsRegID, 0xAB)

	sensor, err := NewAPDS9960Full(conn)
	if err != nil {
		t.Fatalf("NewAPDS9960Full: %v", err)
	}

	if conn.registers[apdsRegATIME] != apdsATIMEDefault {
		t.Errorf("init: ATIME = 0x%02X, want 0x%02X", conn.registers[apdsRegATIME], apdsATIMEDefault)
	}
	if conn.registers[apdsRegCONTROL] != apdsCONTROLDefault {
		t.Errorf("init: CONTROL = 0x%02X, want 0x%02X", conn.registers[apdsRegCONTROL], apdsCONTROLDefault)
	}
	if conn.registers[apdsRegCONFIG2] != apdsCONFIG2Default {
		t.Errorf("init: CONFIG2 = 0x%02X, want 0x%02X", conn.registers[apdsRegCONFIG2], apdsCONFIG2Default)
	}
	if conn.registers[apdsRegENABLE] != 0x03 {
		t.Errorf("init: ENABLE = 0x%02X, want 0x03", conn.registers[apdsRegENABLE])
	}

	// A bad ID must reject construction.
	badConn := newMockConnection()
	badConn.setRegister(apdsRegID, 0x00)
	if _, err := NewAPDS9960Full(badConn); err == nil {
		t.Errorf("NewAPDS9960Full: expected error for bad chip ID")
	}

	// Color burst: clear=0x1234, red=0x0102, green=0x0304, blue=0x0506 (LE).
	conn.setRegister(apdsRegCDATAL, 0x34, 0x12, 0x02, 0x01, 0x04, 0x03, 0x06, 0x05)
	c, r, g, b, err := sensor.Color()
	if err != nil {
		t.Fatalf("Color: %v", err)
	}
	if c != 0x1234 || r != 0x0102 || g != 0x0304 || b != 0x0506 {
		t.Errorf("Color() = (%v, %v, %v, %v), want (0x1234, 0x0102, 0x0304, 0x0506)", c, r, g, b)
	}
	if v, _ := sensor.ColorClear(); v != 0x1234 {
		t.Errorf("ColorClear() = %v, want 0x1234", v)
	}
	if v, _ := sensor.ColorRed(); v != 0x0102 {
		t.Errorf("ColorRed() = %v, want 0x0102", v)
	}
	if v, _ := sensor.ColorGreen(); v != 0x0304 {
		t.Errorf("ColorGreen() = %v, want 0x0304", v)
	}
	if v, _ := sensor.ColorBlue(); v != 0x0506 {
		t.Errorf("ColorBlue() = %v, want 0x0506", v)
	}

	if err := sensor.EnableProximity(true); err != nil {
		t.Fatalf("EnableProximity: %v", err)
	}
	if conn.registers[apdsRegENABLE] != 0x07 {
		t.Errorf("EnableProximity(true): ENABLE = 0x%02X, want 0x07", conn.registers[apdsRegENABLE])
	}
	if err := sensor.EnableProximity(false); err != nil {
		t.Fatalf("EnableProximity: %v", err)
	}
	if conn.registers[apdsRegENABLE] != 0x03 {
		t.Errorf("EnableProximity(false): ENABLE = 0x%02X, want 0x03", conn.registers[apdsRegENABLE])
	}

	conn.setRegister(apdsRegPDATA, 200)
	if v, _ := sensor.Proximity(); v != 200 {
		t.Errorf("Proximity() = %v, want 200", v)
	}

	if err := sensor.EnableWait(true); err != nil {
		t.Fatalf("EnableWait: %v", err)
	}
	if conn.registers[apdsRegENABLE] != 0x0B {
		t.Errorf("EnableWait(true): ENABLE = 0x%02X, want 0x0B", conn.registers[apdsRegENABLE])
	}
	if err := sensor.EnableWait(false); err != nil {
		t.Fatalf("EnableWait: %v", err)
	}

	if err := sensor.ConfigureWait(100, true); err != nil {
		t.Fatalf("ConfigureWait: %v", err)
	}
	if conn.registers[apdsRegWTIME] != 100 || conn.registers[apdsRegCONFIG1] != 0x62 {
		t.Errorf("ConfigureWait(100, true): WTIME=0x%02X CONFIG1=0x%02X, want 0x64 0x62",
			conn.registers[apdsRegWTIME], conn.registers[apdsRegCONFIG1])
	}
	if err := sensor.ConfigureWait(50, false); err != nil {
		t.Fatalf("ConfigureWait: %v", err)
	}
	if conn.registers[apdsRegCONFIG1] != 0x60 {
		t.Errorf("ConfigureWait(50, false): CONFIG1 = 0x%02X, want 0x60", conn.registers[apdsRegCONFIG1])
	}

	if err := sensor.ConfigureALS(0xDB, 2); err != nil {
		t.Fatalf("ConfigureALS: %v", err)
	}
	if conn.registers[apdsRegATIME] != 0xDB || conn.registers[apdsRegCONTROL]&0x03 != 2 {
		t.Errorf("ConfigureALS: ATIME=0x%02X CONTROL&0x03=%d, want 0xDB 2",
			conn.registers[apdsRegATIME], conn.registers[apdsRegCONTROL]&0x03)
	}

	if err := sensor.ConfigureProximityLED(1, 2, 10, 3); err != nil {
		t.Fatalf("ConfigureProximityLED: %v", err)
	}
	ctrl := conn.registers[apdsRegCONTROL]
	if (ctrl>>6)&0x03 != 1 || (ctrl>>2)&0x03 != 2 {
		t.Errorf("ConfigureProximityLED: CONTROL = 0x%02X", ctrl)
	}
	if conn.registers[apdsRegPPULSE] != ((3 << 6) | 10) {
		t.Errorf("ConfigureProximityLED: PPULSE = 0x%02X, want 0xCA", conn.registers[apdsRegPPULSE])
	}

	if err := sensor.SetLEDBoost(2); err != nil {
		t.Fatalf("SetLEDBoost: %v", err)
	}
	if conn.registers[apdsRegCONFIG2] != ((2 << 4) | 0x01) {
		t.Errorf("SetLEDBoost: CONFIG2 = 0x%02X, want 0x21", conn.registers[apdsRegCONFIG2])
	}

	if err := sensor.AlsThreshold(0x1234, 0x5678); err != nil {
		t.Fatalf("AlsThreshold: %v", err)
	}
	if conn.registers[apdsRegAILTL] != 0x34 || conn.registers[apdsRegAILTH] != 0x12 ||
		conn.registers[apdsRegAIHTL] != 0x78 || conn.registers[apdsRegAIHTH] != 0x56 {
		t.Errorf("AlsThreshold: unexpected register values")
	}

	if err := sensor.ProximityThreshold(10, 200); err != nil {
		t.Fatalf("ProximityThreshold: %v", err)
	}
	if conn.registers[apdsRegPILT] != 10 || conn.registers[apdsRegPIHT] != 200 {
		t.Errorf("ProximityThreshold: unexpected register values")
	}

	if err := sensor.SetPersistence(5, 3); err != nil {
		t.Fatalf("SetPersistence: %v", err)
	}
	if conn.registers[apdsRegPERS] != ((5 << 4) | 3) {
		t.Errorf("SetPersistence: PERS = 0x%02X, want 0x53", conn.registers[apdsRegPERS])
	}

	if err := sensor.EnableAlsInterrupt(true); err != nil {
		t.Fatalf("EnableAlsInterrupt: %v", err)
	}
	if conn.registers[apdsRegENABLE]&0x10 == 0 {
		t.Errorf("EnableAlsInterrupt: AIEN not set")
	}
	if err := sensor.EnableProximityInterrupt(true); err != nil {
		t.Fatalf("EnableProximityInterrupt: %v", err)
	}
	if conn.registers[apdsRegENABLE]&0x20 == 0 {
		t.Errorf("EnableProximityInterrupt: PIEN not set")
	}

	if err := sensor.ClearProximityInterrupt(); err != nil {
		t.Fatalf("ClearProximityInterrupt: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 1 || last[0] != apdsRegPICLEAR {
		t.Errorf("ClearProximityInterrupt: last write = %v, want [0x%02X]", last, apdsRegPICLEAR)
	}
	if err := sensor.ClearAlsInterrupt(); err != nil {
		t.Fatalf("ClearAlsInterrupt: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 1 || last[0] != apdsRegCICLEAR {
		t.Errorf("ClearAlsInterrupt: last write = %v, want [0x%02X]", last, apdsRegCICLEAR)
	}
	if err := sensor.ClearAllInterrupts(); err != nil {
		t.Fatalf("ClearAllInterrupts: %v", err)
	}
	if last := conn.writes[len(conn.writes)-1]; len(last) != 1 || last[0] != apdsRegAICLEAR {
		t.Errorf("ClearAllInterrupts: last write = %v, want [0x%02X]", last, apdsRegAICLEAR)
	}

	// Sign-magnitude proximity offset encoding: -50 -> 0x80|50=0xB2, 100 -> 0x64.
	if err := sensor.SetProximityOffset(-50, 100); err != nil {
		t.Fatalf("SetProximityOffset: %v", err)
	}
	if conn.registers[apdsRegPOFFSET_UR] != 0xB2 || conn.registers[apdsRegPOFFSET_DL] != 0x64 {
		t.Errorf("SetProximityOffset: UR=0x%02X DL=0x%02X, want 0xB2 0x64",
			conn.registers[apdsRegPOFFSET_UR], conn.registers[apdsRegPOFFSET_DL])
	}

	if err := sensor.SetProximityMask(true, false, true, false); err != nil {
		t.Fatalf("SetProximityMask: %v", err)
	}
	if conn.registers[apdsRegCONFIG3] != (0x08 | 0x02) {
		t.Errorf("SetProximityMask: CONFIG3 = 0x%02X, want 0x0A", conn.registers[apdsRegCONFIG3])
	}

	if err := sensor.EnableGesture(true); err != nil {
		t.Fatalf("EnableGesture: %v", err)
	}
	if conn.registers[apdsRegENABLE]&0x40 == 0 || conn.registers[apdsRegGCONF4]&0x01 == 0 {
		t.Errorf("EnableGesture(true): GEN/GMODE not set")
	}
	if err := sensor.EnableGesture(false); err != nil {
		t.Fatalf("EnableGesture: %v", err)
	}
	if conn.registers[apdsRegENABLE]&0x40 != 0 || conn.registers[apdsRegGCONF4]&0x01 != 0 {
		t.Errorf("EnableGesture(false): GEN/GMODE not cleared")
	}

	if err := sensor.ConfigureGesture(1, 2, 20, 3, 5, 30, 10); err != nil {
		t.Fatalf("ConfigureGesture: %v", err)
	}
	if conn.registers[apdsRegGPENTH] != 30 || conn.registers[apdsRegGEXTH] != 10 {
		t.Errorf("ConfigureGesture: thresholds wrong")
	}
	if conn.registers[apdsRegGCONF2] != ((1 << 5) | (2 << 3) | 5) {
		t.Errorf("ConfigureGesture: GCONF2 = 0x%02X, want 0x35", conn.registers[apdsRegGCONF2])
	}
	if conn.registers[apdsRegGPULSE] != ((3 << 6) | 20) {
		t.Errorf("ConfigureGesture: GPULSE = 0x%02X, want 0xD4", conn.registers[apdsRegGPULSE])
	}

	conn.setRegister(apdsRegGSTATUS, 0x01)
	if ok, _ := sensor.GestureAvailable(); !ok {
		t.Errorf("GestureAvailable() = false, want true")
	}

	conn.setRegister(apdsRegGFLVL, 2)
	conn.setRegister(apdsRegGFIFO_U, 10, 20, 30, 40)
	fifo, err := sensor.ReadGestureFIFO(4)
	if err != nil {
		t.Fatalf("ReadGestureFIFO: %v", err)
	}
	if len(fifo) != 2 {
		t.Errorf("ReadGestureFIFO: len = %d, want 2", len(fifo))
	}
	if fifo[0] != [4]uint8{10, 20, 30, 40} {
		t.Errorf("ReadGestureFIFO: fifo[0] = %v, want [10 20 30 40]", fifo[0])
	}

	conn.setRegister(apdsRegGFLVL, 0)
	if fifo, _ := sensor.ReadGestureFIFO(4); len(fifo) != 0 {
		t.Errorf("ReadGestureFIFO: expected empty slice, got %v", fifo)
	}
	if v, _ := sensor.GestureFIFOLevel(); v != 0 {
		t.Errorf("GestureFIFOLevel() = %v, want 0", v)
	}

	if err := sensor.ClearGestureFIFO(); err != nil {
		t.Fatalf("ClearGestureFIFO: %v", err)
	}
	if conn.registers[apdsRegGCONF4]&0x04 == 0 {
		t.Errorf("ClearGestureFIFO: GFIFO_CLR not set")
	}

	if err := sensor.EnableGestureInterrupt(true); err != nil {
		t.Fatalf("EnableGestureInterrupt: %v", err)
	}
	if conn.registers[apdsRegGCONF4]&0x02 == 0 {
		t.Errorf("EnableGestureInterrupt: GIEN not set")
	}

	conn.setRegister(apdsRegSTATUS, 0x93) // CPSAT|PVALID|AVALID
	if v, _ := sensor.Status(); v != 0x93 {
		t.Errorf("Status() = 0x%02X, want 0x93", v)
	}
	if ok, _ := sensor.IsAlsValid(); !ok {
		t.Errorf("IsAlsValid() = false, want true")
	}
	if ok, _ := sensor.IsProximityValid(); !ok {
		t.Errorf("IsProximityValid() = false, want true")
	}
	if ok, _ := sensor.IsAlsSaturated(); !ok {
		t.Errorf("IsAlsSaturated() = false, want true")
	}
	if ok, _ := sensor.IsProximitySaturated(); ok {
		t.Errorf("IsProximitySaturated() = true, want false")
	}

	if v, _ := sensor.ChipID(); v != 0xAB {
		t.Errorf("ChipID() = 0x%02X, want 0xAB", v)
	}
}
