package evo.comms

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._

class ProtocolRxTest extends AnyFunSuite {

  /** Build a message: 4-byte header + payload. */
  def makeMessage(msgType: Int, payload: Seq[Int]): List[Byte] = {
    val length = payload.length
    val header = List(
      msgType & 0xFF,
      0x00, // flags
      length & 0xFF,
      (length >> 8) & 0xFF
    )
    (header ++ payload).map(_.toByte)
  }

  /** Send bytes one at a time via the AXI-Stream input. */
  def sendBytes(dut: ProtocolRx, bytes: List[Byte]): Unit = {
    for (b <- bytes) {
      dut.io.stream.valid #= true
      dut.io.stream.payload.data #= (b.toInt & 0xFF)
      dut.io.stream.payload.last #= false
      dut.clockDomain.waitRisingEdge()
    }
    dut.io.stream.valid #= false
  }

  def initInputs(dut: ProtocolRx): Unit = {
    dut.io.stream.valid #= false
    dut.io.stream.payload.data #= 0
    dut.io.stream.payload.last #= false
  }

  test("ProtocolRx should parse TRAINING_GO (zero-payload command)") {
    SimConfig.withWave.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      val msg = makeMessage(CommsDefs.CmdId.TRAINING_GO, Seq())
      sendBytes(dut, msg)
      dut.clockDomain.waitRisingEdge()

      // trainingGo should have pulsed on the cycle the 4th header byte was consumed.
      // We check by sending and looking at the output right after.
      // Re-send and capture:
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(3)

      // Send again and watch the pulse
      var sawPulse = false
      for (b <- msg) {
        dut.io.stream.valid #= true
        dut.io.stream.payload.data #= (b.toInt & 0xFF)
        dut.io.stream.payload.last #= false
        dut.clockDomain.waitRisingEdge()
        if (dut.io.cmd.trainingGo.toBoolean) sawPulse = true
      }
      dut.io.stream.valid #= false
      // Also check the cycle after
      dut.clockDomain.waitRisingEdge()
      if (dut.io.cmd.trainingGo.toBoolean) sawPulse = true

      assert(sawPulse, "trainingGo should pulse")
    }
  }

  test("ProtocolRx should parse TRAINING_PAUSE") {
    SimConfig.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      var sawPulse = false
      val msg = makeMessage(CommsDefs.CmdId.TRAINING_PAUSE, Seq())
      for (b <- msg) {
        dut.io.stream.valid #= true
        dut.io.stream.payload.data #= (b.toInt & 0xFF)
        dut.io.stream.payload.last #= false
        dut.clockDomain.waitRisingEdge()
        if (dut.io.cmd.trainingPause.toBoolean) sawPulse = true
      }
      dut.io.stream.valid #= false
      dut.clockDomain.waitRisingEdge()
      if (dut.io.cmd.trainingPause.toBoolean) sawPulse = true

      assert(sawPulse, "trainingPause should pulse")
    }
  }

  test("ProtocolRx should parse PLAYER_INPUT") {
    SimConfig.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // 0x07 = bits 0,1,2 set = left + right + jump
      val msg = makeMessage(CommsDefs.CmdId.PLAYER_INPUT, Seq(0x07))

      var sawValid = false
      for (b <- msg) {
        dut.io.stream.valid #= true
        dut.io.stream.payload.data #= (b.toInt & 0xFF)
        dut.io.stream.payload.last #= false
        dut.clockDomain.waitRisingEdge()
        if (dut.io.humanInputValid.toBoolean) sawValid = true
      }
      dut.io.stream.valid #= false
      dut.clockDomain.waitRisingEdge()
      if (dut.io.humanInputValid.toBoolean) sawValid = true

      assert(sawValid, "humanInputValid should pulse")
      assert(dut.io.humanInput.left.toBoolean, "left should be set")
      assert(dut.io.humanInput.right.toBoolean, "right should be set")
      assert(dut.io.humanInput.jump.toBoolean, "jump should be set")
    }
  }

  test("ProtocolRx should parse INFERENCE_GO with playAgainstNn") {
    SimConfig.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      val msg = makeMessage(CommsDefs.CmdId.INFERENCE_GO, Seq(0x01))

      var sawPulse = false
      for (b <- msg) {
        dut.io.stream.valid #= true
        dut.io.stream.payload.data #= (b.toInt & 0xFF)
        dut.io.stream.payload.last #= false
        dut.clockDomain.waitRisingEdge()
        if (dut.io.cmd.inferenceGo.toBoolean) sawPulse = true
      }
      dut.io.stream.valid #= false
      dut.clockDomain.waitRisingEdge()
      if (dut.io.cmd.inferenceGo.toBoolean) sawPulse = true

      assert(sawPulse, "inferenceGo should pulse")
      assert(dut.io.cmd.playAgainstNn.toBoolean, "playAgainstNn should be latched true")
    }
  }

  test("ProtocolRx should parse GA_CONFIG") {
    SimConfig.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Build GA_CONFIG payload (145 bytes)
      val payload = new Array[Int](145)

      // mutation_rates[0..127] = index value
      for (i <- 0 until 128) payload(i) = i & 0xFF

      // maxGen = 0x1234 (LE: 0x34, 0x12)
      payload(128) = 0x34
      payload(129) = 0x12

      // runUntilStopCmd = true
      payload(130) = 0x01

      // tournamentSize = 4
      payload(131) = 0x04

      // populationSizeExp = 3
      payload(132) = 0x03

      // modelHistorySize = 5
      payload(133) = 0x05

      // modelHistoryInterval = 10
      payload(134) = 0x0A

      // seed = 0xDEADBEEF (LE: 0xEF, 0xBE, 0xAD, 0xDE)
      payload(135) = 0xEF
      payload(136) = 0xBE
      payload(137) = 0xAD
      payload(138) = 0xDE

      // referenceCount = 2
      payload(139) = 0x02

      // evalInterval = 7
      payload(140) = 0x07

      // seedCount = 8
      payload(141) = 0x08

      // frameLimit = 0x0500 (LE: 0x00, 0x05)
      payload(142) = 0x00
      payload(143) = 0x05

      // recycleSeeds = true
      payload(144) = 0x01

      val msg = makeMessage(CommsDefs.CmdId.GA_CONFIG, payload.toSeq)
      sendBytes(dut, msg)
      dut.clockDomain.waitRisingEdge(3)

      // Verify fields
      assert(dut.io.config.mutationRates(0).toInt == 0, "mutationRates(0)")
      assert(dut.io.config.mutationRates(127).toInt == 127, "mutationRates(127)")
      assert(dut.io.config.maxGen.toInt == 0x1234, s"maxGen should be 0x1234, got 0x${dut.io.config.maxGen.toInt.toHexString}")
      assert(dut.io.config.runUntilStopCmd.toBoolean, "runUntilStopCmd")
      assert(dut.io.config.tournamentSize.toInt == 4, "tournamentSize")
      assert(dut.io.config.populationSizeExp.toInt == 3, "populationSizeExp")
      assert(dut.io.config.modelHistorySize.toInt == 5, "modelHistorySize")
      assert(dut.io.config.modelHistoryInterval.toInt == 10, "modelHistoryInterval")
      assert((dut.io.config.seed.toInt & 0xFFFFFFFFL) == 0xDEADBEEFL,
        s"seed should be 0xDEADBEEF, got 0x${(dut.io.config.seed.toInt & 0xFFFFFFFFL).toHexString}")
      assert(dut.io.config.referenceCount.toInt == 2, "referenceCount")
      assert(dut.io.config.evalInterval.toInt == 7, "evalInterval")
      assert(dut.io.config.seedCount.toInt == 8, "seedCount")
      assert(dut.io.config.frameLimit.toInt == 0x0500, s"frameLimit should be 0x0500, got 0x${dut.io.config.frameLimit.toInt.toHexString}")
      assert(dut.io.config.recycleSeeds.toBoolean, "recycleSeeds")
    }
  }

  test("ProtocolRx should parse TILEMAP") {
    SimConfig.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      val payload = new Array[Int](518)

      // tiles[0..255]: set tile at (3,2) = GROUND (1), rest = NOTHING (0)
      // Index = y*16 + x, so (3,2) = 2*16 + 3 = 35
      payload(35) = 0x01 // GROUND

      // spawns[0]: x=5, y=7 (interleaved: byte 256=x, 257=y)
      payload(256) = 0x05
      payload(257) = 0x07

      // numSpawn = 1
      payload(512) = 0x01

      // numSpawnBits = 1
      payload(513) = 0x01

      // width = 16 (LE: 0x10, 0x00)
      payload(514) = 0x10
      payload(515) = 0x00

      // height = 12 (LE: 0x0C, 0x00)
      payload(516) = 0x0C
      payload(517) = 0x00

      val msg = makeMessage(CommsDefs.CmdId.TILEMAP, payload.toSeq)
      sendBytes(dut, msg)
      dut.clockDomain.waitRisingEdge(3)

      // Verify tile at (3,2) is GROUND
      assert(dut.io.tilemap.tiles(3)(2).toEnum == TileType.GROUND,
        s"tile(3,2) should be GROUND")

      // Verify tile at (0,0) is NOTHING
      assert(dut.io.tilemap.tiles(0)(0).toEnum == TileType.NOTHING,
        s"tile(0,0) should be NOTHING")

      // Verify spawn[0]
      assert(dut.io.tilemap.spawn(0).x.toInt == 5, "spawn(0).x")
      assert(dut.io.tilemap.spawn(0).y.toInt == 7, "spawn(0).y")

      assert(dut.io.tilemap.numSpawn.toInt == 1, "numSpawn")
      assert(dut.io.tilemap.width.toInt == 16, s"width should be 16, got ${dut.io.tilemap.width.toInt}")
      assert(dut.io.tilemap.height.toInt == 12, s"height should be 12, got ${dut.io.tilemap.height.toInt}")
    }
  }

  test("ProtocolRx should handle back-to-back messages") {
    SimConfig.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Send TRAINING_GO then PLAYER_INPUT back-to-back
      val msg1 = makeMessage(CommsDefs.CmdId.TRAINING_GO, Seq())
      val msg2 = makeMessage(CommsDefs.CmdId.PLAYER_INPUT, Seq(0x05)) // left + jump

      var sawTrainingGo = false
      var sawPlayerInput = false

      for (b <- msg1 ++ msg2) {
        dut.io.stream.valid #= true
        dut.io.stream.payload.data #= (b.toInt & 0xFF)
        dut.io.stream.payload.last #= false
        dut.clockDomain.waitRisingEdge()
        if (dut.io.cmd.trainingGo.toBoolean) sawTrainingGo = true
        if (dut.io.humanInputValid.toBoolean) sawPlayerInput = true
      }
      dut.io.stream.valid #= false
      dut.clockDomain.waitRisingEdge()
      if (dut.io.cmd.trainingGo.toBoolean) sawTrainingGo = true
      if (dut.io.humanInputValid.toBoolean) sawPlayerInput = true

      assert(sawTrainingGo, "trainingGo should have pulsed")
      assert(sawPlayerInput, "humanInputValid should have pulsed")
      assert(dut.io.humanInput.left.toBoolean, "left should be set")
      assert(!dut.io.humanInput.right.toBoolean, "right should not be set")
      assert(dut.io.humanInput.jump.toBoolean, "jump should be set")
    }
  }

  test("ProtocolRx should skip unknown message types") {
    SimConfig.compile(new ProtocolRx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Unknown type 0xFF with 3 payload bytes
      val msg1 = makeMessage(0xFF, Seq(0xAA, 0xBB, 0xCC))
      // Followed by a valid TRAINING_GO
      val msg2 = makeMessage(CommsDefs.CmdId.TRAINING_GO, Seq())

      var sawTrainingGo = false
      for (b <- msg1 ++ msg2) {
        dut.io.stream.valid #= true
        dut.io.stream.payload.data #= (b.toInt & 0xFF)
        dut.io.stream.payload.last #= false
        dut.clockDomain.waitRisingEdge()
        if (dut.io.cmd.trainingGo.toBoolean) sawTrainingGo = true
      }
      dut.io.stream.valid #= false
      dut.clockDomain.waitRisingEdge()
      if (dut.io.cmd.trainingGo.toBoolean) sawTrainingGo = true

      assert(sawTrainingGo, "Should parse TRAINING_GO after skipping unknown message")
    }
  }
}
