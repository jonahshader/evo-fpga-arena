package evo.comms

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axis._
import evo.types._

/**
 * Protocol RX: Deserializes byte-wide AXI-Stream into Neuroevolution input signals.
 *
 * Parses the 4-byte message header (type, flags, length LE), then routes payload
 * bytes to the appropriate output registers based on message type.
 *
 * See doc/COMMS_PROTOCOL.md for message formats.
 */
class ProtocolRx extends Component {
  val axisConfig = Axi4StreamConfig(dataWidth = 1, useLast = true)

  val io = new Bundle {
    val stream          = slave(Axi4Stream(axisConfig))
    val config          = out(GaConfigBundle())
    val tilemap         = out(Tilemap())
    val cmd             = out(CommandBundle())
    val humanInput      = out(PlayerInput())
    val humanInputValid = out Bool()
  }

  import CommsDefs._
  import CommsDefs.CmdId._
  import MapConfig._

  // --- State ---
  val inHeader = RegInit(True)  // True = parsing header, False = parsing payload
  val headerCounter  = Reg(UInt(2 bits)) init 0
  val payloadCounter = Reg(UInt(16 bits)) init 0

  val msgType = Reg(UInt(8 bits)) init 0
  val msgLength = Reg(UInt(16 bits)) init 0

  // --- Output registers ---
  val configReg  = Reg(GaConfigBundle())
  val tilemapReg = Reg(Tilemap())
  val humanInputReg = Reg(PlayerInput())
  val playAgainstNnReg = RegInit(False)

  io.config     := configReg
  io.tilemap    := tilemapReg
  io.humanInput := humanInputReg
  io.cmd.playAgainstNn := playAgainstNnReg

  // Pulse defaults (active for 1 cycle only when triggered)
  io.cmd.trainingGo      := False
  io.cmd.trainingPause   := False
  io.cmd.trainingResume  := False
  io.cmd.inferenceGo     := False
  io.cmd.inferenceStop   := False
  io.cmd.testGo          := False
  io.cmd.dbBramDump      := False
  io.cmd.dbBramDumpIndex := 0
  io.humanInputValid     := False

  // Always accept bytes
  io.stream.ready := True

  val rxByte = io.stream.payload.data

  when(io.stream.fire) {
    when(inHeader) {
      switch(headerCounter) {
        is(0) { msgType := rxByte.asUInt.resized }
        is(1) { /* flags — ignored */ }
        is(2) { msgLength(7 downto 0) := rxByte.asUInt.resized }
        is(3) {
          msgLength(15 downto 8) := rxByte.asUInt.resized

          // Reconstruct full length (byte 2 already stored, byte 3 arriving now)
          val fullLength = UInt(16 bits)
          fullLength(7 downto 0) := msgLength(7 downto 0)
          fullLength(15 downto 8) := rxByte.asUInt.resized

          when(fullLength === 0) {
            // Zero-payload command — fire pulse and stay in header mode
            headerCounter := 0
            switch(msgType) {
              is(TRAINING_GO)     { io.cmd.trainingGo := True }
              is(TRAINING_PAUSE)  { io.cmd.trainingPause := True }
              is(TRAINING_RESUME) { io.cmd.trainingResume := True }
              is(INFERENCE_STOP)  { io.cmd.inferenceStop := True }
              is(TEST)            { io.cmd.testGo := True }
            }
          } otherwise {
            // Has payload — switch to payload mode
            inHeader := False
            payloadCounter := 0
          }
        }
      }
      when(headerCounter =/= 3) {
        headerCounter := headerCounter + 1
      }
    } otherwise {
      // --- Payload processing ---
      val pc = payloadCounter
      val b  = rxByte

      switch(msgType) {
        // --- TILEMAP (518 bytes) ---
        is(TILEMAP) {
          when(pc < 256) {
            // tiles: row-major, index = y*16 + x
            val x = pc(MAP_TILES_BITS - 1 downto 0)
            val y = pc(2 * MAP_TILES_BITS - 1 downto MAP_TILES_BITS)
            tilemapReg.tiles(x.resized)(y.resized).assignFromBits(b(2 downto 0))
          } elsewhen (pc < 512) {
            // spawns: interleaved x,y pairs
            val spawnOffset = pc - 256
            val spawnIndex = (spawnOffset >> 1).resized
            when(!spawnOffset(0)) {
              tilemapReg.spawn(spawnIndex).x := b(MAP_TILES_BITS - 1 downto 0).asUInt
            } otherwise {
              tilemapReg.spawn(spawnIndex).y := b(MAP_TILES_BITS - 1 downto 0).asUInt
            }
          } elsewhen (pc === 512) {
            tilemapReg.numSpawn := b.asUInt.resized
          } elsewhen (pc === 513) {
            tilemapReg.numSpawnBits := b(3 downto 0).asUInt
          } elsewhen (pc === 514) {
            tilemapReg.width := b.asUInt.resized
          } elsewhen (pc === 515) {
            // width high byte — ignored (field is only 5 bits)
          } elsewhen (pc === 516) {
            tilemapReg.height := b.asUInt.resized
          } elsewhen (pc === 517) {
            // height high byte — ignored
          }
        }

        // --- GA_CONFIG (145 bytes) ---
        is(GA_CONFIG) {
          when(pc < 128) {
            configReg.mutationRates(pc.resized) := b.asUInt
          } elsewhen (pc === 128) {
            configReg.maxGen(7 downto 0) := b.asUInt
          } elsewhen (pc === 129) {
            configReg.maxGen(15 downto 8) := b.asUInt
          } elsewhen (pc === 130) {
            configReg.runUntilStopCmd := b(0)
          } elsewhen (pc === 131) {
            configReg.tournamentSize := b.asUInt
          } elsewhen (pc === 132) {
            configReg.populationSizeExp := b.asUInt
          } elsewhen (pc === 133) {
            configReg.modelHistorySize := b.asUInt
          } elsewhen (pc === 134) {
            configReg.modelHistoryInterval := b.asUInt
          } elsewhen (pc === 135) {
            configReg.seed(7 downto 0) := b
          } elsewhen (pc === 136) {
            configReg.seed(15 downto 8) := b
          } elsewhen (pc === 137) {
            configReg.seed(23 downto 16) := b
          } elsewhen (pc === 138) {
            configReg.seed(31 downto 24) := b
          } elsewhen (pc === 139) {
            configReg.referenceCount := b.asUInt
          } elsewhen (pc === 140) {
            configReg.evalInterval := b.asUInt
          } elsewhen (pc === 141) {
            configReg.seedCount := b.asUInt
          } elsewhen (pc === 142) {
            configReg.frameLimit(7 downto 0) := b.asUInt
          } elsewhen (pc === 143) {
            configReg.frameLimit(15 downto 8) := b.asUInt
          } elsewhen (pc === 144) {
            configReg.recycleSeeds := b(0)
          }
        }

        // --- INFERENCE_GO (1 byte: playAgainstNn) ---
        is(INFERENCE_GO) {
          when(pc === 0) {
            playAgainstNnReg := b(0)
            io.cmd.inferenceGo := True
          }
        }

        // --- PLAYER_INPUT (1 byte: left|right|jump) ---
        is(PLAYER_INPUT) {
          when(pc === 0) {
            humanInputReg.left  := b(0)
            humanInputReg.right := b(1)
            humanInputReg.jump  := b(2)
            io.humanInputValid  := True
          }
        }

        // --- BRAM_DUMP_REQ (1 byte: index) ---
        is(BRAM_DUMP_REQ) {
          when(pc === 0) {
            io.cmd.dbBramDumpIndex := b.asUInt
            io.cmd.dbBramDump := True
          }
        }

        // Unknown message type — consume silently
        default { }
      }

      // Advance or return to header
      when(payloadCounter === msgLength - 1) {
        inHeader := True
        headerCounter := 0
      } otherwise {
        payloadCounter := payloadCounter + 1
      }
    }
  }
}
