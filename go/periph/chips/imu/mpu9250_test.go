package imu

import (
	"testing"

	"github.com/tuhde/Periph/go/periph/connection"
)

// s16le encodes a signed 16-bit value as its two little-endian bytes -
// needed for the AK8963's magnetometer registers, unlike the MPU's own
// big-endian ones (see s16 in mpu6050_test.go).
func s16le(value int16) (byte, byte) {
	hi, lo := s16(value)
	return lo, hi
}

// newInitializedSensor9250 builds an MPU9250Full whose magFactory always
// returns magConn regardless of the requested address - the AK8963 sits
// behind I²C bypass as its own device at 0x0C, so it needs its own
// connection, separate from the MPU-9250's own (see NewMPU9250Full's doc
// comment).
func newInitializedSensor9250(t *testing.T) (*mockConnection, *mockConnection, *MPU9250Full) {
	t.Helper()
	conn := newMockConnection()
	conn.setRegister(reg9250WhoAmI, whoAmI9250Value)
	magConn := newMockConnection()
	sensor, err := NewMPU9250Full(conn, func(addr uint8) (connection.Connection, error) {
		return magConn, nil
	})
	if err != nil {
		t.Fatalf("NewMPU9250Full: %v", err)
	}
	return conn, magConn, sensor
}

func TestMPU9250Init(t *testing.T) {
	conn, _, _ := newInitializedSensor9250(t)

	// MPU9250 additionally writes ACCEL_CONFIG2, unlike MPU6050.
	expected := [][]byte{
		{reg9250PwrMgmt1, 0x80},
		{reg9250PwrMgmt1, 0x01},
		{reg9250WhoAmI},
		{reg9250GyroConfig, 0x00},
		{reg9250AccelConfig, 0x00},
		{reg9250AccelConfig2, 0x03},
		{reg9250Config, 0x03},
		{reg9250SmplrtDiv, 0x04},
	}
	if len(conn.writes) != len(expected) {
		t.Fatalf("init write count = %d, want %d", len(conn.writes), len(expected))
	}
	for i, w := range expected {
		if string(conn.writes[i]) != string(w) {
			t.Errorf("init write[%d] = % X, want % X", i, conn.writes[i], w)
		}
	}
}

func TestMPU9250WhoAmIMismatch(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(reg9250WhoAmI, 0x00)
	if _, err := NewMPU9250Minimal(conn); err == nil {
		t.Error("NewMPU9250Minimal with bad WHO_AM_I: expected error, got nil")
	}
}

func TestMPU9250FullRequiresMagFactory(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(reg9250WhoAmI, whoAmI9250Value)
	if _, err := NewMPU9250Full(conn, nil); err == nil {
		t.Error("NewMPU9250Full with nil magFactory: expected error, got nil")
	}
}

func TestMPU9250Accel(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	// raw (16384, -8192, 4096) at default ACCEL_FS_SEL=0 (16384 LSB/g).
	h, l := s16(16384)
	conn.setRegister(reg9250AccelXoutH, h, l)
	h, l = s16(-8192)
	conn.setRegister(reg9250AccelXoutH+2, h, l)
	h, l = s16(4096)
	conn.setRegister(reg9250AccelXoutH+4, h, l)

	ax, ay, az, err := sensor.Accel()
	if err != nil {
		t.Fatalf("Accel: %v", err)
	}
	if abs32(ax-9.80665) > 1e-3 || abs32(ay-(-4.903325)) > 1e-3 || abs32(az-2.4516625) > 1e-3 {
		t.Errorf("Accel() = (%v, %v, %v), want (9.80665, -4.903325, 2.4516625)", ax, ay, az)
	}
}

func TestMPU9250Gyro(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	// raw (131, -131, 262) at default GYRO_FS_SEL=0 (131.0 LSB/(deg/s)) -> (1, -1, 2) dps.
	h, l := s16(131)
	conn.setRegister(reg9250GyroXoutH, h, l)
	h, l = s16(-131)
	conn.setRegister(reg9250GyroXoutH+2, h, l)
	h, l = s16(262)
	conn.setRegister(reg9250GyroXoutH+4, h, l)

	gx, gy, gz, err := sensor.Gyro()
	if err != nil {
		t.Fatalf("Gyro: %v", err)
	}
	deg2rad := func(d float32) float32 { return d * 3.141592653589793 / 180.0 }
	if abs32(gx-deg2rad(1)) > 1e-4 || abs32(gy-deg2rad(-1)) > 1e-4 || abs32(gz-deg2rad(2)) > 1e-4 {
		t.Errorf("Gyro() = (%v, %v, %v), want ~(%v, %v, %v)", gx, gy, gz, deg2rad(1), deg2rad(-1), deg2rad(2))
	}
}

func TestMPU9250ConfigureGyroChangesSensitivity(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	if err := sensor.ConfigureGyro(2); err != nil {
		t.Fatalf("ConfigureGyro: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250GyroConfig, 2 << 3}) {
		t.Errorf("ConfigureGyro write = % X, want % X", lastWrite(conn.writes), []byte{reg9250GyroConfig, 2 << 3})
	}
	// Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
	h, l := s16(328)
	conn.setRegister(reg9250GyroXoutH, h, l)
	conn.setRegister(reg9250GyroXoutH+2, 0, 0)
	conn.setRegister(reg9250GyroXoutH+4, 0, 0)
	gx, _, _, err := sensor.Gyro()
	if err != nil {
		t.Fatalf("Gyro: %v", err)
	}
	want := float32(10) * 3.141592653589793 / 180.0
	if abs32(gx-want) > 1e-3 {
		t.Errorf("Gyro() x = %v, want %v", gx, want)
	}
}

func TestMPU9250ConfigureAccelChangesSensitivity(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	if err := sensor.ConfigureAccel(1); err != nil {
		t.Fatalf("ConfigureAccel: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250AccelConfig, 1 << 3}) {
		t.Errorf("ConfigureAccel write = % X, want % X", lastWrite(conn.writes), []byte{reg9250AccelConfig, 1 << 3})
	}
	// Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
	h, l := s16(8192)
	conn.setRegister(reg9250AccelXoutH, h, l)
	conn.setRegister(reg9250AccelXoutH+2, 0, 0)
	conn.setRegister(reg9250AccelXoutH+4, 0, 0)
	ax, _, _, err := sensor.Accel()
	if err != nil {
		t.Fatalf("Accel: %v", err)
	}
	if abs32(ax-9.80665) > 1e-3 {
		t.Errorf("Accel() x = %v, want 9.80665", ax)
	}
}

func TestMPU9250ConfigureDLPFAndSampleRate(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	if err := sensor.ConfigureDLPF(5, 2); err != nil {
		t.Fatalf("ConfigureDLPF: %v", err)
	}
	writes := conn.writes
	n := len(writes)
	if string(writes[n-2]) != string([]byte{reg9250Config, 5}) {
		t.Errorf("ConfigureDLPF gyro write = % X, want % X", writes[n-2], []byte{reg9250Config, 5})
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250AccelConfig2, 2}) {
		t.Errorf("ConfigureDLPF accel write = % X, want % X", lastWrite(conn.writes), []byte{reg9250AccelConfig2, 2})
	}
	if err := sensor.ConfigureSampleRate(9); err != nil {
		t.Fatalf("ConfigureSampleRate: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250SmplrtDiv, 9}) {
		t.Errorf("ConfigureSampleRate write = % X, want % X", lastWrite(conn.writes), []byte{reg9250SmplrtDiv, 9})
	}
}

func TestMPU9250Temperature(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	// raw=340 -> 340/333.87 + 21.0.
	h, l := s16(340)
	conn.setRegister(reg9250TempOutH, h, l)
	temp, err := sensor.Temperature()
	if err != nil {
		t.Fatalf("Temperature: %v", err)
	}
	want := float32(340)/333.87 + 21.0
	if abs32(temp-want) > 1e-3 {
		t.Errorf("Temperature() = %v, want %v", temp, want)
	}
}

func TestMPU9250RawReads(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	h, l := s16(100)
	conn.setRegister(reg9250AccelXoutH, h, l)
	h, l = s16(-200)
	conn.setRegister(reg9250AccelXoutH+2, h, l)
	h, l = s16(300)
	conn.setRegister(reg9250AccelXoutH+4, h, l)
	ax, ay, az, err := sensor.AccelRaw()
	if err != nil || ax != 100 || ay != -200 || az != 300 {
		t.Errorf("AccelRaw() = (%d, %d, %d, %v), want (100, -200, 300, nil)", ax, ay, az, err)
	}

	h, l = s16(-50)
	conn.setRegister(reg9250GyroXoutH, h, l)
	h, l = s16(60)
	conn.setRegister(reg9250GyroXoutH+2, h, l)
	h, l = s16(-70)
	conn.setRegister(reg9250GyroXoutH+4, h, l)
	gx, gy, gz, err := sensor.GyroRaw()
	if err != nil || gx != -50 || gy != 60 || gz != -70 {
		t.Errorf("GyroRaw() = (%d, %d, %d, %v), want (-50, 60, -70, nil)", gx, gy, gz, err)
	}
}

func TestMPU9250DataReady(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	conn.setRegister(reg9250IntStatus, 0x01)
	if ready, err := sensor.DataReady(); err != nil || !ready {
		t.Errorf("DataReady() = %v, %v, want true, nil", ready, err)
	}
	conn.setRegister(reg9250IntStatus, 0x00)
	if ready, err := sensor.DataReady(); err != nil || ready {
		t.Errorf("DataReady() = %v, %v, want false, nil", ready, err)
	}
}

func TestMPU9250SetSleep(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)
	// PWR_MGMT_1 is 0x01 in the register map after init.
	if err := sensor.SetSleep(true); err != nil {
		t.Fatalf("SetSleep(true): %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250PwrMgmt1, 0x41}) {
		t.Errorf("SetSleep(true) write = % X, want % X", lastWrite(conn.writes), []byte{reg9250PwrMgmt1, 0x41})
	}
	if err := sensor.SetSleep(false); err != nil {
		t.Fatalf("SetSleep(false): %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250PwrMgmt1, 0x01}) {
		t.Errorf("SetSleep(false) write = % X, want % X", lastWrite(conn.writes), []byte{reg9250PwrMgmt1, 0x01})
	}
}

func TestMPU9250FIFO(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)

	conn.setRegister(reg9250FifoCountH, 0x03, 0x45)
	count, err := sensor.FIFOcount()
	if err != nil {
		t.Fatalf("FIFOcount: %v", err)
	}
	if count != (uint16(0x03)&0x1F)<<8|0x45 {
		t.Errorf("FIFOcount() = %d, want %d", count, (uint16(0x03)&0x1F)<<8|0x45)
	}

	conn.setRegister(reg9250FifoCountH, 0x00, 0x02)
	conn.setRegister(reg9250FifoR_W, 0xAA, 0xBB)
	buf := make([]byte, 8)
	n, err := sensor.ReadFIFO(buf)
	if err != nil || n != 2 || buf[0] != 0xAA || buf[1] != 0xBB {
		t.Errorf("ReadFIFO() = %d, %v, buf=% X, want 2, nil, [AA BB ...]", n, err, buf)
	}

	conn.setRegister(reg9250FifoCountH, 0x00, 0x00)
	n, err = sensor.ReadFIFO(buf)
	if err != nil || n != 0 {
		t.Errorf("ReadFIFO() (empty) = %d, %v, want 0, nil", n, err)
	}
}

func TestMPU9250EnableAndResetFIFO(t *testing.T) {
	conn, _, sensor := newInitializedSensor9250(t)

	if err := sensor.EnableFIFO(true, true, false); err != nil {
		t.Fatalf("EnableFIFO: %v", err)
	}
	n := len(conn.writes)
	// FIFO_EN write, then a USER_CTRL read (whose WriteRead phase also
	// appends a []byte{reg} entry), then the USER_CTRL write.
	if string(conn.writes[n-3]) != string([]byte{reg9250FifoEn, (1 << 3) | (1 << 4)}) ||
		string(conn.writes[n-2]) != string([]byte{reg9250UserCtrl}) ||
		string(conn.writes[n-1]) != string([]byte{reg9250UserCtrl, 0x40}) {
		t.Errorf("EnableFIFO writes = %v", conn.writes[n-3:])
	}

	// USER_CTRL is 0x40 in the register map after EnableFIFO().
	if err := sensor.ResetFIFO(); err != nil {
		t.Fatalf("ResetFIFO: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250UserCtrl, 0x44}) {
		t.Errorf("ResetFIFO write = % X, want % X", lastWrite(conn.writes), []byte{reg9250UserCtrl, 0x44})
	}
}

func TestMPU9250EnableMag(t *testing.T) {
	conn, magConn, sensor := newInitializedSensor9250(t)

	// EnableMag(): INT_PIN_CFG write (primary connection), AK8963 CNTL1
	// power-down, fuse ROM access, ASAX/ASAY/ASAZ burst read, power-down,
	// mode write - all on magConn.
	magConn.setRegister(ak8963ASAX, 200, 100, 50)
	if err := sensor.EnableMag(16, 6); err != nil {
		t.Fatalf("EnableMag: %v", err)
	}
	if string(lastWrite(conn.writes)) != string([]byte{reg9250IntPinCfg, 0x22}) {
		t.Errorf("EnableMag INT_PIN_CFG write = % X, want % X", lastWrite(conn.writes), []byte{reg9250IntPinCfg, 0x22})
	}
	magWrites := magConn.writes
	if len(magWrites) != 5 {
		t.Fatalf("EnableMag mag write count = %d, want 5", len(magWrites))
	}
	if string(magWrites[0]) != string([]byte{ak8963CNTL1, ak8963ModePowerDown}) {
		t.Errorf("EnableMag write[0] = % X, want power-down", magWrites[0])
	}
	if string(magWrites[1]) != string([]byte{ak8963CNTL1, ak8963ModeFuseROM}) {
		t.Errorf("EnableMag write[1] = % X, want fuse ROM", magWrites[1])
	}
	if string(magWrites[2]) != string([]byte{ak8963ASAX}) {
		t.Errorf("EnableMag write[2] = % X, want ASAX read", magWrites[2])
	}
	if string(magWrites[3]) != string([]byte{ak8963CNTL1, ak8963ModePowerDown}) {
		t.Errorf("EnableMag write[3] = % X, want power-down", magWrites[3])
	}
	if string(magWrites[4]) != string([]byte{ak8963CNTL1, ak8963Cont100Hz16b}) {
		t.Errorf("EnableMag write[4] = % X, want mode write", magWrites[4])
	}

	// Mag(): raw (1000, -500, 250) with scale factors derived from
	// ASAX/ASAY/ASAZ above: (200-128)/256+1=1.28125, (100-128)/256+1=0.890625,
	// (50-128)/256+1=0.6953125.
	lo, hi := s16le(1000)
	magConn.setRegister(ak8963HXL, lo, hi)
	lo, hi = s16le(-500)
	magConn.setRegister(ak8963HXL+2, lo, hi)
	lo, hi = s16le(250)
	magConn.setRegister(ak8963HXL+4, lo, hi)
	magConn.setRegister(ak8963HXL+6, 0x00)
	mx, my, mz, err := sensor.Mag()
	if err != nil {
		t.Fatalf("Mag: %v", err)
	}
	wantX := float32(1000) * 0.15 * 1.28125
	wantY := float32(-500) * 0.15 * 0.890625
	wantZ := float32(250) * 0.15 * 0.6953125
	if abs32(mx-wantX) > 1e-3 || abs32(my-wantY) > 1e-3 || abs32(mz-wantZ) > 1e-3 {
		t.Errorf("Mag() = (%v, %v, %v), want (%v, %v, %v)", mx, my, mz, wantX, wantY, wantZ)
	}

	// MagRaw()
	lo, hi = s16le(111)
	magConn.setRegister(ak8963HXL, lo, hi)
	lo, hi = s16le(-222)
	magConn.setRegister(ak8963HXL+2, lo, hi)
	lo, hi = s16le(333)
	magConn.setRegister(ak8963HXL+4, lo, hi)
	rmx, rmy, rmz, err := sensor.MagRaw()
	if err != nil || rmx != 111 || rmy != -222 || rmz != 333 {
		t.Errorf("MagRaw() = (%d, %d, %d, %v), want (111, -222, 333, nil)", rmx, rmy, rmz, err)
	}
}

func TestMPU9250MagNotEnabled(t *testing.T) {
	_, _, sensor := newInitializedSensor9250(t)
	if _, _, _, err := sensor.Mag(); err == nil {
		t.Error("Mag() before EnableMag: expected error, got nil")
	}
	if _, _, _, err := sensor.MagRaw(); err == nil {
		t.Error("MagRaw() before EnableMag: expected error, got nil")
	}
}
