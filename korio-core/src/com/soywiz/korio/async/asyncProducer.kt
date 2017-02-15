package com.soywiz.korio.async

import com.soywiz.korio.coroutine.korioStartCoroutine
import com.soywiz.korio.coroutine.korioSuspendCoroutine
import com.soywiz.korio.stream.AsyncInputStream
import com.soywiz.korio.stream.AsyncOutputStream
import com.soywiz.korio.util.BYTES_EMPTY
import java.io.Closeable
import java.util.*
import java.util.concurrent.CancellationException

typealias CancelHandler = Signal<Unit>

interface Consumer<T> : Closeable {
	suspend fun consume(cancel: CancelHandler? = null): T?
}

interface Producer<T> : Closeable {
	fun produce(v: T): Unit
}

open class ProduceConsumer<T> : Consumer<T>, Producer<T> {
	val items = LinkedList<T>()
	val consumers = LinkedList<(T?) -> Unit>()
	private var closed = false

	override fun produce(v: T) {
		synchronized(this) { items.addLast(v) }
		flush()
	}

	override fun close() {
		synchronized(this) {
			items.addLast(null)
			closed = true
		}
		flush()
	}

	private fun flush() {
		while (true) {
			var done = false
			var consumer: ((T?) -> Unit)? = null
			var item: T? = null
			synchronized(this) {
				if (consumers.isNotEmpty() && items.isNotEmpty()) {
					consumer = consumers.removeFirst()
					item = items.removeFirst()
				} else {
					done = true
				}
			}
			if (done) break
			consumer!!(item)
		}
	}

	suspend override fun consume(cancel: CancelHandler?): T? = korioSuspendCoroutine { c ->
		val consumer: (T?) -> Unit = {
			c.resume(it)
			//if (it != null) c.resume(it) else c.resumeWithException(EOFException())
		}
		if (cancel != null) {
			cancel {
				synchronized(this) {
					consumers -= consumer
				}
				c.resumeWithException(CancellationException())
			}
		}
		synchronized(this) {
			consumers += consumer
		}
		flush()
	}
}

fun <T> asyncProducer(callback: suspend Producer<T>.() -> Unit): Consumer<T> {
	val p = ProduceConsumer<T>()

	callback.korioStartCoroutine(p, completion = EmptyContinuation)
	return p
}

fun Producer<ByteArray>.toAsyncOutputStream() = AsyncProducerStream(this)
fun Consumer<ByteArray>.toAsyncInputStream() = AsyncConsumerStream(this)

class AsyncProducerStream(val producer: Producer<ByteArray>) : AsyncOutputStream {
	suspend override fun write(buffer: ByteArray, offset: Int, len: Int) {
		producer.produce(Arrays.copyOfRange(buffer, offset, offset + len))
	}

	suspend override fun close() {
		producer.close()
	}
}

class AsyncConsumerStream(val consumer: Consumer<ByteArray>) : AsyncInputStream {
	var eof = false
	var current = BYTES_EMPTY
	var currentPos = 0
	val available get() = current.size - currentPos

	suspend private fun ensureNonEmptyBuffer() {
		while (available == 0) {
			currentPos = 0
			val item = consumer.consume()
			if (item != null) {
				current = item
			} else {
				current = BYTES_EMPTY
				eof = true
				break
			}
		}
	}

	suspend override fun read(buffer: ByteArray, offset: Int, len: Int): Int {
		ensureNonEmptyBuffer()
		if (eof) return -1
		val actualRead = Math.min(len, available)
		System.arraycopy(current, currentPos, buffer, offset, actualRead)
		currentPos += actualRead
		return actualRead
	}

	suspend override fun close() {
		consumer.close()
	}
}