/*
 * Copyright 2009 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.service.admin

import java.io.InputStream
/*
import net.lag.logging.{Level, Logger}
import java.io.IOException
*/
import java.net.{ConnectException, Socket}
import com.twitter.json.Json
import com.twitter.stats.Stats
import com.twitter.xrayspecs.Eventually
import net.lag.configgy.RuntimeEnvironment
import org.specs._
/*import scala.collection.jcl*/

object AdminHttpServiceSpec extends Specification with Eventually {
  class PimpedInputStream(stream: InputStream) {
    def readString(maxBytes: Int) = {
      val buffer = new Array[Byte](maxBytes)
      val len = stream.read(buffer)
      new String(buffer, 0, len, "UTF-8")
    }
  }
  implicit def pimpInputStream(stream: InputStream) = new PimpedInputStream(stream)

  "AdminHttpService" should {
    var service: AdminHttpService = null
    var server: MockServerInterface = null

    doBefore {
      new Socket("localhost", 9990) must throwA[ConnectException]
      server = new MockServerInterface
      service = new AdminHttpService(server, new RuntimeEnvironment(getClass))
      AdminService.webServer = service
      service.start()
    }

    doAfter {
      service.stop()
      new Socket("localhost", 9990) must eventually(throwA[ConnectException])
      AdminService.webServer = null
    }

    "start and stop" in {
      new Socket("localhost", 9990) must notBeNull
      service.stop()
      new Socket("localhost", 9990) must throwA[ConnectException]
    }

    "answer pings" in {
      val socket = new Socket("localhost", 9990)
      socket.getOutputStream().write("get /ping\n".getBytes)
      socket.getInputStream().readString(1024).split("\n").last mustEqual "\"pong\""
    }

    "shutdown" in {
      server.askedToShutdown mustBe false
      val socket = new Socket("localhost", 9990)
      socket.getOutputStream().write("get /shutdown\n".getBytes)
      server.askedToShutdown must eventually(beTrue)
      new Socket("localhost", 9990) must eventually(throwA[ConnectException])
    }

    "quiesce" in {
      server.askedToQuiesce mustBe false
      val socket = new Socket("localhost", 9990)
      socket.getOutputStream().write("get /quiesce\n".getBytes)
      server.askedToQuiesce must eventually(beTrue)
      new Socket("localhost", 9990) must eventually(throwA[ConnectException])
    }

    "provide stats" in {
      "in json" in {
        // make some statsy things happen
        Stats.clearAll()
        Stats.time("kangaroo_time") { Stats.incr("kangaroos", 1) }

        val socket = new Socket("localhost", 9990)
        socket.getOutputStream().write("get /stats\n".getBytes)
        val stats = Json.parse(socket.getInputStream().readString(1024).split("\n").last).asInstanceOf[Map[String, Map[String, AnyRef]]]
        stats("jvm") must haveKey("uptime")
        stats("jvm") must haveKey("heap_used")
        stats("counters") must haveKey("kangaroos")
        stats("timings") must haveKey("kangaroo_time")
        val timing = stats("timings")("kangaroo_time").asInstanceOf[Map[String, Int]]
        timing("count") mustEqual 1
        timing("average") mustEqual timing("minimum")
        timing("average") mustEqual timing("maximum")
      }

      "in json, with reset" in {
        // make some statsy things happen
        Stats.clearAll()
        Stats.time("kangaroo_time") { Stats.incr("kangaroos", 1) }

        val socket = new Socket("localhost", 9990)
        socket.getOutputStream().write("get /stats/reset\n".getBytes)
        val stats = Json.parse(socket.getInputStream().readString(1024).split("\n").last).asInstanceOf[Map[String, Map[String, AnyRef]]]
        val timing = stats("timings")("kangaroo_time").asInstanceOf[Map[String, Int]]
        timing("count") mustEqual 1

        val socket2 = new Socket("localhost", 9990)
        socket2.getOutputStream().write("get /stats/reset\n".getBytes)
        val stats2 = Json.parse(socket2.getInputStream().readString(1024).split("\n").last).asInstanceOf[Map[String, Map[String, AnyRef]]]
        val timing2 = stats2("timings")("kangaroo_time").asInstanceOf[Map[String, Int]]
        timing2("count") mustEqual 0
      }

      "in text" in {
        // make some statsy things happen
        Stats.clearAll()
        Stats.time("kangaroo_time") { Stats.incr("kangaroos", 1) }

        val socket = new Socket("localhost", 9990)
        socket.getOutputStream().write("get /stats.txt\n".getBytes)
        val response = socket.getInputStream().readString(1024).split("\n")
        response mustContain "kangaroos: 1"
      }
    }
  }
}
