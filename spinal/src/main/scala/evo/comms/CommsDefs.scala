package evo.comms

import spinal.core._
import spinal.lib._

/** Message IDs and constants for the host ↔ FPGA protocol. */
object CommsDefs {
  val HEADER_SIZE = 4 // bytes: [msgType, flags, lengthLo, lengthHi]

  /** Host → FPGA command message IDs. */
  object CmdId {
    val TILEMAP: Int         = 0x01
    val GA_CONFIG: Int       = 0x02
    val TRAINING_GO: Int     = 0x03
    val TRAINING_PAUSE: Int  = 0x04
    val TRAINING_RESUME: Int = 0x05
    val INFERENCE_GO: Int    = 0x06
    val INFERENCE_STOP: Int  = 0x07
    val PLAYER_INPUT: Int    = 0x08
    val BRAM_DUMP_REQ: Int   = 0x09
    val NN_UPLOAD: Int       = 0x0A
    val TEST: Int            = 0x0B
  }

  /** FPGA → Host telemetry message IDs. */
  object TelId {
    val STATE_CHANGE: Int   = 0x01
    val GA_STATUS: Int      = 0x02
    val GAME_STATE: Int     = 0x03
    val BRAM_DUMP_RESP: Int = 0x04
    val TEST_RESP: Int      = 0x05
  }

  /** Payload sizes in bytes (excludes 4-byte header). */
  object PayloadSize {
    val TILEMAP: Int      = 518
    val GA_CONFIG: Int    = 145
    val STATE_CHANGE: Int = 1
    val GA_STATUS: Int    = 4
    val GAME_STATE: Int   = 20
    val BRAM_DUMP: Int    = 4608
  }
}

/** Groups pulse/latch outputs from ProtocolRx for clean wiring. */
case class CommandBundle() extends Bundle {
  val trainingGo      = Bool()
  val trainingPause   = Bool()
  val trainingResume  = Bool()
  val inferenceGo     = Bool()
  val inferenceStop   = Bool()
  val playAgainstNn   = Bool() // latched, not pulse
  val testGo          = Bool()
  val dbBramDump      = Bool()
  val dbBramDumpIndex = UInt(8 bits)
}
