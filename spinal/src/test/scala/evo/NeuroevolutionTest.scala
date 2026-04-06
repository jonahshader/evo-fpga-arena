package evo

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._
import evo.types.MapConfig._

class NeuroevolutionTest extends AnyFunSuite {

  def setupTestTilemap(tm: Tilemap): Unit = {
    tm.width  #= 8
    tm.height #= 8
    for (y <- 0 until MAP_MAX_SIZE_TILES; x <- 0 until MAP_MAX_SIZE_TILES) {
      tm.tiles(y)(x) #= TileType.AIR
    }
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

  def initConfig(cfg: GaConfigBundle): Unit = {
    cfg.populationSizeExp    #= 2   // pop = 4
    cfg.modelHistorySize     #= 1
    cfg.modelHistoryInterval #= 1
    cfg.referenceCount       #= 1
    cfg.seedCount            #= 1
    cfg.tournamentSize       #= 2
    cfg.maxGen               #= 10
    cfg.frameLimit           #= 20
    cfg.evalInterval         #= 1
    cfg.seed                 #= 0xDEADBEEFL
    cfg.recycleSeeds         #= true
    cfg.runUntilStopCmd      #= false
    for (i <- 0 until GaConfig.MAX_POPULATION_SIZE) {
      cfg.mutationRates(i) #= 20
    }
  }

  def initDefaults(dut: Neuroevolution): Unit = {
    initConfig(dut.io.config)
    setupTestTilemap(dut.io.tilemap)
    dut.io.trainingGo      #= false
    dut.io.trainingPause   #= false
    dut.io.trainingResume  #= false
    dut.io.inferenceGo     #= false
    dut.io.inferenceStop   #= false
    dut.io.humanInput.left  #= false
    dut.io.humanInput.right #= false
    dut.io.humanInput.jump  #= false
    dut.io.humanInputValid #= false
    dut.io.playAgainstNn   #= false
    dut.io.dbBramDump      #= false
    dut.io.dbBramDumpIndex #= 0
  }

  test("Neuroevolution should elaborate and start in IDLE") {
    SimConfig.compile(new Neuroevolution).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      dut.clockDomain.waitRisingEdge(10)
      assert(dut.io.state.toEnum == NeState.IDLE, "Should start in IDLE state")
    }
  }

  test("Neuroevolution state transitions should work") {
    SimConfig.compile(new Neuroevolution).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      dut.clockDomain.waitRisingEdge(5)

      assert(dut.io.state.toEnum == NeState.IDLE)

      // IDLE -> PLAYING
      dut.io.inferenceGo #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.inferenceGo #= false
      dut.clockDomain.waitRisingEdge()
      assert(dut.io.state.toEnum == NeState.PLAYING, "Should transition to PLAYING")
      assert(dut.io.announceNewState.toBoolean, "Should announce state change")

      dut.clockDomain.waitRisingEdge()

      // PLAYING -> IDLE
      dut.io.inferenceStop #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.inferenceStop #= false
      dut.clockDomain.waitRisingEdge()
      assert(dut.io.state.toEnum == NeState.IDLE, "Should return to IDLE")
    }
  }

  test("Neuroevolution should complete training run (smoke)") {
    SimConfig.compile(new Neuroevolution).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initDefaults(dut)
      dut.clockDomain.waitRisingEdge(5)

      dut.io.trainingGo #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.trainingGo #= false

      var gaStateSendCount = 0
      var doneDetected = false

      for (cycle <- 0 until 2000000 if !doneDetected) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.gaStateSend.toBoolean) gaStateSendCount += 1
        if (cycle > 100 && dut.io.state.toEnum == NeState.IDLE) doneDetected = true
      }

      assert(doneDetected, "Training should complete within timeout")
      assert(gaStateSendCount == 10, s"Should report 10 generations, got $gaStateSendCount")
    }
  }

  def runExtendedTraining(dut: Neuroevolution, popExp: Int, maxGen: Int, frameLimit: Int,
                          tournamentSize: Int, timeout: Int): Unit = {
    initDefaults(dut)
    dut.io.config.populationSizeExp #= popExp
    dut.io.config.maxGen            #= maxGen
    dut.io.config.frameLimit        #= frameLimit
    dut.io.config.tournamentSize    #= tournamentSize
    dut.clockDomain.waitRisingEdge(5)

    dut.io.trainingGo #= true
    dut.clockDomain.waitRisingEdge()
    dut.io.trainingGo #= false

    val referenceFitnesses = scala.collection.mutable.ArrayBuffer[Int]()
    var doneDetected = false

    for (cycle <- 0 until timeout if !doneDetected) {
      dut.clockDomain.waitRisingEdge()
      if (dut.io.gaStateSend.toBoolean) {
        val gen    = dut.io.gaState.currentGen.toInt
        val refFit = dut.io.gaState.referenceFitness.toInt
        referenceFitnesses += refFit
        println(s"  Gen $gen: referenceFitness = $refFit")
      }
      if (cycle > 100 && dut.io.state.toEnum == NeState.IDLE) {
        doneDetected = true
        println(s"  Training completed at cycle $cycle")
      }
    }

    assert(doneDetected, "Training should complete within timeout")
    assert(referenceFitnesses.size == maxGen, s"Expected $maxGen generations, got ${referenceFitnesses.size}")
    println(s"  Fitness progression: ${referenceFitnesses.mkString(", ")}")

    val best = referenceFitnesses.max
    val worst = referenceFitnesses.min
    println(s"  Range: min=$worst, max=$best")
    assert(best > worst, "Fitness should show some variation across generations")
  }

  test("Neuroevolution extended training should show fitness improvement (pop=16)") {
    SimConfig.compile(new Neuroevolution).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      runExtendedTraining(dut, popExp = 4, maxGen = 30, frameLimit = 300,
                          tournamentSize = 3, timeout = 100000000)
    }
  }

  test("Neuroevolution full population training (pop=128)") {
    SimConfig.compile(new Neuroevolution).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      runExtendedTraining(dut, popExp = 7, maxGen = 10, frameLimit = 300,
                          tournamentSize = 5, timeout = 500000000)
    }
  }
}
