package org.http4s.netty.utils

import scalaz.{\/-, -\/, \/}
import scalaz.stream.Process.End

import org.http4s.{BodyChunk, TrailerChunk, Chunk}
import java.util.{Queue, LinkedList}

/**
 * @author Bryce Anderson
 *         Created on 11/27/13
 */
class ChunkHandler(val highWater: Int) {

  type CB = Throwable \/ Chunk => Unit
  private val queue: Queue[Chunk] = new LinkedList[Chunk]()
  private var cb: CB = null
  private var endThrowable: Throwable = null
  private var closed = false
  private var queuesize = 0

  override def toString() = {
    val sb = new StringBuilder
    sb.append(s"${this.getClass.getName}(")
    val it = queue.iterator()
    while(it.hasNext) sb.append(it.next().toString())
    sb.result()
  }

  def queueSize(): Int = queuesize

  def onQueueFull(): Unit = {}

  def isClosed(): Boolean = closed

  /** Called when a chunk is sent out. This method will be called synchronously AFTER the cb is fired */
  def onBytesSent(n: Int): Unit = {}

  def close(trailer: TrailerChunk): Unit = queue.synchronized {
    enque(trailer)
    close()
  }

  def close(): Unit = queue.synchronized {
    closed = true
    endThrowable = End
    if (cb != null) {
      try cb(-\/(endThrowable))
      catch { case t: Throwable => cbException(t, cb) }
      finally cb = null
    }
  }

  def kill(t: Throwable): Unit = queue.synchronized {
    queuesize = 0
    closed = true
    endThrowable = t
    queue.clear()
    if (cb != null) {
      try cb(-\/(t))
      catch { case t: Throwable => cbException(t, cb) }
      finally cb = null
    }
  }

  def request(cb: CB): Int = queue.synchronized {
    assert(this.cb == null)
    if (closed && queue.isEmpty) {
      println("Sending the endThrowable.")
      cb(-\/(endThrowable))
      0
    }
    else {
      val chunk = queue.poll()
      if (chunk == null) {
        queuesize = -1
        this.cb = cb
      } else {
        try cb(\/-(chunk))
        catch { case t: Throwable => cbException(t, cb) }
        finally if (chunk.isInstanceOf[BodyChunk]) {
          queuesize -= chunk.asInstanceOf[BodyChunk].length
          onBytesSent(chunk.asInstanceOf[BodyChunk].length)
        }
      }
      queuesize
    }
  }

  /** enqueues a chunk if the channel is open
    *
    * @param chunk Chunk to enqueue
    * @return -1 if the queue is closed, else the current queue size (may be 0 if a cb was present)
    */
  def enque(chunk: Chunk): Int = queue.synchronized {
    if (closed) return -1
    if (this.cb != null) {
      assert(queue.isEmpty)
      val cb = this.cb
      this.cb = null
      queuesize = 0   // No chunks, no callbacks.

      try cb(\/-(chunk))
      catch { case t: Throwable => cbException(t, cb) }
      finally if (chunk.isInstanceOf[BodyChunk]) {
        onBytesSent(chunk.asInstanceOf[BodyChunk].length)
      }

    } else {
      queue.add(chunk)
      if (chunk.isInstanceOf[BodyChunk]) {
        queuesize += chunk.asInstanceOf[BodyChunk].length
        if (queuesize >= highWater) onQueueFull()
      }
    }
    queuesize
  }

  private def cbException(t: Throwable, cb: CB) {
    throw new Exception(s"Callback $cb threw and exception.", t)
  }
}