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

package org.apache.spark

import java.io._
import java.nio.ByteBuffer
import java.util.zip.GZIPOutputStream

import scala.io.Source

import org.apache.hadoop.fs.Path
import org.apache.hadoop.io._
import org.apache.hadoop.io.compress.DefaultCodec
import org.apache.hadoop.mapred.{FileAlreadyExistsException, FileSplit, JobConf, TextInputFormat, TextOutputFormat}
import org.apache.hadoop.mapreduce.Job
import org.apache.hadoop.mapreduce.lib.input.{FileSplit => NewFileSplit, TextInputFormat => NewTextInputFormat}
import org.apache.hadoop.mapreduce.lib.output.{TextOutputFormat => NewTextOutputFormat}

import org.apache.spark.internal.config.IGNORE_CORRUPT_FILES
import org.apache.spark.rdd.{HadoopRDD, NewHadoopRDD}
import org.apache.spark.storage.StorageLevel
import org.apache.spark.util.Utils

class FileSuite extends SparkFunSuite with LocalSparkContext {
  var tempDir: File = _

  override def beforeEach() {
    super.beforeEach()
    tempDir = Utils.createTempDir()
  }

  override def afterEach() {
    try {
      Utils.deleteRecursively(tempDir)
    } finally {
      super.afterEach()
    }
  }

  test("text files") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 4)
    nums.saveAsTextFile(outputDir)
    // Read the plain text file and check it's OK
    val outputFile = new File(outputDir, "part-00000")
    val bufferSrc = Source.fromFile(outputFile)
    Utils.tryWithSafeFinally {
      val content = bufferSrc.mkString
      assert(content === "1\n2\n3\n4\n")
      // Also try reading it in as a text file RDD
      assert(sc.textFile(outputDir).collect().toList === List("1", "2", "3", "4"))
    } {
      bufferSrc.close()
    }
  }

  test("text files (compressed)") {
    sc = new SparkContext("local", "test")
    val normalDir = new File(tempDir, "output_normal").getAbsolutePath
    val compressedOutputDir = new File(tempDir, "output_compressed").getAbsolutePath
    val codec = new DefaultCodec()

    val data = sc.parallelize("a" * 10000, 1)
    data.saveAsTextFile(normalDir)
    data.saveAsTextFile(compressedOutputDir, classOf[DefaultCodec])

    val normalFile = new File(normalDir, "part-00000")
    val normalContent = sc.textFile(normalDir).collect
    assert(normalContent === Array.fill(10000)("a"))

    val compressedFile = new File(compressedOutputDir, "part-00000" + codec.getDefaultExtension)
    val compressedContent = sc.textFile(compressedOutputDir).collect
    assert(compressedContent === Array.fill(10000)("a"))

    assert(compressedFile.length < normalFile.length)
  }

  test("SequenceFiles") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (x, "a" * x)) // (1,a), (2,aa), (3,aaa)
    nums.saveAsSequenceFile(outputDir)
    // Try reading the output back as a SequenceFile
    val output = sc.sequenceFile[IntWritable, Text](outputDir)
    assert(output.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
  }

  test("SequenceFile (compressed)") {
    sc = new SparkContext("local", "test")
    val normalDir = new File(tempDir, "output_normal").getAbsolutePath
    val compressedOutputDir = new File(tempDir, "output_compressed").getAbsolutePath
    val codec = new DefaultCodec()

    val data = sc.parallelize(Seq.fill(100)("abc"), 1).map(x => (x, x))
    data.saveAsSequenceFile(normalDir)
    data.saveAsSequenceFile(compressedOutputDir, Some(classOf[DefaultCodec]))

    val normalFile = new File(normalDir, "part-00000")
    val normalContent = sc.sequenceFile[String, String](normalDir).collect
    assert(normalContent === Array.fill(100)("abc", "abc"))

    val compressedFile = new File(compressedOutputDir, "part-00000" + codec.getDefaultExtension)
    val compressedContent = sc.sequenceFile[String, String](compressedOutputDir).collect
    assert(compressedContent === Array.fill(100)("abc", "abc"))

    assert(compressedFile.length < normalFile.length)
  }

  test("SequenceFile with writable key") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (new IntWritable(x), "a" * x))
    nums.saveAsSequenceFile(outputDir)
    // Try reading the output back as a SequenceFile
    val output = sc.sequenceFile[IntWritable, Text](outputDir)
    assert(output.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
  }

  test("SequenceFile with writable value") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (x, new Text("a" * x)))
    nums.saveAsSequenceFile(outputDir)
    // Try reading the output back as a SequenceFile
    val output = sc.sequenceFile[IntWritable, Text](outputDir)
    assert(output.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
  }

  test("SequenceFile with writable key and value") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (new IntWritable(x), new Text("a" * x)))
    nums.saveAsSequenceFile(outputDir)
    // Try reading the output back as a SequenceFile
    val output = sc.sequenceFile[IntWritable, Text](outputDir)
    assert(output.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
  }

  test("implicit conversions in reading SequenceFiles") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (x, "a" * x)) // (1,a), (2,aa), (3,aaa)
    nums.saveAsSequenceFile(outputDir)
    // Similar to the tests above, we read a SequenceFile, but this time we pass type params
    // that are convertable to Writable instead of calling sequenceFile[IntWritable, Text]
    val output1 = sc.sequenceFile[Int, String](outputDir)
    assert(output1.collect().toList === List((1, "a"), (2, "aa"), (3, "aaa")))
    // Also try having one type be a subclass of Writable and one not
    val output2 = sc.sequenceFile[Int, Text](outputDir)
    assert(output2.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
    val output3 = sc.sequenceFile[IntWritable, String](outputDir)
    assert(output3.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
  }

  test("object files of ints") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 4)
    nums.saveAsObjectFile(outputDir)
    // Try reading the output back as an object file
    val output = sc.objectFile[Int](outputDir)
    assert(output.collect().toList === List(1, 2, 3, 4))
  }

  test("object files of complex types") {
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (x, "a" * x))
    nums.saveAsObjectFile(outputDir)
    // Try reading the output back as an object file
    val output = sc.objectFile[(Int, String)](outputDir)
    assert(output.collect().toList === List((1, "a"), (2, "aa"), (3, "aaa")))
  }

  test("object files of classes from a JAR") {
    // scalastyle:off classforname
    val original = Thread.currentThread().getContextClassLoader
    val className = "FileSuiteObjectFileTest"
    val jar = TestUtils.createJarWithClasses(Seq(className))
    val loader = new java.net.URLClassLoader(Array(jar), Utils.getContextOrSparkClassLoader)
    Thread.currentThread().setContextClassLoader(loader)
    try {
      sc = new SparkContext("local", "test")
      val objs = sc.makeRDD(1 to 3).map { x =>
        val loader = Thread.currentThread().getContextClassLoader
        Class.forName(className, true, loader).newInstance()
      }
      val outputDir = new File(tempDir, "output").getAbsolutePath
      objs.saveAsObjectFile(outputDir)
      // Try reading the output back as an object file
      val ct = reflect.ClassTag[Any](Class.forName(className, true, loader))
      val output = sc.objectFile[Any](outputDir)
      assert(output.collect().size === 3)
      assert(output.collect().head.getClass.getName === className)
    }
    finally {
      Thread.currentThread().setContextClassLoader(original)
    }
    // scalastyle:on classforname
  }

  test("write SequenceFile using new Hadoop API") {
    import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (new IntWritable(x), new Text("a" * x)))
    nums.saveAsNewAPIHadoopFile[SequenceFileOutputFormat[IntWritable, Text]](
        outputDir)
    val output = sc.sequenceFile[IntWritable, Text](outputDir)
    assert(output.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
  }

  test("read SequenceFile using new Hadoop API") {
    import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat
    sc = new SparkContext("local", "test")
    val outputDir = new File(tempDir, "output").getAbsolutePath
    val nums = sc.makeRDD(1 to 3).map(x => (new IntWritable(x), new Text("a" * x)))
    nums.saveAsSequenceFile(outputDir)
    val output =
      sc.newAPIHadoopFile[IntWritable, Text, SequenceFileInputFormat[IntWritable, Text]](outputDir)
    assert(output.map(_.toString).collect().toList === List("(1,a)", "(2,aa)", "(3,aaa)"))
  }

  private def writeBinaryData(testOutput: Array[Byte], testOutputCopies: Int): File = {
    val outFile = new File(tempDir, "record-bytestream-00000.bin")
    val file = new FileOutputStream(outFile)
    val channel = file.getChannel
    for (i <- 0 until testOutputCopies) {
      // Shift values by i so that they're different in the output
      val alteredOutput = testOutput.map(b => (b + i).toByte)
      channel.write(ByteBuffer.wrap(alteredOutput))
    }
    channel.close()
    file.close()
    outFile
  }

  test("binary file input as byte array") {
    sc = new SparkContext("local", "test")
    val testOutput = Array[Byte](1, 2, 3, 4, 5, 6)
    val outFile = writeBinaryData(testOutput, 1)
    val inRdd = sc.binaryFiles(outFile.getAbsolutePath)
    val (infile, indata) = inRdd.collect().head
    // Make sure the name and array match
    assert(infile.contains(outFile.toURI.getPath)) // a prefix may get added
    assert(indata.toArray === testOutput)
  }

  test("portabledatastream caching tests") {
    sc = new SparkContext("local", "test")
    val testOutput = Array[Byte](1, 2, 3, 4, 5, 6)
    val outFile = writeBinaryData(testOutput, 1)
    val inRdd = sc.binaryFiles(outFile.getAbsolutePath).cache()
    inRdd.foreach(_._2.toArray()) // force the file to read
    // Try reading the output back as an object file
    assert(inRdd.values.collect().head.toArray === testOutput)
  }

  test("portabledatastream persist disk storage") {
    sc = new SparkContext("local", "test")
    val testOutput = Array[Byte](1, 2, 3, 4, 5, 6)
    val outFile = writeBinaryData(testOutput, 1)
    val inRdd = sc.binaryFiles(outFile.getAbsolutePath).persist(StorageLevel.DISK_ONLY)
    inRdd.foreach(_._2.toArray()) // force the file to read
    assert(inRdd.values.collect().head.toArray === testOutput)
  }

  test("portabledatastream flatmap tests") {
    sc = new SparkContext("local", "test")
    val testOutput = Array[Byte](1, 2, 3, 4, 5, 6)
    val outFile = writeBinaryData(testOutput, 1)
    val inRdd = sc.binaryFiles(outFile.getAbsolutePath)
    val numOfCopies = 3
    val copyRdd = inRdd.flatMap(curData => (0 until numOfCopies).map(_ => curData._2))
    val copyArr = copyRdd.collect()
    assert(copyArr.length == numOfCopies)
    for (i <- copyArr.indices) {
      assert(copyArr(i).toArray === testOutput)
    }
  }

  test("fixed record length binary file as byte array") {
    sc = new SparkContext("local", "test")
    val testOutput = Array[Byte](1, 2, 3, 4, 5, 6)
    val testOutputCopies = 10
    val outFile = writeBinaryData(testOutput, testOutputCopies)
    val inRdd = sc.binaryRecords(outFile.getAbsolutePath, testOutput.length)
    assert(inRdd.count == testOutputCopies)
    val inArr = inRdd.collect()
    for (i <- inArr.indices) {
      assert(inArr(i) === testOutput.map(b => (b + i).toByte))
    }
  }

  test ("negative binary record length should raise an exception") {
    sc = new SparkContext("local", "test")
    val outFile = writeBinaryData(Array[Byte](1, 2, 3, 4, 5, 6), 1)
    intercept[SparkException] {
      sc.binaryRecords(outFile.getAbsolutePath, -1).count()
    }
  }

  test("file caching") {
    sc = new SparkContext("local", "test")
    val out = new FileWriter(tempDir + "/input")
    out.write("Hello world!\n")
    out.write("What's up?\n")
    out.write("Goodbye\n")
    out.close()
    val rdd = sc.textFile(tempDir + "/input").cache()
    assert(rdd.count() === 3)
    assert(rdd.count() === 3)
    assert(rdd.count() === 3)
  }

  test ("prevent user from overwriting the empty directory (old Hadoop API)") {
    sc = new SparkContext("local", "test")
    val randomRDD = sc.parallelize(Array((1, "a"), (1, "a"), (2, "b"), (3, "c")), 1)
    intercept[FileAlreadyExistsException] {
      randomRDD.saveAsTextFile(tempDir.getPath)
    }
  }

  test ("prevent user from overwriting the non-empty directory (old Hadoop API)") {
    sc = new SparkContext("local", "test")
    val randomRDD = sc.parallelize(Array((1, "a"), (1, "a"), (2, "b"), (3, "c")), 1)
    randomRDD.saveAsTextFile(tempDir.getPath + "/output")
    assert(new File(tempDir.getPath + "/output/part-00000").exists() === true)
    intercept[FileAlreadyExistsException] {
      randomRDD.saveAsTextFile(tempDir.getPath + "/output")
    }
  }

  test ("allow user to disable the output directory existence checking (old Hadoop API") {
    val sf = new SparkConf()
    sf.setAppName("test").setMaster("local").set("spark.hadoop.validateOutputSpecs", "false")
    sc = new SparkContext(sf)
    val randomRDD = sc.parallelize(Array((1, "a"), (1, "a"), (2, "b"), (3, "c")), 1)
    randomRDD.saveAsTextFile(tempDir.getPath + "/output")
    assert(new File(tempDir.getPath + "/output/part-00000").exists() === true)
    randomRDD.saveAsTextFile(tempDir.getPath + "/output")
    assert(new File(tempDir.getPath + "/output/part-00000").exists() === true)
  }

  test ("prevent user from overwriting the empty directory (new Hadoop API)") {
    sc = new SparkContext("local", "test")
    val randomRDD = sc.parallelize(
      Array(("key1", "a"), ("key2", "a"), ("key3", "b"), ("key4", "c")), 1)
    intercept[FileAlreadyExistsException] {
      randomRDD.saveAsNewAPIHadoopFile[NewTextOutputFormat[String, String]](tempDir.getPath)
    }
  }

  test ("prevent user from overwriting the non-empty directory (new Hadoop API)") {
    sc = new SparkContext("local", "test")
    val randomRDD = sc.parallelize(
      Array(("key1", "a"), ("key2", "a"), ("key3", "b"), ("key4", "c")), 1)
    randomRDD.saveAsNewAPIHadoopFile[NewTextOutputFormat[String, String]](
      tempDir.getPath + "/output")
    assert(new File(tempDir.getPath + "/output/part-r-00000").exists() === true)
    intercept[FileAlreadyExistsException] {
      randomRDD.saveAsNewAPIHadoopFile[NewTextOutputFormat[String, String]](tempDir.getPath)
    }
  }

  test ("allow user to disable the output directory existence checking (new Hadoop API") {
    val sf = new SparkConf()
    sf.setAppName("test").setMaster("local").set("spark.hadoop.validateOutputSpecs", "false")
    sc = new SparkContext(sf)
    val randomRDD = sc.parallelize(
      Array(("key1", "a"), ("key2", "a"), ("key3", "b"), ("key4", "c")), 1)
    randomRDD.saveAsNewAPIHadoopFile[NewTextOutputFormat[String, String]](
      tempDir.getPath + "/output")
    assert(new File(tempDir.getPath + "/output/part-r-00000").exists() === true)
    randomRDD.saveAsNewAPIHadoopFile[NewTextOutputFormat[String, String]](
      tempDir.getPath + "/output")
    assert(new File(tempDir.getPath + "/output/part-r-00000").exists() === true)
  }

  test ("save Hadoop Dataset through old Hadoop API") {
    sc = new SparkContext("local", "test")
    val randomRDD = sc.parallelize(
      Array(("key1", "a"), ("key2", "a"), ("key3", "b"), ("key4", "c")), 1)
    val job = new JobConf()
    job.setOutputKeyClass(classOf[String])
    job.setOutputValueClass(classOf[String])
    job.set("mapred.output.format.class", classOf[TextOutputFormat[String, String]].getName)
    job.set("mapreduce.output.fileoutputformat.outputdir", tempDir.getPath + "/outputDataset_old")
    randomRDD.saveAsHadoopDataset(job)
    assert(new File(tempDir.getPath + "/outputDataset_old/part-00000").exists() === true)
  }

  test ("save Hadoop Dataset through new Hadoop API") {
    sc = new SparkContext("local", "test")
    val randomRDD = sc.parallelize(
      Array(("key1", "a"), ("key2", "a"), ("key3", "b"), ("key4", "c")), 1)
    val job = Job.getInstance(sc.hadoopConfiguration)
    job.setOutputKeyClass(classOf[String])
    job.setOutputValueClass(classOf[String])
    job.setOutputFormatClass(classOf[NewTextOutputFormat[String, String]])
    val jobConfig = job.getConfiguration
    jobConfig.set("mapreduce.output.fileoutputformat.outputdir",
      tempDir.getPath + "/outputDataset_new")
    randomRDD.saveAsNewAPIHadoopDataset(jobConfig)
    assert(new File(tempDir.getPath + "/outputDataset_new/part-r-00000").exists() === true)
  }

  test("Get input files via old Hadoop API") {
    sc = new SparkContext("local", "test")
    val outDir = new File(tempDir, "output").getAbsolutePath
    sc.makeRDD(1 to 4, 2).saveAsTextFile(outDir)

    val inputPaths =
      sc.hadoopFile(outDir, classOf[TextInputFormat], classOf[LongWritable], classOf[Text])
        .asInstanceOf[HadoopRDD[_, _]]
        .mapPartitionsWithInputSplit { (split, part) =>
          Iterator(split.asInstanceOf[FileSplit].getPath.toUri.getPath)
        }.collect()
    val outPathOne = new Path(outDir, "part-00000").toUri.getPath
    val outPathTwo = new Path(outDir, "part-00001").toUri.getPath
    assert(inputPaths.toSet === Set(outPathOne, outPathTwo))
  }

  test("Get input files via new Hadoop API") {
    sc = new SparkContext("local", "test")
    val outDir = new File(tempDir, "output").getAbsolutePath
    sc.makeRDD(1 to 4, 2).saveAsTextFile(outDir)

    val inputPaths =
      sc.newAPIHadoopFile(outDir, classOf[NewTextInputFormat], classOf[LongWritable], classOf[Text])
        .asInstanceOf[NewHadoopRDD[_, _]]
        .mapPartitionsWithInputSplit { (split, part) =>
          Iterator(split.asInstanceOf[NewFileSplit].getPath.toUri.getPath)
        }.collect()
    val outPathOne = new Path(outDir, "part-00000").toUri.getPath
    val outPathTwo = new Path(outDir, "part-00001").toUri.getPath
    assert(inputPaths.toSet === Set(outPathOne, outPathTwo))
  }

  test("spark.files.ignoreCorruptFiles should work both HadoopRDD and NewHadoopRDD") {
    val inputFile = File.createTempFile("input-", ".gz")
    try {
      // Create a corrupt gzip file
      val byteOutput = new ByteArrayOutputStream()
      val gzip = new GZIPOutputStream(byteOutput)
      try {
        gzip.write(Array[Byte](1, 2, 3, 4))
      } finally {
        gzip.close()
      }
      val bytes = byteOutput.toByteArray
      val o = new FileOutputStream(inputFile)
      try {
        // It's corrupt since we only write half of bytes into the file.
        o.write(bytes.take(bytes.length / 2))
      } finally {
        o.close()
      }

      // Reading a corrupt gzip file should throw EOFException
      sc = new SparkContext("local", "test")
      // Test HadoopRDD
      var e = intercept[SparkException] {
        sc.textFile(inputFile.toURI.toString).collect()
      }
      assert(e.getCause.isInstanceOf[EOFException])
      assert(e.getCause.getMessage === "Unexpected end of input stream")
      // Test NewHadoopRDD
      e = intercept[SparkException] {
        sc.newAPIHadoopFile(
          inputFile.toURI.toString,
          classOf[NewTextInputFormat],
          classOf[LongWritable],
          classOf[Text]).collect()
      }
      assert(e.getCause.isInstanceOf[EOFException])
      assert(e.getCause.getMessage === "Unexpected end of input stream")
      sc.stop()

      val conf = new SparkConf().set(IGNORE_CORRUPT_FILES, true)
      sc = new SparkContext("local", "test", conf)
      // Test HadoopRDD
      assert(sc.textFile(inputFile.toURI.toString).collect().isEmpty)
      // Test NewHadoopRDD
      assert {
        sc.newAPIHadoopFile(
          inputFile.toURI.toString,
          classOf[NewTextInputFormat],
          classOf[LongWritable],
          classOf[Text]).collect().isEmpty
      }
    } finally {
      inputFile.delete()
    }
  }

}
