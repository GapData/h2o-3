/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package examples

import examples.Frequencies.Data
import hex.deeplearning.DeepLearningModel.DeepLearningParameters
import hex.deeplearning.{DlInput, DeepLearning, DeepLearningModel}
import water.fvec.{AppendableVec, Frame, NewChunk, Vec}
import water.{Futures, H2O, Key, TestUtil}

import scala.io.Source
import scala.language.postfixOps

/**
  * Demo for NYC meetup and MLConf 2015.
  *
  * It predicts spam text messages.
  * Training dataset is available in the file smalldata/smsData.txt.
  */
object HamOrSpamDemoTest extends TestUtil {
  ClassLoader.getSystemClassLoader.setDefaultAssertionStatus(true)
  
  val numFeatures = 1024

  val minDocFreq: Int = 4

  val freqModel = new Frequencies(numFeatures, minDocFreq)

  val DATAFILE = "smsData.txt"
  val TEST_MSGS = Seq(
    "Michal, beer tonight in MV?",
    "penis enlargement, our exclusive offer of penis enlargement, enlarge one, enlarge one free",
    "We tried to contact you re your reply to our offer of a Video Handset? 750 anytime any networks mins? UNLIMITED TEXT?"
  )

  def main(args: Array[String]) {
    TestUtil.stall_till_cloudsize(1)

    try {
      val (hs: List[String], msgs: List[String]) = readSamples
      val spamModel = new SpamModel(hs, msgs)

      TEST_MSGS.foreach(msg => {
        val whatitis = ("HAM"::"SPAM"::Nil)(spamModel.spamness(msg))
        println(s"$msg is $whatitis")
      })
    } finally {
      // Shutdown H2O
      //      h2oContext.stop(stopSparkContext = true)
      H2O.exit(0);
    }
  }

  def readSamples: (List[String], List[String]) = {
    val lines = readSamples("smalldata/" + DATAFILE)
    val size = lines.size
    val hs = lines map (_ (0))
    val msgs = lines map (_ (1))
    (hs, msgs)
  }

  def buildTable(id: String, trainingRows: List[CategorizedTexts]): Frame = {
    val fr = new Frame(trainingRows.head.names, catVecs(trainingRows))
    new water.fvec.H2OFrame(fr)
  }

  def readSamples(dataFile: String): List[Array[String]] = {
    val lines: Iterator[String] = Source.fromFile(dataFile, "ISO-8859-1").getLines()
    val pairs: Iterator[Array[String]] = lines.map(_.split("\t", 2))
    val goodOnes: Iterator[Array[String]] = pairs.filter(!_ (0).isEmpty)
    goodOnes.toList
  }

  val IgnoreWords = Set("the", "not", "for")
  val IgnoreChars = "[,:;/<>\".()?\\-\\\'!01 ]"

  def tokenize(s: String) = {
    var smsText = s.toLowerCase.replaceAll(IgnoreChars, " ").replaceAll("  +", " ").trim
    val words = smsText split " " filter (w => !IgnoreWords(w) && w.length > 2)

    words.toSeq
  }

  case class SpamModel(hs: List[String], msgs: List[String]) {

    lazy val tf: List[Data] = msgs map freqModel.weigh

    // Build term frequency-inverse document frequency
    lazy val idf: freqModel.IDF = (new freqModel.IDF() /: tf) (_ + _)

    lazy val weights: List[Array[Double]] = tf map idf.normalize
    
    lazy val categorizedTexts = hs zip weights map CategorizedTexts.tupled
    
    lazy val cutoff = (categorizedTexts.length * 0.8).toInt
    // Split table
    lazy val (before, after) = categorizedTexts.splitAt(cutoff)
    lazy val train = buildTable("train", before)
    lazy val valid = buildTable("valid", after)
println("v1 = " + train.lastVecName() + ", v2=" + valid.lastVecName() + "/" + train.lastVec())
    lazy val dlModel = buildDLModel(train, valid, catData(before), catData(after))
    
    /** Spam detector */
    def spamness(msg: String) = {
      val weights = freqModel.weigh(msg)
      val normalizedWeights = idf.normalize(weights)
      val estimate: Double = dlModel.scoreSample(normalizedWeights)
      estimate.toInt
    }
  }

  /** Builds DeepLearning model. */
  def buildDLModel(train: Frame, valid: Frame,
                   trainData: DlInput, testData: DlInput,

  epochs: Int = 10, l1: Double = 0.001,
                   hidden: Array[Int] = Array[Int](200, 200)): DeepLearningModel = {
    val v1 = train.vec("target")
    
    assert(v1.length() == trainData.target.size())
    for (i <- 0L until v1.length) {
      assert(v1.at(i) == trainData.target(i.toInt))
    }
    
    val dlParams = new DeepLearningParameters()
    dlParams._train = train._key
    println("Train was " + train.lastVecName())
    println("Now train is " + dlParams.train().lastVecName())
    dlParams._valid = valid._key
    dlParams.trainData = trainData
    dlParams.testData = testData
    dlParams._response_column = "target"
    dlParams._epochs = epochs
    dlParams._l1 = l1
    dlParams._hidden = hidden
    dlParams._ignore_const_cols = false // TODO(vlad): figure out how important is it

    val jobKey: Key[DeepLearningModel] = water.Key.make("dlModel.hex")
    val dl = new DeepLearning(dlParams, jobKey)
//    val tmi = dl.trainModelImpl()
//    tmi.computeImpl()
//    dl.checkMyConditions()

    val tm = dl.trainModel()
    tm.waitTillFinish()
    tm._result.get()
//    tm.modelWeBuild
  }

  /** A numeric Vec from an array of doubles */
  def dvec(values: Iterable[Double]): Vec = {
    val k: Key[Vec] = Vec.VectorGroup.VG_LEN1.addVec()
    val avec: AppendableVec = new AppendableVec(k, Vec.T_NUM)
    val chunk: NewChunk = new NewChunk(avec, 0)
    for (r <- values) chunk.addNum(r)
    commit(avec, chunk)
  }

  def commit(avec: AppendableVec, chunk: NewChunk): Vec = {
    val fs: Futures = new Futures
    chunk.close(0, fs)
    val vec: Vec = avec.layout_and_close(fs)
    fs.blockForPending()
    vec
  }

  def vec(domain: Array[String], rows: Iterable[Int]): Vec = {
    val k: Key[Vec] = Vec.VectorGroup.VG_LEN1.addVec()
    val avec: AppendableVec = new AppendableVec(k, Vec.T_NUM)
    avec.setDomain(domain)
    val chunk: NewChunk = new NewChunk(avec, 0)
    for (r <- rows) chunk.addNum(r)
    commit(avec, chunk)
  }

  val CatDomain = "ham" :: "spam" :: Nil toArray

  import scala.collection.JavaConverters._

  case class CategorizedTexts(targetText: String, weights: Array[Double]) {

    def target: Int = CatDomain indexOf targetText

    def name(i: Int) = "fv" + i

    lazy val names: Array[String] = ("target" :: (weights.indices map name).toList) toArray
  }

  def catData(rows: List[CategorizedTexts]): DlInput = {
    val row0 = rows.head
    val target:java.util.List[Integer] = (rows map (_.target) map Integer.valueOf) asJava
    
    val columns:List[List[Double]] = row0.weights.indices.map(i => rows.map(_.weights(i)):List[Double]).toList

    val javaColumns: java.util.List[java.util.List[java.lang.Double]] = columns.map(
      column => (column.map(java.lang.Double.valueOf)).asJava) asJava

    new DlInput(target, target.size, javaColumns)
  }
  
  def catVecs(rows: Iterable[CategorizedTexts]): Array[Vec] = {
    val row0 = rows.head
    val targetVec = vec(CatDomain, rows map (_.target))
    val vecs = row0.weights.indices.map(
      i => dvec(rows map (_.weights(i))))

    (targetVec :: vecs.toList) toArray
  }

  case class VectorOfDoubles(fv: Data) {
    def name(i: Int) = "fv" + i

    def names: Array[String] = fv.indices map name toArray

    def vecs: Array[Vec] = fv map (x => dvec(x :: Nil))

    def frame: Frame = new Frame(names, vecs)
  }

}

