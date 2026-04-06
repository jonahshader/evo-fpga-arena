# Communications Protocol Specification

## Overview

This document specifies the message protocol between a host (PS, PC, etc.) and the
neuroevolution FPGA core. The protocol is transport-agnostic: the same message formats
are used whether the underlying transport is UART, AXI-DMA, or XDMA (PCIe).

Messages are framed as AXI-Stream transactions internally. Transport adapters bridge
between the physical interface and the protocol layer.

## Wire Format

Every message is a contiguous byte sequence with a fixed 4-byte header followed by a
variable-length payload.

```
Offset  Size  Field
------  ----  -----
0       1     MSG_TYPE    Message type ID (see tables below)
1       1     FLAGS       Reserved, must be 0x00
2       2     LENGTH      Payload length in bytes, little-endian (excludes header)
4       N     PAYLOAD     Message-specific data (N = LENGTH)
```

Total message size = 4 + LENGTH bytes.

### AXI-Stream Mapping

On AXI-Stream transports with data width W bytes:

- Header and payload are packed contiguously across beats.
- Beat 0 contains the 4-byte header followed by up to (W - 4) payload bytes.
- `TLAST` is asserted on the final beat.
- `TKEEP` on the final beat marks which bytes are valid (for non-W-aligned payloads).
- `TID`, `TDEST`, `TUSER` are not used by the protocol layer.

For W=1 (byte-wide, e.g. UART bridge), this degenerates to one byte per beat.

### Byte Order

All multi-byte integers are **little-endian** (LSB first). This matches the PCIe/x86
host and simplifies DMA transfers. Note: the original VHDL protocol used big-endian;
the SpinalHDL implementation switches to little-endian.

### Fixed-Point Encoding

F4 values (SFix 11 downto -4) are transmitted as their 16-bit raw signed integer
representation (two's complement, little-endian). The host reconstructs the value as
`raw_value * 2^(-4)`.

## Host-to-FPGA Messages (Commands)

These flow from the host to the FPGA protocol RX path.

### 0x01 TILEMAP -- Configure game map

Payload layout (518 bytes):

```
Offset  Size  Field
------  ----  -----
0       256   tiles[256]       16x16 tile grid, row-major, 1 byte per tile (low 3 bits = TileType)
                               Index = y * 16 + x
256     256   spawns[128]      128 spawn points, 2 bytes each:
                                 [2*i]   = spawn[i].x  (uint8, low 4 bits used)
                                 [2*i+1] = spawn[i].y  (uint8, low 4 bits used)
512     1     num_spawn        Number of valid spawn points (uint8)
513     1     num_spawn_bits   Bits needed for RNG spawn sampling (uint8, low 4 bits)
514     2     width            Map width in tiles (uint16 LE)
516     2     height           Map height in tiles (uint16 LE)
```

### 0x02 GA_CONFIG -- Configure genetic algorithm

Payload layout (148 bytes):

```
Offset  Size  Field
------  ----  -----
0       128   mutation_rates[128]      Per-individual mutation rate (uint8 each)
128     2     max_gen                  Max generations (uint16 LE)
130     1     run_until_stop_cmd       Boolean (0 or 1)
131     1     tournament_size          (uint8)
132     1     population_size_exp      log2(population_size) (uint8)
133     1     model_history_size       (uint8)
134     1     model_history_interval   (uint8)
135     4     seed                     RNG seed (uint32 LE)
139     1     reference_count          (uint8)
140     1     eval_interval            (uint8)
141     1     seed_count               (uint8)
142     2     frame_limit              (uint16 LE)
144     1     recycle_seeds            Boolean (0 or 1)
```

Note: total payload = 145 bytes.

### 0x03 TRAINING_GO -- Start training

No payload (LENGTH = 0).

### 0x04 TRAINING_PAUSE -- Pause training

No payload.

### 0x05 TRAINING_RESUME -- Resume training

No payload.

### 0x06 INFERENCE_GO -- Start playing

Payload (1 byte):

```
Offset  Size  Field
------  ----  -----
0       1     play_against_nn    0 = NN vs NN, 1 = human vs NN
```

### 0x07 INFERENCE_STOP -- Stop playing

No payload.

### 0x08 PLAYER_INPUT -- Human player controls

Payload (1 byte):

```
Offset  Size  Field
------  ----  -----
0       1     input_bits         bit 0 = left, bit 1 = right, bit 2 = jump
```

### 0x09 BRAM_DUMP_REQ -- Request neural network weight dump

Payload (1 byte):

```
Offset  Size  Field
------  ----  -----
0       1     index              Which individual's weights to dump (uint8)
```

### 0x0A NN_UPLOAD -- Upload neural network weights (future)

Payload (variable):

```
Offset  Size   Field
------  -----  -----
0       1      index             Target individual slot (uint8)
1       4608   params[4608]      4-bit parameters, one per byte (low nibble)
```

Reserved for future use. Enables host-side weight injection for transfer learning,
checkpoint restore, or PS-in-the-loop training.

### 0x0B TEST -- Echo test

No payload. FPGA responds with TEST_RESP.

## FPGA-to-Host Messages (Telemetry)

These flow from the FPGA protocol TX path to the host.

### 0x01 STATE_CHANGE -- Neuroevolution state transition

Payload (1 byte):

```
Offset  Size  Field
------  ----  -----
0       1     state              0 = IDLE, 1 = TRAINING, 2 = PLAYING
```

Sent whenever the neuroevolution FSM changes state. Replaces the three separate
NE_IS_IDLE / NE_IS_TRAINING / NE_IS_PLAYING messages from the VHDL design.

### 0x02 GA_STATUS -- Training progress update

Payload (4 bytes):

```
Offset  Size  Field
------  ----  -----
0       2     current_gen         Current generation (uint16 LE)
2       2     reference_fitness   Best fitness (sint16 LE, two's complement)
```

Sent periodically during training (once per generation or per eval_interval).

### 0x03 GAME_STATE -- Live game state

Payload (20 bytes):

```
Offset  Size  Field
------  ----  -----
0       2     p1.pos.x           F4 raw (sint16 LE)
2       2     p1.pos.y           F4 raw (sint16 LE)
4       2     p1.score           (sint16 LE)
6       1     p1.dead_timeout    (uint8)
7       2     p2.pos.x           F4 raw (sint16 LE)
9       2     p2.pos.y           F4 raw (sint16 LE)
11      2     p2.score           (sint16 LE)
13      1     p2.dead_timeout    (uint8)
14      1     coin_pos.x         (uint8)
15      1     coin_pos.y         (uint8)
16      2     age                (uint16 LE)
18      2     reserved           Padding to align to 20 bytes
```

Sent each frame during PLAYING mode.

### 0x04 BRAM_DUMP_RESP -- Neural network weight dump

Payload (4608 bytes):

```
Offset  Size   Field
------  -----  -----
0       4608   params[4608]     4-bit parameters, one per byte (low nibble)
```

Sent in response to BRAM_DUMP_REQ. Contains all weights and biases for one
individual's neural network.

### 0x05 TEST_RESP -- Echo test response

No payload.

## Message ID Summary

### Host-to-FPGA

| ID   | Name            | Payload Size |
|------|-----------------|--------------|
| 0x01 | TILEMAP         | 518          |
| 0x02 | GA_CONFIG       | 145          |
| 0x03 | TRAINING_GO     | 0            |
| 0x04 | TRAINING_PAUSE  | 0            |
| 0x05 | TRAINING_RESUME | 0            |
| 0x06 | INFERENCE_GO    | 1            |
| 0x07 | INFERENCE_STOP  | 0            |
| 0x08 | PLAYER_INPUT    | 1            |
| 0x09 | BRAM_DUMP_REQ   | 1            |
| 0x0A | NN_UPLOAD       | 4609         |
| 0x0B | TEST            | 0            |

### FPGA-to-Host

| ID   | Name            | Payload Size |
|------|-----------------|--------------|
| 0x01 | STATE_CHANGE    | 1            |
| 0x02 | GA_STATUS       | 4            |
| 0x03 | GAME_STATE      | 20           |
| 0x04 | BRAM_DUMP_RESP  | 4608         |
| 0x05 | TEST_RESP       | 0            |

## Changes from VHDL Protocol

| Aspect | VHDL (legacy) | SpinalHDL (new) |
|--------|---------------|-----------------|
| Byte order | Big-endian | Little-endian |
| Header | 1-byte command only | 4-byte (type + flags + length) |
| Framing | Implicit length per msg type | Explicit length field |
| NE state msgs | 3 separate IDs (0x03-0x05) | 1 STATE_CHANGE with payload byte |
| PLAY_AGAINST_NN | 2 separate IDs (0x09-0x0A) | Folded into INFERENCE_GO payload |
| NN upload | Not supported | NN_UPLOAD (0x0A) reserved |
| GAME_STATE size | 19 bytes | 20 bytes (2 padding for alignment) |
| Tilemap spawns | x[128] then y[128] (split) | Interleaved (x,y) pairs |
| Tilemap width/height | 1 byte each (uint8) | 2 bytes each (uint16 LE) for consistency |
