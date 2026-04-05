package evo.game

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._
import evo.types.MapConfig._

class PlayAGameTest extends AnyFunSuite {

  def setupTestTilemap(tm: Tilemap): Unit = {
    tm.width  #= 8
    tm.height #= 8

    for (y <- 0 until MAP_MAX_SIZE_TILES; x <- 0 until MAP_MAX_SIZE_TILES) {
      tm.tiles(y)(x) #= TileType.AIR
    }
    // Ground at bottom
    for (x <- 0 until 8) {
      tm.tiles(7)(x) #= TileType.GROUND
    }
    for (i <- 0 until MAP_MAX_SPAWNS) {
      tm.spawn(i).x #= (i % 6) + 1
      tm.spawn(i).y #= 1
    }
    tm.numSpawn     #= 6
    tm.numSpawnBits #= 3
  }

  test("PlayAGame should elaborate and run a short game") {
    SimConfig.withWave.compile(new PlayAGame).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.swapStart  #= false
      dut.io.seed       #= 0x12345678L
      dut.io.frameLimit #= 3
      dut.io.gameGo     #= false

      dut.io.p1Input.left  #= false
      dut.io.p1Input.right #= false
      dut.io.p1Input.jump  #= false
      dut.io.p1InputValid  #= false

      dut.io.p2Input.left  #= false
      dut.io.p2Input.right #= false
      dut.io.p2Input.jump  #= false
      dut.io.p2InputValid  #= false

      setupTestTilemap(dut.io.tilemap)

      dut.clockDomain.waitRisingEdge(5)

      // Start game
      dut.io.gameGo #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.gameGo #= false

      // Run frame loop: respond to requestInput, provide input, wait for frame
      var doneSeen = false
      for (_ <- 0 until 500 if !doneSeen) {
        dut.clockDomain.waitRisingEdge()

        // When request_input pulses, provide valid inputs
        if (dut.io.p1RequestInput.toBoolean) {
          dut.io.p1InputValid #= true
          dut.io.p2InputValid #= true
        } else {
          dut.io.p1InputValid #= false
          dut.io.p2InputValid #= false
        }

        if (dut.io.gameDone.toBoolean) doneSeen = true
      }

      assert(doneSeen, "PlayAGame should complete after frame_limit frames")
      println(s"PlayAGame test passed, final score: ${dut.io.scoreOutput.toInt}")
    }
  }
}
