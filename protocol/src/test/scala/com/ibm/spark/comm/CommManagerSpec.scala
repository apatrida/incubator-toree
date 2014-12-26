/*
 * Copyright 2014 IBM Corp.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.ibm.spark.comm

import com.ibm.spark.comm.CommCallbacks.{CloseCallback, OpenCallback}
import com.ibm.spark.kernel.protocol.v5
import com.ibm.spark.kernel.protocol.v5.UUID
import com.ibm.spark.kernel.protocol.v5.content.{CommClose, CommOpen}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, Matchers, FunSpec}
import org.mockito.Mockito._
import org.mockito.Matchers.{eq => mockEq, _}

class CommManagerSpec extends FunSpec with Matchers with BeforeAndAfter
  with MockitoSugar
{
  private val TestTargetName = "some target"
  private val TestCommId = java.util.UUID.randomUUID().toString

  private var mockCommWriter: CommWriter = _
  private var mockCommRegistrar: CommRegistrar = _
  private var commManager: CommManager = _

  before {
    mockCommWriter = mock[CommWriter]
    mockCommRegistrar = mock[CommRegistrar]
    doReturn(mockCommRegistrar).when(mockCommRegistrar)
      .register(anyString())
    doReturn(mockCommRegistrar).when(mockCommRegistrar)
      .addOpenHandler(any(classOf[OpenCallback]))
    doReturn(mockCommRegistrar).when(mockCommRegistrar)
      .addCloseHandler(any(classOf[CloseCallback]))

    commManager = new CommManager(mockCommRegistrar) {
      override protected def newCommWriter(commId: UUID) = mockCommWriter
    }
  }

  describe("CommManager") {
    describe("#register") {
      it("should register the target name provided") {
        commManager.register(TestTargetName)

        verify(mockCommRegistrar).register(TestTargetName)
      }

      // TODO: Is there a better/cleaner way to assert the contents of the callback?
      it("should add a link callback to the received open events") {
        var linkFunc: OpenCallback = null

        // Setup used to extract the function of the callback
        doAnswer(new Answer[CommRegistrar]() {
          override def answer(p1: InvocationOnMock): CommRegistrar = {
            linkFunc = p1.getArguments.head.asInstanceOf[OpenCallback]
            mockCommRegistrar
          }
        }).when(mockCommRegistrar).addOpenHandler(any(classOf[OpenCallback]))

        // Call register and verify that the underlying registrar method called
        commManager.register(TestTargetName)
        verify(mockCommRegistrar).addOpenHandler(any(classOf[OpenCallback]))

        // Trigger the callback to test what it does
        linkFunc(mock[CommWriter], TestCommId, TestTargetName, v5.Data())
        verify(mockCommRegistrar).link(TestTargetName, TestCommId)
      }

      // TODO: Is there a better/cleaner way to assert the contents of the callback?
      it("should add an unlink callback to the received close events") {
        var unlinkFunc: CloseCallback = null

        // Setup used to extract the function of the callback
        doAnswer(new Answer[CommRegistrar]() {
          override def answer(p1: InvocationOnMock): CommRegistrar = {
            unlinkFunc = p1.getArguments.head.asInstanceOf[CloseCallback]
            mockCommRegistrar
          }
        }).when(mockCommRegistrar).addCloseHandler(any(classOf[CloseCallback]))

        // Call register and verify that the underlying registrar method called
        commManager.register(TestTargetName)
        verify(mockCommRegistrar).addCloseHandler(any(classOf[CloseCallback]))

        // Trigger the callback to test what it does
        unlinkFunc(mock[CommWriter], TestCommId, v5.Data())
        verify(mockCommRegistrar).unlink(TestCommId)
      }
    }

    describe("#open") {
      it("should return a new CommWriter instance that links during open") {
        val commWriter = commManager.open(TestTargetName, v5.Data())

        commWriter.writeOpen(TestTargetName)

        // Should have been executed once during commManager.open(...) and
        // another time with the call above
        verify(mockCommRegistrar, times(2))
          .link(mockEq(TestTargetName), any[v5.UUID])
      }

      it("should return a new CommWriter instance that unlinks during close") {
        val commWriter = commManager.open(TestTargetName, v5.Data())

        commWriter.writeClose(v5.Data())

        verify(mockCommRegistrar).unlink(any[v5.UUID])
      }

      it("should initiate a comm_open") {
        commManager.open(TestTargetName, v5.Data())

        verify(mockCommWriter).writeOpen(TestTargetName, v5.Data())
      }
    }
  }

}
