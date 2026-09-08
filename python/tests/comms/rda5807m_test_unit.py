import struct
import sys

from periph.connection.i2c_mock import I2CConnectionMock
from periph.chips.comms.rda5807m import RDA5807MFull

passed = 0
failed = 0


def check_true(label, condition):
    global passed, failed
    if condition:
        print('PASS', label)
        passed += 1
    else:
        print('FAIL', label)
        failed += 1


# Register bit constants, mirrored from python/periph/chips/comms/rda5807m.py
# (kept private there, so re-declared here to build expected values).
BAND_BASE_KHZ = (87000, 76000, 76000, 65000)
SPACE_KHZ = (100, 200, 50, 25)

BAND_WORLD = 2
SPACE_100K = 0
BAND_US_EUROPE = 0
SPACE_50K = 2

DHIZ = 0x8000
DMUTE = 0x4000
MONO = 0x2000
BASS = 0x1000
SEEKUP = 0x0200
SEEK = 0x0100
SKMODE = 0x0080
RDS_EN = 0x0008
NEW_METHOD = 0x0004
SOFT_RESET = 0x0002
ENABLE = 0x0001
TUNE = 0x0010
DE = 0x0800
SOFTMUTE_EN = 0x0200
AFCD = 0x0100
INT_MODE = 0x8000
BAND_65M_50M = 0x0200
RDSR = 0x8000
STC = 0x4000
SF = 0x2000
ST = 0x0400
FM_TRUE = 0x0100
FM_READY = 0x0080


def freq_to_chan(band, space, east50, freq_mhz):
    base = 50000 if (band == 3 and east50) else BAND_BASE_KHZ[band]
    freq_khz = round(freq_mhz * 1000)
    chan = round((freq_khz - base) / SPACE_KHZ[space])
    return max(0, min(1023, chan))


def chan_to_freq(band, space, east50, chan):
    base = 50000 if (band == 3 and east50) else BAND_BASE_KHZ[band]
    return (base + chan * SPACE_KHZ[space]) / 1000.0


def regs_bytes(regs):
    return struct.pack('>6H', *regs)


def status_bytes(*words):
    return struct.pack('>{}H'.format(len(words)), *words)


def new_sensor(frequency_mhz=100.0, volume=8):
    """Construct a fresh RDA5807MFull with a queued STC-set status so the
    blocking wait_stc() inside __init__ resolves on its first poll, and
    return (connection, sensor, regs, band, space, east50) where regs is the
    expected post-init shadow register array (TUNE already cleared, mirroring
    what the driver does once it observes STC)."""
    conn = I2CConnectionMock()
    conn.queue_read(status_bytes(STC))
    band, space, east50 = BAND_WORLD, SPACE_100K, False
    chan0 = freq_to_chan(band, space, east50, frequency_mhz)
    regs = [
        DHIZ | DMUTE | SKMODE | NEW_METHOD | ENABLE,
        (chan0 << 6) | TUNE | (band << 2) | space,
        SOFTMUTE_EN | DE,
        INT_MODE | (8 << 8) | (volume & 0x0F),
        0x0000,
        (16 << 10) | BAND_65M_50M | 0x0002,
    ]
    sensor = RDA5807MFull(conn, frequency_mhz=frequency_mhz, volume=volume)
    regs[1] &= ~TUNE
    return conn, sensor, regs, band, space, east50


# --- init ---
conn, sensor, regs, band, space, east50 = new_sensor()
expected_init_regs = list(regs)
expected_init_regs[1] |= TUNE  # write happened before the shadow TUNE bit was cleared
check_true('init_writes_regs', conn.writes[0] == regs_bytes(expected_init_regs))

# --- frequency() ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(250))
freq = sensor.frequency()
check_true('frequency', freq == chan_to_freq(band, space, east50, 250))

# --- set_frequency() ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(STC))
sensor.set_frequency(103.5)
chan1 = freq_to_chan(band, space, east50, 103.5)
regs[1] = (chan1 << 6) | TUNE | (band << 2) | space
check_true('set_frequency_writes', conn.writes[-1] == regs_bytes(regs))

# --- set_volume() ---
conn, sensor, regs, band, space, east50 = new_sensor()
sensor.set_volume(5)
regs[3] = (regs[3] & ~0x000F) | (5 & 0x0F)
check_true('set_volume', conn.writes[-1] == regs_bytes(regs))

# --- mute() ---
conn, sensor, regs, band, space, east50 = new_sensor()
sensor.mute(True)
regs[0] &= ~DMUTE
check_true('mute_true', conn.writes[-1] == regs_bytes(regs))
sensor.mute(False)
regs[0] |= DMUTE
check_true('mute_false', conn.writes[-1] == regs_bytes(regs))

# --- seek() found ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(STC | 300))
result = sensor.seek(up=True)
regs[0] |= SEEKUP
regs[0] |= SEEK
first_write = regs_bytes(regs)
regs[0] &= ~SEEK
second_write = regs_bytes(regs)
check_true('seek_up_writes', conn.writes[-2:] == [first_write, second_write])
check_true('seek_up_result', result == chan_to_freq(band, space, east50, 300))

# --- seek() fails (SF set) ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(STC | SF))
result = sensor.seek(up=False)
check_true('seek_fail_returns_none', result is None)

# --- configure() with retune (band/space change) ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(500))  # configure() reads current frequency() first
current_freq = chan_to_freq(band, space, east50, 500)
conn.queue_read(status_bytes(STC))  # for the resulting retune's wait_stc
sensor.configure(band=BAND_US_EUROPE, space=SPACE_50K, de_emphasis=False,
                  seek_threshold=10, seek_mode=False, clk_mode=3, afc_disable=True)
band, space = BAND_US_EUROPE, SPACE_50K
regs[2] &= ~DE
regs[2] |= AFCD
regs[3] = (regs[3] & ~0x0F00) | ((10 & 0x0F) << 8)
regs[0] &= ~SKMODE
regs[0] = (regs[0] & ~0x0070) | ((3 & 0x07) << 4)
chan2 = freq_to_chan(band, space, east50, current_freq)
regs[1] = (chan2 << 6) | TUNE | (band << 2) | space
check_true('configure_retunes', conn.writes[-1] == regs_bytes(regs))

# --- configure() without retune (band/space unchanged) ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(0))  # configure() still reads frequency() first
sensor.configure(seek_threshold=4)
regs[3] = (regs[3] & ~0x0F00) | ((4 & 0x0F) << 8)
check_true('configure_no_retune', conn.writes[-1] == regs_bytes(regs))

# --- set_bass_boost / set_mono / set_softmute / enable_rds (chained: no reads involved) ---
conn, sensor, regs, band, space, east50 = new_sensor()
sensor.set_bass_boost(True)
regs[0] |= BASS
check_true('set_bass_boost', conn.writes[-1] == regs_bytes(regs))

sensor.set_mono(True)
regs[0] |= MONO
check_true('set_mono', conn.writes[-1] == regs_bytes(regs))

sensor.set_softmute(False)
regs[2] &= ~SOFTMUTE_EN
check_true('set_softmute', conn.writes[-1] == regs_bytes(regs))

sensor.enable_rds(True)
regs[0] |= RDS_EN
check_true('enable_rds', conn.writes[-1] == regs_bytes(regs))

# --- rds_ready() ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(RDSR))
check_true('rds_ready_true', sensor.rds_ready())
conn.queue_read(status_bytes(0))
check_true('rds_ready_false', not sensor.rds_ready())

# --- read_rds_group() ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(RDSR, 0, 0x1122, 0x3344, 0x5566, 0x7788))
group = sensor.read_rds_group()
check_true('read_rds_group', group == (0x1122, 0x3344, 0x5566, 0x7788))
conn.queue_read(status_bytes(0, 0, 0, 0, 0, 0))
check_true('read_rds_group_none', sensor.read_rds_group() is None)

# --- is_stereo / is_station / is_ready / signal_strength ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(ST))
check_true('is_stereo_true', sensor.is_stereo())
conn.queue_read(status_bytes(0, FM_TRUE))
check_true('is_station_true', sensor.is_station())
conn.queue_read(status_bytes(0, FM_READY))
check_true('is_ready_true', sensor.is_ready())
conn.queue_read(status_bytes(0, (100 << 9) & 0xFFFF))
check_true('signal_strength', sensor.signal_strength() == 100)

# --- standby() ---
conn, sensor, regs, band, space, east50 = new_sensor()
sensor.standby(True)
regs[0] &= ~ENABLE
check_true('standby_down', conn.writes[-1] == regs_bytes(regs))

conn.queue_read(status_bytes(STC))  # standby(False)'s internal set_frequency's wait_stc
sensor.standby(False)
regs[0] |= ENABLE
enable_write = regs_bytes(regs)
chan3 = freq_to_chan(band, space, east50, 100.0)  # new_sensor()'s default frequency, unchanged so far
regs[1] = (chan3 << 6) | TUNE | (band << 2) | space
retune_write = regs_bytes(regs)
check_true('standby_up_writes', conn.writes[-2:] == [enable_write, retune_write])

# --- soft_reset() ---
conn, sensor, regs, band, space, east50 = new_sensor()
conn.queue_read(status_bytes(STC))  # soft_reset()'s internal set_frequency's wait_stc
sensor.soft_reset()
regs[0] |= SOFT_RESET
set_write = regs_bytes(regs)
regs[0] &= ~SOFT_RESET
clear_write = regs_bytes(regs)
chan4 = freq_to_chan(band, space, east50, 100.0)  # new_sensor()'s default frequency, unchanged so far
regs[1] = (chan4 << 6) | TUNE | (band << 2) | space
retune_write = regs_bytes(regs)
check_true('soft_reset_writes', conn.writes[-3:] == [set_write, clear_write, retune_write])

print('===DONE: {} passed, {} failed==='.format(passed, failed))
sys.exit(0 if failed == 0 else 1)
